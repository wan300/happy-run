package com.lepao.companion.capture

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DnsRelayTest {
    @Test fun cacheRestoresIdAgesTtlAndExpires() {
        var now = 0L
        val cache = DnsCache { now }
        val query = query(123)
        val answer = DnsPacket.buildResponse(query, answers = listOf(0x01020304))
        cache.put(query, answer)
        now = 2_000
        val cached = requireNotNull(cache.get(query(456)))
        assertEquals(456, IpPacket.u16(cached, 0))
        assertEquals(58, IpPacket.u16(cached, DnsPacket.questionEnd(cached) + 8))
        assertEquals(60, IpPacket.u16(answer, DnsPacket.questionEnd(answer) + 8))
        now = 60_000
        assertNull(cache.get(query))
    }

    @Test fun cacheDoesNotStoreErrorsTruncatedMismatchedOrZeroTtlAnswers() {
        val query = query(123)
        val valid = DnsPacket.buildResponse(query, answers = listOf(0x01020304))
        val candidates = listOf(
            DnsPacket.buildErrorResponse(query),
            valid.copyOf().apply { IpPacket.writeU16(this, 2, 0x8380) },
            valid.copyOf().apply { IpPacket.writeU16(this, 0, 124) },
            valid.copyOf().apply { IpPacket.writeU32(this, DnsPacket.questionEnd(this) + 6, 0) },
            valid.copyOf(valid.size - 1),
        )
        for (answer in candidates) {
            val cache = DnsCache()
            cache.put(query, answer)
            assertNull(cache.get(query))
        }
    }

    @Test fun cacheSeparatesQueryTypesAndCapsSize() {
        val cache = DnsCache()
        val first = query(1)
        cache.put(first, DnsPacket.buildResponse(first, answers = listOf(0x01020304)))
        val aaaa = first.copyOf().apply { IpPacket.writeU16(this, size - 4, 28) }
        assertNull(cache.get(aaaa))
        repeat(129) {
            val query = query(it, "host$it.test")
            cache.put(query, DnsPacket.buildResponse(query, answers = listOf(0x01020304)))
        }
        assertNull(cache.get(first))
        assertNotNull(cache.get(query(9, "host128.test")))
    }

    @Test(timeout = 10_000) fun relayCachesResponsesAndReusesProtectedWorkerSockets() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { upstream ->
            val calls = AtomicInteger()
            val protections = AtomicInteger()
            val upstreamPorts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val serving = thread(isDaemon = true) {
                try {
                    while (!upstream.isClosed) {
                        val packet = DatagramPacket(ByteArray(4_096), 4_096)
                        upstream.receive(packet)
                        calls.incrementAndGet()
                        upstreamPorts.add(packet.port)
                        val query = packet.data.copyOf(packet.length)
                        // A late/mismatched reply must not become this query's answer.
                        val wrong = DnsPacket.buildResponse(query, answers = listOf(0x09090909))
                        IpPacket.writeU16(wrong, 0, (IpPacket.u16(query, 0) + 1) and 0xffff)
                        upstream.send(DatagramPacket(wrong, wrong.size, packet.socketAddress))
                        val answer = DnsPacket.buildResponse(query, answers = listOf(0x01020304))
                        upstream.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                    }
                } catch (_: java.net.SocketException) { }
            }
            val replies = LinkedBlockingQueue<ByteArray>()
            val protectedSockets = java.util.concurrent.CopyOnWriteArrayList<DatagramSocket>()
            val relay = DnsRelay(listOf(InetAddress.getLoopbackAddress()),
                { protections.incrementAndGet(); protectedSockets.add(it); true }, { replies.add(it) },
                upstreamPort = upstream.localPort)
            try {
                fun resolve(query: ByteArray): ByteArray {
                    val packet = IpPacket.buildUdp(0x0a420002, 32100, 0x0a420001, 53, query)
                    relay.submit(packet, packet.size)
                    val reply = requireNotNull(replies.poll(2, TimeUnit.SECONDS))
                    return requireNotNull(IpPacket.udpPayload(requireNotNull(IpPacket.parse(reply, reply.size)).payload))
                }
                assertEquals(123, IpPacket.u16(resolve(query(123)), 0))
                assertEquals(456, IpPacket.u16(resolve(query(456)), 0))
                assertEquals("second lookup must use cache", 1, calls.get())
                repeat(8) { index ->
                    val answer = resolve(query(index, "host$index.test"))
                    assertEquals(index, IpPacket.u16(answer, 0))
                    assertEquals(0x0102, IpPacket.u16(answer, answer.size - 4))
                }
                assertEquals(9, calls.get())
                assertTrue("at most one protected socket per worker/resolver", protections.get() <= 4)
                assertTrue(upstreamPorts.size <= 4)
            } finally {
                relay.close()
                assertTrue(protectedSockets.all { it.isClosed })
                upstream.close()
                serving.join(1_000)
            }
        }
    }

    @Test(timeout = 5_000) fun dnsTimeoutReturnsServfailAndStopClosesSocket() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { blackhole ->
            val replies = LinkedBlockingQueue<ByteArray>()
            val relay = DnsRelay(listOf(InetAddress.getLoopbackAddress()), { true }, { replies.add(it) },
                upstreamPort = blackhole.localPort, queryTimeoutMs = 100)
            try {
                val packet = IpPacket.buildUdp(0x0a420002, 32100, 0x0a420001, 53, query(123))
                relay.submit(packet, packet.size)
                val reply = requireNotNull(replies.poll(2, TimeUnit.SECONDS))
                val dns = requireNotNull(IpPacket.udpPayload(requireNotNull(IpPacket.parse(reply, reply.size)).payload))
                assertEquals(123, IpPacket.u16(dns, 0))
                assertEquals(2, IpPacket.u16(dns, 2) and 0xf)
            } finally { relay.close() }
        }
    }

    private fun query(id: Int, name: String = "example.test"): ByteArray {
        val labels = name.split('.')
        val output = java.io.ByteArrayOutputStream()
        output.write(ByteArray(12).apply { IpPacket.writeU16(this, 0, id); IpPacket.writeU16(this, 2, 0x100); IpPacket.writeU16(this, 4, 1) })
        for (label in labels) { output.write(label.length); output.write(label.toByteArray()) }
        output.write(byteArrayOf(0, 0, 1, 0, 1))
        return output.toByteArray()
    }
}
