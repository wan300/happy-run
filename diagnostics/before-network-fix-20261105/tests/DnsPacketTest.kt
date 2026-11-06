package com.lepao.companion.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/** Framing checks for the TUN reader: DNS queries, DNS replies and the ICMP-free UDP packet shape. */
class DnsPacketTest {

    private fun dnsQuery(id: Int, name: String, qtype: Int = 1): ByteArray {
        val output = ByteArrayOutputStream()
        output.write((id shr 8) and 0xFF)
        output.write(id and 0xFF)
        output.write(0x01) // RD
        output.write(0x00)
        output.write(0x00) // QDCOUNT = 1
        output.write(0x01)
        // ANCOUNT, NSCOUNT and ARCOUNT stay zero.
        repeat(6) { output.write(0) }
        for (label in name.split('.')) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        output.write((qtype shr 8) and 0xFF)
        output.write(qtype and 0xFF)
        output.write(0x00) // QCLASS = IN
        output.write(0x01)
        return output.toByteArray()
    }

    @Test
    fun queryParsingReadsNameAndType() {
        val query = DnsPacket.parseQuery(dnsQuery(0x1234, "api2.lptiyu.com"))
        requireNotNull(query)
        assertEquals(0x1234, query.id)
        assertEquals("api2.lptiyu.com", query.name)
        assertEquals(1, query.qtype)
        assertEquals(1, query.qclass)
        assertTrue("recursion desired is set", query.flags and 0x0100 != 0)

        assertEquals(".", DnsPacket.parseQuery(dnsQuery(1, ""))?.name)
        assertNull(DnsPacket.parseQuery(ByteArray(4)))
        assertNull("responses that answer nothing are not queries", DnsPacket.parseQuery(ByteArray(12)))
    }

    @Test
    fun errorResponseMirrorsTheQuestionAndCarriesTheCode() {
        val query = dnsQuery(0x2A2A, "api2.lptiyu.com")
        val response = DnsPacket.buildErrorResponse(query, DnsPacket.RCODE_SERVFAIL)

        assertEquals("transaction id is echoed", 0x2A2A, IpPacket.u16(response, 0))
        val flags = IpPacket.u16(response, 2)
        assertTrue("QR bit set", flags and 0x8000 != 0)
        assertTrue("RD echoed", flags and 0x0100 != 0)
        assertTrue("RA set", flags and 0x0080 != 0)
        assertEquals(DnsPacket.RCODE_SERVFAIL, flags and 0x0F)
        assertEquals(1, IpPacket.u16(response, 4))
        assertEquals(0, IpPacket.u16(response, 6))

        val questionEnd = DnsPacket.questionEnd(query)
        assertEquals(
            "question section is copied verbatim",
            query.copyOfRange(12, questionEnd).toList(),
            response.copyOfRange(12, questionEnd).toList(),
        )
        assertEquals(query.size, response.size)
    }

    @Test
    fun answerResponseCarriesAnARecord() {
        val query = dnsQuery(7, "api2.lptiyu.com")
        val response = DnsPacket.buildResponse(query, DnsPacket.RCODE_NO_ERROR, listOf(0x0A420002))
        assertEquals(1, IpPacket.u16(response, 4))
        assertEquals(1, IpPacket.u16(response, 6))
        val questionEnd = DnsPacket.questionEnd(query)
        assertEquals(0xC00C, IpPacket.u16(response, questionEnd))
        assertEquals(1, IpPacket.u16(response, questionEnd + 2))
        assertEquals(1, IpPacket.u16(response, questionEnd + 4))
        assertEquals(4, IpPacket.u16(response, questionEnd + 10))
        assertEquals(0x0A420002, IpPacket.u32(response, questionEnd + 12))
        assertEquals(questionEnd + 16, response.size)
    }

    @Test
    fun udpPacketsRoundTripWithValidChecksums() {
        val query = dnsQuery(0x0102, "api2.lptiyu.com")
        val client = requireNotNull(IpPacket.ipv4ToInt("10.66.0.2"))
        val server = requireNotNull(IpPacket.ipv4ToInt("10.66.0.1"))
        val packet = IpPacket.buildUdp(client, 53124, server, 53, query)

        val parsed = requireNotNull(IpPacket.parse(packet, packet.size))
        assertEquals(IpPacket.PROTOCOL_UDP, parsed.protocol)
        assertEquals(client, parsed.sourceAddress)
        assertEquals(server, parsed.destinationAddress)
        assertEquals(53124, IpPacket.udpSourcePort(parsed.payload))
        assertEquals(53, IpPacket.udpDestinationPort(parsed.payload))
        assertEquals(query.toList(), IpPacket.udpPayload(parsed.payload)?.toList())

        assertEquals("IPv4 header verifies", 0, IpPacket.checksum(packet, 0, IpPacket.IPV4_HEADER_SIZE))
        val udpLength = IpPacket.udpPayload(parsed.payload)!!.size + IpPacket.UDP_HEADER_SIZE
        val pseudo = IpPacket.pseudoHeaderSum(client, server, IpPacket.PROTOCOL_UDP, udpLength)
        assertEquals(
            "UDP checksum verifies",
            0,
            IpPacket.checksum(packet, IpPacket.IPV4_HEADER_SIZE, udpLength, pseudo),
        )
    }

