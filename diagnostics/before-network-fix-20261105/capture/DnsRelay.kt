package com.lepao.companion.capture

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
) {

    private val executor: ExecutorService = Executors.newFixedThreadPool(WORKERS) { task ->
        Thread(task, "capture-dns").apply { isDaemon = true }
    }

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
                try {
                    executor.execute {
                        resolve(clientAddress, sourcePort, serverAddress, destinationPort, query)
                    }
                } catch (_: Exception) {
                    // The relay is shutting down.
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
    }

    private fun resolve(
        clientAddress: Int,
        clientPort: Int,
        serverAddress: Int,
        serverPort: Int,
        query: ByteArray,
    ) {
        val reply = queryUpstream(query) ?: DnsPacket.buildErrorResponse(query, DnsPacket.RCODE_SERVFAIL)
        if (closed) return
        val packet = IpPacket.buildUdp(serverAddress, serverPort, clientAddress, clientPort, reply)
        try {
            output(packet)
        } catch (error: Exception) {
            onDiag("dns write-back failed: ${error.javaClass.simpleName}")
        }
    }

    private fun queryUpstream(query: ByteArray): ByteArray? {
        if (upstreams.isEmpty()) {
            onDiag("dns relay has no upstream resolver")
            return null
        }
        val expectedId = DnsPacket.parseQuery(query)?.id
        for (upstream in upstreams) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!protect(socket)) {
                    onDiag("dns socket could not be protected")
                    return null
                }
                socket.soTimeout = TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, InetSocketAddress(upstream, DNS_PORT)))
                val buffer = ByteArray(MAX_REPLY_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val answer = buffer.copyOf(response.length)
                if (expectedId != null && (answer.size < 2 || IpPacket.u16(answer, 0) != expectedId)) {
                    onDiag("dns answer transaction id mismatch")
                    continue
                }
                return answer
            } catch (_: SocketTimeoutException) {
                onDiag("dns upstream timeout")
            } catch (error: Exception) {
                onDiag("dns relay failed: ${error.javaClass.simpleName}")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // Ignore.
                }
            }
        }
        return null
    }

    companion object {
        const val DNS_PORT: Int = 53
        const val HTTPS_PORT: Int = 443

        private const val WORKERS = 2
        private const val TIMEOUT_MS = 4_000
        private const val MAX_REPLY_BYTES = 4 * 1024
    }
}
