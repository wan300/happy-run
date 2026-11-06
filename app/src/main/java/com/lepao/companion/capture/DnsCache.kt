package com.lepao.companion.capture

import java.util.Base64

/** Small positive-answer cache; preserves wire data and ages every resource-record TTL. */
internal class DnsCache(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val answer: ByteArray, val inserted: Long, val lifetime: Long, val ttls: List<Int>)
    private val entries = LinkedHashMap<String, Entry>(128, 0.75f, true)

    @Synchronized
    fun get(query: ByteArray): ByteArray? {
        val key = key(query) ?: return null
        val entry = entries[key] ?: return null
        val age = (nowMillis() - entry.inserted).coerceAtLeast(0) / 1_000
        if (age >= entry.lifetime) { entries.remove(key); return null }
        return entry.answer.copyOf().apply {
            this[0] = query[0]
            this[1] = query[1]
            for (offset in entry.ttls) IpPacket.writeU32(this, offset, (u32(this, offset) - age).coerceAtLeast(0).toInt())
        }
    }

    @Synchronized
    fun put(query: ByteArray, answer: ByteArray) {
        val key = key(query) ?: return
        if (!matches(query, answer) || IpPacket.u16(answer, 2) and 0x020F != 0 || IpPacket.u16(answer, 6) == 0) return
        var offset = DnsPacket.questionEnd(answer)
        val ttls = ArrayList<Int>()
        var lifetime = 60L
        val records = IpPacket.u16(answer, 6) + IpPacket.u16(answer, 8) + IpPacket.u16(answer, 10)
        repeat(records) {
            offset = skipName(answer, offset)
            if (offset < 0 || offset + 10 > answer.size) return
            val type = IpPacket.u16(answer, offset)
            if (type != 41) { // OPT uses this field for EDNS flags, not a TTL.
                if (type !in setOf(1, 5, 28)) return
                ttls.add(offset + 4)
                lifetime = minOf(lifetime, u32(answer, offset + 4))
            }
            offset += 10 + IpPacket.u16(answer, offset + 8)
            if (offset > answer.size) return
        }
        if (lifetime <= 0 || ttls.isEmpty() || offset != answer.size) return
        entries[key] = Entry(answer.copyOf(), nowMillis(), lifetime, ttls)
        while (entries.size > 128) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() = entries.clear()

    private fun key(query: ByteArray): String? {
        if (query.size < 12 || IpPacket.u16(query, 4) != 1 || IpPacket.u16(query, 2) and 0xF800 != 0) return null
        return Base64.getEncoder().encodeToString(query.copyOfRange(2, query.size))
    }

    companion object {
        fun matches(query: ByteArray, answer: ByteArray): Boolean {
            if (query.size < 12 || answer.size < 12 || IpPacket.u16(answer, 2) and 0x8000 == 0 ||
                IpPacket.u16(query, 0) != IpPacket.u16(answer, 0) || IpPacket.u16(answer, 4) != 1 ||
                IpPacket.u16(query, 2) and 0x7800 != IpPacket.u16(answer, 2) and 0x7800) return false
            val end = DnsPacket.questionEnd(query)
            if (end < 0 || DnsPacket.questionEnd(answer) != end) return false
            return (12 until end).all { query[it] == answer[it] }
        }

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((IpPacket.u16(bytes, offset).toLong() shl 16) or IpPacket.u16(bytes, offset + 2).toLong())

        private fun skipName(bytes: ByteArray, start: Int): Int {
            var offset = start
            while (offset < bytes.size) {
                val length = bytes[offset].toInt() and 255
                if (length == 0) return offset + 1
                if (length and 0xC0 == 0xC0) return if (offset + 1 < bytes.size) offset + 2 else -1
                if (length > 63) return -1
                offset += length + 1
            }
            return -1
        }
    }
}
