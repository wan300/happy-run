package com.lepao.companion.capture

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Bounded byte relay. One selector thread serves all established, uninspected tunnels. */
internal class TunnelRelay(
    private val protect: (Socket) -> Boolean,
    private val onDiag: (String) -> Unit,
) : Closeable {
    private val selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<PairState>()
    private val pairs = ConcurrentHashMap.newKeySet<PairState>()
    private val count = AtomicInteger()
    private val connecting = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private val thread = Thread({ runLoop() }, "capture-relay-selector").apply {
        isDaemon = true
        start()
    }

    /** Runs only the bounded connection setup on the caller; takes ownership on success. */
    fun open(client: Socket, authority: HttpWire.Authority, initialRequest: ByteArray? = null): Boolean {
        if (closed) return false
        if (count.incrementAndGet() > MAX_TUNNELS) {
            count.decrementAndGet()
            respond(client, 503)
            return false
        }
        val upstream = SocketChannel.open()
        connecting.add(upstream)
        var acknowledged = false
        try {
            if (closed) throw IOException("Relay stopped")
            val started = System.nanoTime()
            val target = upstream.socket()
            if (!protect(target)) throw IOException("Socket protection failed")
            target.tcpNoDelay = true
            target.connect(InetSocketAddress(authority.host, authority.port), CONNECT_TIMEOUT_MS)
            if (initialRequest == null) {
                client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
            } else {
                target.getOutputStream().write(initialRequest)
                target.getOutputStream().flush()
            }
            acknowledged = true
            val downstream = client.channel ?: throw IOException("Client is not channel-backed")
            downstream.configureBlocking(false)
            upstream.configureBlocking(false)
            val pair = PairState(downstream, upstream)
            synchronized(lifecycleLock) {
                if (closed) throw IOException("Relay stopped")
                pairs.add(pair)
                pending.add(pair)
                selector.wakeup()
            }
            onDiag("proxy relay connect_ms=${(System.nanoTime() - started) / 1_000_000} active=${count.get()}")
            return true
        } catch (error: Exception) {
            upstream.close()
            count.decrementAndGet()
            if (!acknowledged) respond(client, 502)
            if (!closed) onDiag("proxy relay failed: ${error.javaClass.simpleName}")
            return false
        } finally {
            connecting.remove(upstream)
        }
    }

    private fun runLoop() {
        try {
            while (!closed) {
                while (true) {
                    val pair = pending.poll() ?: break
                    try {
                        pair.left.channel.register(selector, SelectionKey.OP_READ, pair.left)
                        pair.right.channel.register(selector, SelectionKey.OP_READ, pair.right)
                    } catch (_: Exception) { remove(pair) }
                }
                selector.select(1_000)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    val end = key.attachment() as End
                    try {
                        if (!key.isValid) continue
                        if (key.isReadable) {
                            val read = end.channel.read(end.pending)
                            if (read < 0) end.eof = true
                            if (read > 0) end.pair.lastActivity = System.nanoTime()
                        }
                        if (key.isValid && key.isWritable) {
                            val buffer = end.peer.pending
                            buffer.flip()
                            val written = end.channel.write(buffer)
                            buffer.compact()
                            if (written > 0) end.pair.lastActivity = System.nanoTime()
                        }
                        update(end.pair)
                    } catch (_: Exception) { remove(end.pair) }
                }
                val now = System.nanoTime()
                for (pair in pairs) {
                    if (now - pair.lastActivity > IDLE_TIMEOUT_NANOS) remove(pair)
                }
            }
        } finally {
            for (pair in pairs) remove(pair)
            selector.close()
        }
    }

    private fun update(pair: PairState) {
        for (end in arrayOf(pair.left, pair.right)) {
            if (end.peer.eof && end.peer.pending.position() == 0 && !end.outputClosed) {
                end.channel.socket().shutdownOutput()
                end.outputClosed = true
            }
            var interests = 0
            if (!end.eof && end.pending.hasRemaining()) interests = interests or SelectionKey.OP_READ
            if (end.peer.pending.position() > 0) interests = interests or SelectionKey.OP_WRITE
            end.channel.keyFor(selector)?.interestOps(interests)
        }
        if (pair.left.outputClosed && pair.right.outputClosed) remove(pair)
    }

    private fun remove(pair: PairState) {
        if (!pairs.remove(pair)) return
        try { pair.left.channel.close() } catch (_: IOException) { }
        try { pair.right.channel.close() } catch (_: IOException) { }
        count.decrementAndGet()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            selector.wakeup()
        }
        for (channel in connecting) channel.close()
        thread.join(2_000)
    }

    private fun respond(client: Socket, code: Int) {
        try {
            client.getOutputStream().write(HttpWire.buildResponse(code, HttpWire.reasonPhrase(code),
                listOf(HttpWire.Header("Content-Length", "0"), HttpWire.Header("Connection", "close")), byteArrayOf()))
        } catch (_: IOException) { /* Client already closed. */ }
    }

    private class PairState(client: SocketChannel, upstream: SocketChannel) {
        val left = End(client, this)
        val right = End(upstream, this)
        var lastActivity = System.nanoTime()
        init { left.peer = right; right.peer = left }
    }

    private class End(val channel: SocketChannel, val pair: PairState) {
        val pending = ByteBuffer.allocate(32 * 1024)
        lateinit var peer: End
        var eof = false
        var outputClosed = false
    }

    companion object {
        private const val MAX_TUNNELS = 24
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_NANOS = 60_000_000_000L
    }
}
