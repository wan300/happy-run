package com.lepao.companion.capture

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * HTTP/1.1 head parsing and response assembly for [LocalMitmProxy].
 *
 * Deliberately free of Android types so the wire format can be covered by plain JVM unit tests.
 * Nothing here inspects bodies or credentials; it only splits the protocol framing.
 */
object HttpWire {

    const val MAX_HEAD_BYTES: Int = 64 * 1024

    private const val CR: Byte = 13
    private const val LF: Byte = 10

    data class Header(val name: String, val value: String)

    data class RequestHead(
        val method: String,
        val target: String,
        val version: String,
        val headers: List<Header>,
    ) {
        fun header(name: String): String? =
            headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

        fun headerValues(name: String): List<String> =
            headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

        fun wantsConnectionClose(): Boolean {
            val value = header("Connection")?.lowercase()
                ?: return version.equals("HTTP/1.0", ignoreCase = true)
            return value.contains("close")
        }

        fun isChunked(): Boolean =
            header("Transfer-Encoding")?.lowercase()?.contains("chunked") == true

        fun contentLengthOrNull(): Int? = header("Content-Length")?.trim()?.toIntOrNull()
    }

    data class Authority(val host: String, val port: Int)

    data class AbsoluteTarget(val authority: Authority, val path: String, val secure: Boolean)

    /**
     * Reads exactly one HTTP head, terminated by an empty line.
     *
     * Bytes are consumed one at a time on purpose: after a CONNECT the very next bytes are a TLS
     * ClientHello, so a buffered reader would swallow record bytes that the TLS pumps still need.
     */
    fun readHead(input: InputStream, maxBytes: Int = MAX_HEAD_BYTES): ByteArray? {
        val buffer = ByteArray(maxBytes)
        var count = 0
        while (count < maxBytes) {
            val value = input.read()
            if (value < 0) return if (count == 0) null else buffer.copyOf(count)
            buffer[count++] = value.toByte()
            if (count >= 4 &&
                buffer[count - 4] == CR && buffer[count - 3] == LF &&
                buffer[count - 2] == CR && buffer[count - 1] == LF
            ) {
                return buffer.copyOf(count)
            }
        }
        throw IOException("HTTP head exceeds $maxBytes bytes")
    }

    fun parseRequestHead(raw: ByteArray): RequestHead? {
        val text = String(raw, StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines.first().split(' ')
        if (parts.size < 3) return null
        val method = parts[0].trim()
        val target = parts[1].trim()
        val version = parts[2].trim()
        if (method.isEmpty() || target.isEmpty() || !version.startsWith("HTTP/")) return null
        val headers = ArrayList<Header>(lines.size)
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers.add(Header(line.substring(0, separator).trim(), line.substring(separator + 1).trim()))
        }
        return RequestHead(method, target, version, headers)
    }

    /** `host:port` as used on a CONNECT request line; a bare host keeps [defaultPort]. */
    fun parseAuthority(value: String, defaultPort: Int = 443): Authority? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close <= 1) return null
            val host = trimmed.substring(1, close)
            val rest = trimmed.substring(close + 1)
            if (!rest.startsWith(":")) return Authority(host, defaultPort)
            return Authority(host, rest.substring(1).toIntOrNull() ?: defaultPort)
        }
        val separator = trimmed.lastIndexOf(':')
        if (separator < 0) return Authority(trimmed, defaultPort)
        val host = trimmed.substring(0, separator)
        if (host.isEmpty()) return null
        return Authority(host, trimmed.substring(separator + 1).toIntOrNull() ?: defaultPort)
    }

    /** Parses an absolute request target such as `http://host:80/path?q=1`. */
    fun parseAbsoluteTarget(target: String): AbsoluteTarget? {
        val schemeEnd = target.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = target.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = target.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authorityText = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        val authority = parseAuthority(authorityText, if (scheme == "https") 443 else 80) ?: return null
        if (authority.host.isEmpty()) return null
        val tail = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
        val path = when {
            tail.isEmpty() -> "/"
            tail.startsWith("/") -> tail
            else -> "/$tail"
        }
        return AbsoluteTarget(authority, path, scheme == "https")
    }

    fun buildRequestHead(
        method: String,
        target: String,
        version: String,
        headers: List<Header>,
    ): ByteArray {
        val builder = StringBuilder(128)
        builder.append(method).append(' ').append(target).append(' ').append(version).append("\r\n")
        for (header in headers) {
            builder.append(header.name).append(": ").append(header.value).append("\r\n")
        }
        builder.append("\r\n")
        return builder.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }

    fun buildResponse(
        status: Int,
        reason: String,
        headers: List<Header>,
        body: ByteArray,
    ): ByteArray {
        val builder = StringBuilder(128)
        builder.append("HTTP/1.1 ").append(status)
        val phrase = reason.trim()
        if (phrase.isNotEmpty()) builder.append(' ').append(phrase)
        builder.append("\r\n")
        for (header in headers) {
            builder.append(header.name).append(": ").append(header.value).append("\r\n")
        }
        builder.append("\r\n")
        val head = builder.toString().toByteArray(StandardCharsets.ISO_8859_1)
        if (body.isEmpty()) return head
        val result = ByteArray(head.size + body.size)
        head.copyInto(result, 0)
        body.copyInto(result, head.size)
        return result
    }

    fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }
}