    @Test
    fun addressHelpersCoverTheTunnelRange() {
        assertEquals("10.66.0.2", IpPacket.intToIpv4(requireNotNull(IpPacket.ipv4ToInt("10.66.0.2"))))
        assertNull(IpPacket.ipv4ToInt("10.66.0"))
        assertNull(IpPacket.ipv4ToInt("10.66.0.256"))
        assertNull(IpPacket.ipv4ToInt("a.b.c.d"))
        assertNull(IpPacket.parse(byteArrayOf(0x60, 0, 0, 0), 4))
    }

    @Test
    fun tcpSynToHttpsIsCountedWhileDataPacketsAreNot() {
        val relay = DnsRelay(
            upstreams = emptyList(),
            protect = { true },
            output = {},
        )
        try {
            relay.submit(tcpPacket(destinationPort = 443, flags = 0x02), 40)
            relay.submit(tcpPacket(destinationPort = 443, flags = 0x02), 40)
            relay.submit(tcpPacket(destinationPort = 443, flags = 0x12), 40) // SYN+ACK is not a new connection
            relay.submit(tcpPacket(destinationPort = 8443, flags = 0x02), 40)
            relay.submit(tcpPacket(destinationPort = 443, flags = 0x10), 40) // plain ACK
            assertEquals(2, relay.directSynCount)
            assertEquals(0, relay.dnsQueryCount)
        } finally {
            relay.close()
        }
    }

    private fun tcpPacket(destinationPort: Int, flags: Int): ByteArray {
        val payload = ByteArray(IpPacket.TCP_HEADER_SIZE)
        IpPacket.writeU16(payload, 0, 40000) // source port
        IpPacket.writeU16(payload, 2, destinationPort)
        payload[12] = 0x50 // data offset 5
        payload[13] = flags.toByte()
        val total = IpPacket.IPV4_HEADER_SIZE + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        IpPacket.writeU16(packet, 2, total)
        packet[8] = 64
        packet[9] = IpPacket.PROTOCOL_TCP.toByte()
        IpPacket.writeU32(packet, 12, requireNotNull(IpPacket.ipv4ToInt("10.66.0.2")))
        IpPacket.writeU32(packet, 16, requireNotNull(IpPacket.ipv4ToInt("93.184.216.34")))
        IpPacket.writeU16(packet, 10, IpPacket.checksum(packet, 0, IpPacket.IPV4_HEADER_SIZE))
        payload.copyInto(packet, IpPacket.IPV4_HEADER_SIZE)
        return packet
    }

    @Test
    fun dnsQueryIsRelayedThroughTheProtectCallback() {
        val query = dnsQuery(0x5151, "api2.lptiyu.com")
        val client = requireNotNull(IpPacket.ipv4ToInt("10.66.0.2"))
        val server = requireNotNull(IpPacket.ipv4ToInt("10.66.0.1"))
        val incoming = IpPacket.buildUdp(client, 40000, server, 53, query)
        val written = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val relay = DnsRelay(
            upstreams = emptyList(),
            protect = { true },
            output = { packet -> written.add(packet) },
        )
        try {
            relay.submit(incoming, incoming.size)
            // No upstream is configured, so the relay answers SERVFAIL without touching the network.
            val reply = written.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull("a failure reply must still reach the application", reply)
            val outer = requireNotNull(IpPacket.parse(requireNotNull(reply)))
            assertEquals("addresses are swapped", client, outer.destinationAddress)
            assertEquals("source port becomes the application port", 40000, IpPacket.udpDestinationPort(outer.payload))
            val dns = requireNotNull(IpPacket.udpPayload(outer.payload))
            assertEquals(0x5151, IpPacket.u16(dns, 0))
            assertEquals(DnsPacket.RCODE_SERVFAIL, IpPacket.u16(dns, 2) and 0x0F)
            assertEquals(1, relay.dnsQueryCount)
            assertFalse("no direct tcp traffic was observed", relay.directSynCount > 0)
        } finally {
            relay.close()
        }
    }

    @Test
    fun upstreamAddressesAreOnlyUsedWhenConfigured() {
        val relay = DnsRelay(
            upstreams = listOf(requireNotNull(InetAddress.getByName("127.0.0.1"))),
            protect = { true },
            output = {},
        )
        try {
            // A UDP packet that is not DNS must be ignored completely.
            val payload = ByteArray(8)
            IpPacket.writeU16(payload, 0, 5353)
            IpPacket.writeU16(payload, 2, 9999)
            IpPacket.writeU16(payload, 4, 8)
            val packet = IpPacket.buildUdp(
                requireNotNull(IpPacket.ipv4ToInt("10.66.0.2")), 5353,
                requireNotNull(IpPacket.ipv4ToInt("10.66.0.1")), 9999,
                payload,
            )
            relay.submit(packet, packet.size)
            assertEquals(0, relay.dnsQueryCount)
        } finally {
            relay.close()
        }
    }
}
