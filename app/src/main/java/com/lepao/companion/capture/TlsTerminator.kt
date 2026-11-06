package com.lepao.companion.capture

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Server-side TLS termination without helper threads.
 *
 * The previous implementation bridged every client connection into a loopback `SSLServerSocket`
 * with two pump threads. The official app opens a burst of connections from several SDKs, and the
 * resulting thread growth aborted the process on the Huawei ART runtime ("Unable to create
 * protected region in stack"). This implementation drives an [SSLEngine] directly on the accepted
 * socket from the proxy's worker thread: one thread per connection, no pumps.
 *
 * TLS 1.2 with ALPN `http/1.1` is negotiated; the official client accepts it and it avoids the
 * post-handshake bookkeeping TLS 1.3 would need in this blocking wrapper.
 */
class TlsTerminator(private val sslContext: SSLContext) {

    class Tunnel internal constructor(
        val input: InputStream,
        val output: OutputStream,
        private val channel: EngineChannel,
    ) : Closeable {
        /** Changes the socket read timeout, e.g. to shorten an idle keep-alive wait. */
        fun setReadTimeout(millis: Int) = channel.setReadTimeout(millis)
        override fun close() = channel.close()
    }

    fun open(client: Socket): Tunnel {
        client.soTimeout = SESSION_TIMEOUT_MS
        client.tcpNoDelay = true
        val engine = sslContext.createSSLEngine()
        engine.useClientMode = false
        engine.needClientAuth = false
        // This blocking wrapper deliberately implements TLS 1.2, not TLS 1.3 post-handshake I/O.
        engine.enabledProtocols = arrayOf("TLSv1.2")
        try {
            engine.sslParameters = engine.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
        } catch (_: Exception) {
            // ALPN hints are best effort; the handshake still works without them.
        }
        System.err.println(
            "tls-engine setup mode=${if (engine.useClientMode) "client" else "server"} " +
                "protocols=${engine.enabledProtocols.toList()} ciphers=${engine.enabledCipherSuites.take(3)} " +
                "paramsCiphers=${engine.sslParameters.cipherSuites.take(3)}",
        )
        val channel = EngineChannel(client, engine)
        try {
            channel.handshake()
        } catch (error: Exception) {
            System.err.println("tls-handshake-fail: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
        return Tunnel(channel.inputStream, channel.outputStream, channel)
    }

    internal class EngineChannel(private val socket: Socket, private val engine: SSLEngine) {
        private val socketInput = socket.getInputStream()
        private val socketOutput = socket.getOutputStream()

        private val session = engine.session
        private val appIn = ByteBuffer.allocate(maxOf(session.applicationBufferSize, 64 * 1024))
        private val netIn = ByteBuffer.allocate(maxOf(session.packetBufferSize, 32 * 1024))
        private val appOut = ByteBuffer.allocate(maxOf(session.applicationBufferSize, 64 * 1024))
        private val netOut = ByteBuffer.allocate(maxOf(session.packetBufferSize, 32 * 1024))

        private var appInReadable = false

        val inputStream: InputStream = object : InputStream() {
            override fun read(): Int {
                val single = ByteArray(1)
                val count = read(single, 0, 1)
                return if (count <= 0) -1 else single[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                while (true) {
                    if (appInReadable) {
                        if (appIn.hasRemaining()) {
                            val count = minOf(length, appIn.remaining())
                            appIn.get(buffer, offset, count)
                            if (!appIn.hasRemaining()) {
                                appIn.clear()
                                appInReadable = false
                            }
                            return count
                        }
                        appIn.clear()
                        appInReadable = false
                    }
                    if (netIn.position() == 0 && fillNet() < 0) return -1
                    val result = unwrap()
                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            if (appIn.position() > 0) {
                                appIn.flip()
                                appInReadable = true
                            }
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            // A partial record remains in netIn; always append more bytes.
                            if (fillNet() < 0) throw EOFException("Truncated TLS record")
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            throw SSLException("TLS application buffer overflow")
                        }
                        SSLEngineResult.Status.CLOSED -> return -1
                    }
                    servicePostHandshake()
                }
            }

            override fun close() = this@EngineChannel.close()
        }

        val outputStream: OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                var written = 0
                while (written < length) {
                    val chunk = minOf(length - written, appOut.capacity())
                    appOut.clear()
                    appOut.put(buffer, offset + written, chunk)
                    appOut.flip()
                    while (appOut.hasRemaining()) {
                        netOut.clear()
                        val result = engine.wrap(appOut, netOut)
                        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            throw SSLException("TLS output buffer overflow")
                        }
                        if (result.status == SSLEngineResult.Status.CLOSED) {
                            throw SSLException("TLS engine closed while writing")
                        }
                        netOut.flip()
                        writeAll(netOut)
                    }
                    written += chunk
                }
                socketOutput.flush()
            }

            override fun flush() = socketOutput.flush()

            override fun close() = this@EngineChannel.close()
        }

        fun handshake() {
            engine.beginHandshake()
            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                    -> break

                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()

                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapHandshake()

                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                    -> {
                        if (netIn.position() == 0 && fillNet() < 0) {
                            throw EOFException("TLS handshake ended before ClientHello")
                        }
                        val result = unwrap()
                        when (result.status) {
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                                if (fillNet() < 0) throw EOFException("Truncated TLS handshake")
                            }
                            SSLEngineResult.Status.CLOSED -> throw SSLException("TLS handshake closed")
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> throw SSLException("TLS handshake overflow")
                            else -> Unit
                        }
                    }

                    else -> break
                }
            }
            if (appIn.position() > 0) {
                appIn.flip()
                appInReadable = true
            }
        }

        fun setReadTimeout(millis: Int) {
            socket.soTimeout = millis
        }

        private fun servicePostHandshake() {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapHandshake()
                else -> Unit
            }
        }

        private fun runTasks() {
            while (true) {
                val task = engine.delegatedTask ?: break
                task.run()
            }
        }

        private fun wrapHandshake() {
            netOut.clear()
            val result = engine.wrap(EMPTY, netOut)
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                throw SSLException("TLS handshake wrap overflow")
            }
            netOut.flip()
            writeAll(netOut)
        }

        /** Reads raw TLS bytes into [netIn] (which is always kept in write mode). */
        private fun fillNet(): Int {
            val position = netIn.position()
            if (position >= netIn.capacity()) throw SSLException("TLS input buffer is full")
            val count = try {
                socketInput.read(netIn.array(), position, netIn.capacity() - position)
            } catch (error: SocketTimeoutException) {
                throw IOException("TLS peer idle timeout", error)
            }
            if (count > 0) netIn.position(position + count)
            return count
        }

        private fun unwrap(): SSLEngineResult {
            netIn.flip()
            val result = engine.unwrap(netIn, appIn)
            netIn.compact()
            return result
        }

        private fun writeAll(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                socketOutput.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
                buffer.position(buffer.limit())
            }
            socketOutput.flush()
        }

        fun close() {
            try {
                engine.closeOutbound()
            } catch (_: Exception) {
                // Engine may already be closed.
            }
            try {
                netOut.clear()
                engine.wrap(EMPTY, netOut)
                netOut.flip()
                writeAll(netOut)
            } catch (_: Exception) {
                // Best effort close_notify.
            }
            try {
                socket.close()
            } catch (_: IOException) {
                // Ignore.
            }
        }

        companion object {
            private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
        }
    }

    companion object {
        private const val SESSION_TIMEOUT_MS = 20_000
    }
}
