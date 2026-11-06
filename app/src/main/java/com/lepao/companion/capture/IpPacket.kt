package com.lepao.companion.capture

/**
 * Minimal IPv4/UDP/TCP framing used by the TUN reader.
 *
 * The TUN interface only carries traffic of the officially allowed application, and the proxy is
 * reached over loopback (so it never appears here). The only packets this layer has to understand
 * are DNS queries (UDP/53), plus TCP SYN counting to diagnose "the app bypassed the proxy".
 */
object IpPacket {

    const val PROTOCOL_TCP: Int = 6
    const val PROTOCOL_UDP: Int = 17

    const val IPV4_HEADER_SIZE: Int = 20
    const val UDP_HEADER_SIZE: Int = 8
    const val TCP_HEADER_SIZE: Int = 20

    private const val TCP_FLAG_SYN: Int = 0x02
    private const val TCP_FLAG_ACK: Int = 0x10

    class Parsed(
        val protocol: Int,
        val sourceAddress: Int,
        val destinationAddress: Int,
        val payload: ByteArray,
    )

    fun parse(packet: ByteArray, length: Int = packet.size): Parsed? {
        if (length < IPV4_HEADER_SIZE) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER_SIZE || headerLength > length) return null
        val declaredLength = u16(packet, 2)
        val totalLength = if (declaredLength in headerLength..length) declaredLength else length
        if (totalLength <= headerLength) return null
        val protocol = packet[9].toInt() and 0xFF
        return Parsed(
            protocol = protocol,
            sourceAddress = u32(packet, 12),
            destinationAddress = u32(packet, 16),
            payload = packet.copyOfRange(headerLength, totalLength),
        )
    }

    fun udpSourcePort(payload: ByteArray): Int? =
        if (payload.size < UDP_HEADER_SIZE) null else u16(payload, 0)

    fun udpDestinationPort(payload: ByteArray): Int? =
        if (payload.size < UDP_HEADER_SIZE) null else u16(payload, 2)

    fun udpPayload(payload: ByteArray): ByteArray? {
        if (payload.size < UDP_HEADER_SIZE) return null
        val declared = u16(payload, 4)
        val end = if (declared in UDP_HEADER_SIZE..payload.size) declared else payload.size
        return payload.copyOfRange(UDP_HEADER_SIZE, end)
    }

    fun tcpDestinationPort(payload: ByteArray): Int? =
        if (payload.size < TCP_HEADER_SIZE) null else u16(payload, 2)

    /** True for a connection-opening SYN (SYN set, ACK clear). */
    fun isTcpSyn(payload: ByteArray): Boolean {
        if (payload.size < TCP_HEADER_SIZE) return false
        val flags = payload[13].toInt() and 0xFF
        return (flags and TCP_FLAG_SYN) != 0 && (flags and TCP_FLAG_ACK) == 0
    }

    fun buildUdp(
        sourceAddress: Int,
        sourcePort: Int,
        destinationAddress: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = IPV4_HEADER_SIZE + UDP_HEADER_SIZE + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, 0)
        writeU16(packet, 6, 0x4000) // Don't fragment.
        packet[8] = 64
        packet[9] = PROTOCOL_UDP.toByte()
        writeU32(packet, 12, sourceAddress)
        writeU32(packet, 16, destinationAddress)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER_SIZE))

        val udpLength = UDP_HEADER_SIZE + payload.size
        writeU16(packet, IPV4_HEADER_SIZE, sourcePort)
        writeU16(packet, IPV4_HEADER_SIZE + 2, destinationPort)
        writeU16(packet, IPV4_HEADER_SIZE + 4, udpLength)
        writeU16(packet, IPV4_HEADER_SIZE + 6, 0)
        payload.copyInto(packet, IPV4_HEADER_SIZE + UDP_HEADER_SIZE)

        val pseudo = pseudoHeaderSum(sourceAddress, destinationAddress, PROTOCOL_UDP, udpLength)
        val udpChecksum = checksum(packet, IPV4_HEADER_SIZE, udpLength, pseudo)
        writeU16(packet, IPV4_HEADER_SIZE + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    /** RFC 1071 ones-complement checksum; a correctly filled header verifies to 0. */
    fun checksum(data: ByteArray, offset: Int, length: Int, initialSum: Int = 0): Int {
        var sum = initialSum
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (data[index].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    fun pseudoHeaderSum(sourceAddress: Int, destinationAddress: Int, protocol: Int, length: Int): Int {
        var sum = 0
        sum += (sourceAddress ushr 16) and 0xFFFF
        sum += sourceAddress and 0xFFFF
        sum += (destinationAddress ushr 16) and 0xFFFF
        sum += destinationAddress and 0xFFFF
        sum += protocol and 0xFF
        sum += length and 0xFFFF
        return sum
    }

    fun ipv4ToInt(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    fun intToIpv4(value: Int): String = buildString(15) {
        append((value ushr 24) and 0xFF).append('.')
        append((value ushr 16) and 0xFF).append('.')
        append((value ushr 8) and 0xFF).append('.')
        append(value and 0xFF)
    }

    fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    fun u32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun writeU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
