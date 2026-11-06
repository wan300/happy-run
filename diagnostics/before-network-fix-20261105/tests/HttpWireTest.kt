package com.lepao.companion.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Wire-format coverage for the local proxy parser. The proxy must never mis-frame a request, because
 * a wrong Content-Length would cut a following keep-alive request in half.
 */
class HttpWireTest {

    private val connectHead =
        "CONNECT api2.lptiyu.com:443 HTTP/1.1\r\n" +
            "Host: api2.lptiyu.com:443\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "User-Agent: okhttp/3.12.13\r\n\r\n"

    @Test
    fun headReadStopsExactlyAtTheBlankLine() {
        val payload = connectHead.toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00)
        val input = ByteArrayInputStream(payload)

        val head = HttpWire.readHead(input)
        assertEquals(connectHead, String(requireNotNull(head), StandardCharsets.ISO_8859_1))
        assertEquals("TLS bytes after the head stay readable", 5, input.available())
        assertEquals(0x16, input.read())
    }

    @Test
    fun requestLineAndHeadersAreParsed() {
        val request = HttpWire.parseRequestHead(connectHead.toByteArray(StandardCharsets.ISO_8859_1))
        requireNotNull(request)
        assertEquals("CONNECT", request.method)
        assertEquals("api2.lptiyu.com:443", request.target)
        assertEquals("HTTP/1.1", request.version)
        assertEquals("api2.lptiyu.com:443", request.header("host"))
        assertEquals("keep-alive", request.header("PROXY-CONNECTION"))
        assertNull(request.header("x-missing"))
        assertFalse(request.wantsConnectionClose())
        assertFalse(request.isChunked())

        val authority = requireNotNull(HttpWire.parseAuthority(request.target))
        assertEquals("api2.lptiyu.com", authority.host)
        assertEquals(443, authority.port)

        assertEquals("api2.lptiyu.com", HttpWire.parseAuthority("api2.lptiyu.com")?.host)
        assertEquals(443, HttpWire.parseAuthority("api2.lptiyu.com")?.port)
        assertEquals(8080, HttpWire.parseAuthority("api2.lptiyu.com:8080")?.port)
        assertNull(HttpWire.parseAuthority(":443"))
    }

    @Test
    fun contentLengthAndConnectionDirectivesAreRead() {
        val body = "key=abc"
        val head = "POST /v3/api.php/Run2/beforeRunV260 HTTP/1.1\r\n" +
            "Host: api2.lptiyu.com\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n"
        val request = requireNotNull(HttpWire.parseRequestHead(head.toByteArray(StandardCharsets.ISO_8859_1)))
        assertEquals(body.length, request.contentLengthOrNull())
        assertTrue(request.wantsConnectionClose())
        assertEquals(1, request.headerValues("content-length").size)
    }

    @Test
    fun absoluteTargetsAreSplitIntoAuthorityAndPath() {
        val https = requireNotNull(HttpWire.parseAbsoluteTarget("https://api2.lptiyu.com/v3/api.php/User/User?x=1"))
        assertEquals("api2.lptiyu.com", https.authority.host)
        assertEquals(443, https.authority.port)
        assertEquals("/v3/api.php/User/User?x=1", https.path)
        assertTrue(https.secure)

        val plain = requireNotNull(HttpWire.parseAbsoluteTarget("http://example.com:8080/path"))
        assertEquals(8080, plain.authority.port)
        assertFalse(plain.secure)

        val rootOnly = requireNotNull(HttpWire.parseAbsoluteTarget("http://example.com"))
        assertEquals("/", rootOnly.path)
        assertNull(HttpWire.parseAbsoluteTarget("not a url"))
    }

    @Test
    fun responsesAreAssembledWithAnExactContentLength() {
        val body = "{\"status\":1}".toByteArray(StandardCharsets.UTF_8)
        val raw = HttpWire.buildResponse(
            200,
            "OK",
            listOf(
                HttpWire.Header("Content-Type", "application/json"),
                HttpWire.Header("Content-Length", body.size.toString()),
                HttpWire.Header("Set-Cookie", "PHPSESSID=abc; Path=/"),
            ),
            body,
        )
        val text = String(raw, StandardCharsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: ${body.size}\r\n"))
        assertTrue(text.contains("Set-Cookie: PHPSESSID=abc; Path=/\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"status\":1}"))

        val stream = ByteArrayInputStream(raw)
        val head = requireNotNull(HttpWire.readHead(stream))
        assertFalse("head must not contain the body", String(head, StandardCharsets.ISO_8859_1).contains("status"))
        val responseBody = stream.readBytes().toString(StandardCharsets.UTF_8)
        assertEquals("{\"status\":1}", responseBody)
    }

    @Test
    fun emptyBodyResponsesStillTerminate() {
        val raw = HttpWire.buildResponse(204, "", listOf(HttpWire.Header("Content-Length", "0")), ByteArray(0))
        val text = String(raw, StandardCharsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 204\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test(expected = IOException::class)
    fun oversizedHeadIsRejected() {
        val flood = ByteArrayOutputStream().apply {
            write("GET / HTTP/1.1\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            repeat(64) { write("X-Pad: ${"y".repeat(1024)}\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
            write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }.toByteArray()
        HttpWire.readHead(ByteArrayInputStream(flood), maxBytes = 4096)
    }
}
