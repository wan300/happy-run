package com.lepao.companion.capture

import java.nio.charset.StandardCharsets

/**
 * DNS message parsing for the TUN relay.
 *
 * The relay forwards the raw query to a real resolver and relays its raw answer, so only the
 * question section is parsed (for diagnostics and transaction-id sanity checks) and only failure
 * replies are constructed locally.
 */
object DnsPacket {

    const val RCODE_NO_ERROR: Int = 0
    const val RCODE_SERVFAIL: Int = 2

    private const val HEADER_SIZE = 12
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val ANSWER_TTL_SECONDS = 60

    class Query(
        val id: Int,
        val flags: Int,
        val name: String,
        val qtype: Int,
        val qclass: Int,
    )

    fun parseQuery(payload: ByteArray): Query? {
        if (payload.size < HEADER_SIZE) return null
        val questionCount = IpPacket.u16(payload, 4)
        if (questionCount < 1) return null
        val id = IpPacket.u16(payload, 0)
        val flags = IpPacket.u16(payload, 2)
        var offset = HEADER_SIZE
        val labels = ArrayList<String>(4)
        while (true) {
            if (offset >= payload.size) return null
            val length = payload[offset].toInt() and 0xFF
            if (length == 0) {
                offset += 1
                break
            }
            if (length and 0xC0 != 0) return null // Compressed question names are not expected.
            if (offset + 1 + length > payload.size) return null
            labels.add(String(payload, offset + 1, length, StandardCharsets.US_ASCII))
            offset += 1 + length
        }
        if (offset + 4 > payload.size) return null
        return Query(
            id = id,
            flags = flags,
            name = if (labels.isEmpty()) "." else labels.joinToString("."),
            qtype = IpPacket.u16(payload, offset),
            qclass = IpPacket.u16(payload, offset + 2),
        )
    }

    /** Offset just past the question section, or -1 when the message is not a usable query. */
    fun questionEnd(payload: ByteArray): Int {
        if (payload.size < HEADER_SIZE) return -1
        if (IpPacket.u16(payload, 4) < 1) return -1
        var offset = HEADER_SIZE
        while (true) {
            if (offset >= payload.size) return -1
            val length = payload[offset].toInt() and 0xFF
            if (length == 0) return if (offset + 5 <= payload.size) offset + 5 else -1
            if (length and 0xC0 != 0) return -1
            if (offset + 1 + length > payload.size) return -1
            offset += 1 + length
        }
    }

    /**
     * Builds a reply for [query] with the given response code and optional A records. RD is echoed,
     * RA and QR are set, and the question section is copied verbatim.
     */
    fun buildResponse(query: ByteArray, rcode: Int = RCODE_NO_ERROR, answers: List<Int> = emptyList()): ByteArray {
        val questionBytes = questionEnd(query)
        if (questionBytes < 0 || query.size < HEADER_SIZE) {
            val fallback = ByteArray(HEADER_SIZE)
            if (query.size >= 2) fallback[0] = query[0]
            if (query.size >= 2) fallback[1] = query[1]
            IpPacket.writeU16(fallback, 2, 0x8080 or (rcode and 0x0F))
            return fallback
        }
        val queryFlags = IpPacket.u16(query, 2)
        val flags = 0x8000 or (queryFlags and 0x0100) or 0x0080 or (rcode and 0x0F)
        val payload = ByteArray(questionBytes + answers.size * 16)
        IpPacket.writeU16(payload, 0, IpPacket.u16(query, 0))
        IpPacket.writeU16(payload, 2, flags)
        IpPacket.writeU16(payload, 4, 1)
        IpPacket.writeU16(payload, 6, answers.size)
        IpPacket.writeU16(payload, 8, 0)
        IpPacket.writeU16(payload, 10, 0)
        query.copyInto(payload, HEADER_SIZE, HEADER_SIZE, questionBytes)
        var offset = questionBytes
        for (address in answers) {
            IpPacket.writeU16(payload, offset, 0xC00C) // Pointer to the question name.
            IpPacket.writeU16(payload, offset + 2, TYPE_A)
            IpPacket.writeU16(payload, offset + 4, CLASS_IN)
            IpPacket.writeU32(payload, offset + 6, ANSWER_TTL_SECONDS)
            IpPacket.writeU16(payload, offset + 10, 4)
            IpPacket.writeU32(payload, offset + 12, address)
            offset += 16
        }
        return payload
    }

    fun buildErrorResponse(query: ByteArray, rcode: Int = RCODE_SERVFAIL): ByteArray =
        buildResponse(query, rcode, emptyList())
}
