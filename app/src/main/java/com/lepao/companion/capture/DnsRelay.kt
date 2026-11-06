package com.lepao.companion.capture

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relays the DNS queries the allowed application sends into the TUN device.
 *
 * Each `IPv4/UDP:53` packet is re-sent from a protected real socket to the resolver the device used
 * before the VPN came up, and the raw answer is wrapped back into a TUN packet. Traffic that is not
 * DNS is left alone; TCP SYNs to 443 are counted so the service can tell "the app ignored the proxy".
 */
class DnsRelay(
    private val upstreams: List<InetAddress>,
    private val protect: (DatagramSocket) -> Boolean,
    private val output: (ByteArray) -> Unit,
    private val onDiag: (String) -> Unit = {},
    private val upstreamPort: Int = DNS_PORT,
    private val queryTimeoutMs: Int = 1_500,
) {

    private val executor = ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(32), { task -> Thread(task, "capture-dns").apply { isDaemon = true } })
    private val cache = DnsCache()
    private val sockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    private val workerSockets = ThreadLocal.withInitial { mutableMapOf<InetAddress, DatagramSocket>() }
    private val ids = AtomicInteger(java.security.SecureRandom().nextInt(65_536))

    @Volatile
    var directSynCount: Int = 0
        private set

    @Volatile
    var dnsQueryCount: Int = 0
        private set

    @Volatile
    private var closed = false

    /** Accepts one packet read from the TUN device; never blocks the TUN reader. */
    fun submit(packet: ByteArray, length: Int) {
        if (closed) return
        val parsed = IpPacket.parse(packet, length) ?: return
        when (parsed.protocol) {
            IpPacket.PROTOCOL_UDP -> {
                val sourcePort = IpPacket.udpSourcePort(parsed.payload) ?: return
                val destinationPort = IpPacket.udpDestinationPort(parsed.payload) ?: return
                if (destinationPort != DNS_PORT) return
                val query = IpPacket.udpPayload(parsed.payload) ?: return
                dnsQueryCount += 1
                val clientAddress = parsed.sourceAddress
                val serverAddress = parsed.destinationAddress
                val started = System.nanoTime()
                val cached = cache.get(query)
                if (cached != null) {
                    writeReply(clientAddress, sourcePort, serverAddress, destinationPort, cached)
                    onDiag("dns cache=hit total_ms=0")
                    return
                }
                try {
                    executor.execute {
                        resolve(clientAddress, sourcePort, serverAddress, destinationPort, query, started)
                    }
                } catch (_: RejectedExecutionException) {
                    writeReply(clientAddress, sourcePort, serverAddress, destinationPort, DnsPacket.buildErrorResponse(query))
                    onDiag("dns queue full")
                }
            }
            IpPacket.PROTOCOL_TCP -> {
                if (IpPacket.tcpDestinationPort(parsed.payload) == HTTPS_PORT &&
                    IpPacket.isTcpSyn(parsed.payload)
                ) {
                    directSynCount += 1
                }
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
        for (socket in sockets) socket.close()
        sockets.clear()
        cache.clear()
    }

    private fun resolve(
        clientAddress: Int,
        clientPort: Int,
        serverAddress: Int,
        serverPort: Int,
        query: ByteArray,
        started: Long,
    ) {
        val queueMs = (System.nanoTime() - started) / 1_000_000
        val deadline = started + TOTAL_TIMEOUT_MS * 1_000_000L
        val answer = cache.get(query) ?: queryUpstream(query, deadline)?.also { cache.put(query, it) }
        val reply = answer ?: DnsPacket.buildErrorResponse(query)
        onDiag("dns cache=miss queue_ms=$queueMs total_ms=${(System.nanoTime() - started) / 1_000_000} success=${answer != null}")
        writeReply(clientAddress, clientPort, serverAddress, serverPort, reply)
    }

    private fun writeReply(clientAddress: Int, clientPort: Int, serverAddress: Int, serverPort: Int, reply: ByteArray) {
        if (closed) return
        val packet = IpPacket.buildUdp(serverAddress, serverPort, clientAddress, clientPort, reply)
        try {
            output(packet)
        } catch (error: Exception) {
            onDiag("dns write-back failed: ${error.javaClass.simpleName}")
        }
    }

    private fun queryUpstream(query: ByteArray, deadline: Long): ByteArray? {
        if (DnsPacket.parseQuery(query) == null || upstreams.isEmpty()) return null
        val wireQuery = query.copyOf()
        IpPacket.writeU16(wireQuery, 0, ids.incrementAndGet() and 0xffff)
        for (upstream in upstreams.distinct().take(2)) {
            if (closed || System.nanoTime() >= deadline) break
            try {
                val local = requireNotNull(workerSockets.get())
                val socket = local[upstream]?.takeUnless { it.isClosed } ?: DatagramSocket().also {
                    sockets.add(it)
                    if (closed || !protect(it)) { it.close(); sockets.remove(it); return null }
                    // Connected UDP validates the source address and port of every reply.
                    it.connect(InetSocketAddress(upstream, upstreamPort))
                    local[upstream] = it
                }
                socket.send(DatagramPacket(wireQuery, wireQuery.size))
                val attemptDeadline = minOf(deadline, System.nanoTime() + queryTimeoutMs * 1_000_000L)
                val buffer = ByteArray(MAX_REPLY_BYTES)
                while (!closed) {
                    val remaining = (attemptDeadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.coerceAtLeast(1).toInt()
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val answer = buffer.copyOf(response.length)
                    // Ignore late replies from earlier uses of this worker's socket.
                    if (!DnsCache.matches(wireQuery, answer)) continue
                    answer[0] = query[0]
                    answer[1] = query[1]
                    return answer
                }
            } catch (_: SocketTimeoutException) {
                onDiag("dns upstream timeout")
            } catch (error: Exception) {
                workerSockets.get()?.remove(upstream)?.let { it.close(); sockets.remove(it) }
                if (!closed) onDiag("dns relay failed: ${error.javaClass.simpleName}")
            }
        }
        return null
    }

    companion object {
        const val DNS_PORT: Int = 53
        const val HTTPS_PORT: Int = 443

        private const val WORKERS = 4
        private const val TOTAL_TIMEOUT_MS = 4_000
        private const val MAX_REPLY_BYTES = 65_507
    }
}
