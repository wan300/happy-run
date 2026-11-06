package com.lepao.companion.capture

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * End-to-end coverage of the local proxy over real loopback sockets.
 *
 * The CONNECT test drives the same path the official application uses — plain CONNECT, real TLS
 * termination against a throwaway certificate, plain HTTP inside the tunnel, OkHttp forwarding and
 * header normalisation — so a pump deadlock or a lost `Content-Encoding` shows up here rather than
 * only on a phone.
 */
class LocalMitmProxyTest {

    private val proxies = mutableListOf<LocalMitmProxy>()
    private val servers = mutableListOf<MockWebServer>()
    private val openSockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        for (socket in openSockets) {
            try {
                socket.close()
            } catch (_: Exception) {
                // Ignore.
            }
        }
        for (proxy in proxies) proxy.stop()
        for (server in servers) {
            try {
                server.shutdown()
            } catch (_: Exception) {
                // Ignore.
            }
        }
    }

    @Test
    fun connectTlsTerminatesAndForwardsKeepAliveRequests() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(gzip("{\"status\":1}")))
                .addHeader("Content-Encoding", "gzip")
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "PHPSESSID=fresh; Path=/; Domain=api2.lptiyu.com"),
        )
        server.enqueue(
            MockResponse()
                .setBody("{\"status\":2}")
                .addHeader("Content-Type", "application/json"),
        )
        server.start()
        servers.add(server)

        val transactions = CopyOnWriteArrayList<HttpTransaction>()
        val proxy = LocalMitmProxy(
            sslContext = serverSslContext(),
            onTransaction = { transactions.add(it) },
            targetHost = "127.0.0.1",
            targetBase = "http://127.0.0.1:${server.port}",
        )
        proxies.add(proxy)
        val proxyPort = proxy.start()

        val raw = Socket()
        openSockets.add(raw)
        raw.soTimeout = 15_000
        raw.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), proxyPort), 5_000)
        raw.getOutputStream().write(
            "CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        raw.getOutputStream().flush()
        val connectReply = String(
            requireNotNull(HttpWire.readHead(raw.getInputStream())),
            StandardCharsets.ISO_8859_1,
        )
        assertTrue("proxy must acknowledge the tunnel: $connectReply", connectReply.startsWith("HTTP/1.1 200"))

        val tls = clientSslContext().socketFactory
            .createSocket(raw, "127.0.0.1", 443, true) as SSLSocket
        openSockets.add(tls)
        tls.soTimeout = 15_000
        tls.startHandshake()

        writeRequest(
            tls,
            "POST /v3/api.php/Run2/beforeRunV260 HTTP/1.1\r\n" +
                "Host: api2.lptiyu.com\r\n" +
                "User-Agent: okhttp/3.12.13\r\n" +
                "Accept-Encoding: br\r\n" +
                "Cookie: PHPSESSID=before\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 7\r\n\r\nkey=abc",
        )
        val first = readResponse(tls.getInputStream())
        assertEquals(200, first.status)
        assertNull("gzip must not reach the application", first.header("Content-Encoding"))
        assertEquals("{\"status\":1}", first.bodyText)
        assertEquals(first.bodyBytes.size.toString(), first.header("Content-Length"))
        assertEquals("Set-Cookie survives verbatim", "fresh", first.setCookieValue())

        Thread.sleep(1_300) // Regression: the old one-second idle timeout killed this connection.
        writeRequest(
            tls,
            "POST /v3/api.php/User/User HTTP/1.1\r\n" +
                "Host: api2.lptiyu.com\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 3\r\n\r\nx=1",
        )
        val second = readResponse(tls.getInputStream())
        assertEquals(200, second.status)
        assertEquals("{\"status\":2}", second.bodyText)

        val captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (transactions.size < 2 && System.nanoTime() < captureDeadline) Thread.sleep(5)
        assertEquals("both keep-alive requests were forwarded", 2, transactions.size)
        assertEquals(listOf("Run2/beforeRunV260", "User/User"), transactions.map { it.endpoint })
        assertEquals("okhttp/3.12.13", headerValue(transactions[0].requestHeaders, "User-Agent"))
        assertEquals("br", headerValue(transactions[0].requestHeaders, "Accept-Encoding"))
        assertTrue(
            "captured response headers keep Set-Cookie",
            transactions[0].responseHeaders.any { it.name.equals("Set-Cookie", true) && it.value.contains("PHPSESSID=fresh") },
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("the app's Accept-Encoding is replaced by OkHttp's own", "gzip", recorded?.getHeader("Accept-Encoding"))
        assertEquals("PHPSESSID=before", recorded?.getHeader("Cookie"))
        assertEquals("/v3/api.php/Run2/beforeRunV260", recorded?.path)
        assertEquals("POST", recorded?.method)
        assertEquals("key=abc", recorded?.body?.readUtf8())
        val secondRecorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/v3/api.php/User/User", secondRecorded?.path)
        assertEquals("x=1", secondRecorded?.body?.readUtf8())
    }

    @Test
    fun unreachableNonTargetConnectReturns502() {
        val proxy = LocalMitmProxy(
            sslContext = serverSslContext(),
            onTransaction = { },
        )
        proxies.add(proxy)
        val proxyPort = proxy.start()

        val socket = Socket()
        openSockets.add(socket)
        socket.soTimeout = 10_000
        socket.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), proxyPort), 5_000)
        socket.getOutputStream().write(
            "CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        socket.getOutputStream().flush()
        val reply = String(requireNotNull(HttpWire.readHead(socket.getInputStream())), StandardCharsets.ISO_8859_1)
        assertTrue("unreachable upstream must fail fast instead of pinning threads: $reply", reply.startsWith("HTTP/1.1 502"))
    }

    @Test
    fun chunkedRequestsAreRejectedBeforeForwarding() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"status\":1}").addHeader("Content-Type", "application/json"))
        server.start()
        servers.add(server)

        val transactions = CopyOnWriteArrayList<HttpTransaction>()
        val proxy = LocalMitmProxy(
            sslContext = serverSslContext(),
            onTransaction = { transactions.add(it) },
            targetHost = "127.0.0.1",
            targetBase = "http://127.0.0.1:${server.port}",
        )
        proxies.add(proxy)
        val proxyPort = proxy.start()

        val socket = Socket()
        openSockets.add(socket)
        socket.soTimeout = 10_000
        socket.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), proxyPort), 5_000)
        val chunkedHead = "POST /v3/api.php/Login/RefreshToken HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$proxyPort\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"
        socket.getOutputStream().write(chunkedHead.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
        val response = String(requireNotNull(HttpWire.readHead(socket.getInputStream())), StandardCharsets.ISO_8859_1)
        assertTrue("chunked request bodies are rejected instead of mis-framed", response.startsWith("HTTP/1.1 400"))
        assertTrue("rejection does not reach the upstream", transactions.isEmpty())
    }

    @Test(timeout = 15_000)
    fun unrelatedTunnelsDoNotBlockApiAndStopClosesThem() {
        val upstream = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        upstream.soTimeout = 3_000
        val api = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() }
        servers.add(api)
        val proxy = LocalMitmProxy(serverSslContext(), {}, targetBase = api.url("/").toString().trimEnd('/'))
        proxies.add(proxy)
        proxy.start()
        upstream.use {
            val tunnels = (1..24).map {
                val socket = connectTunnel(proxy.port, upstream.localPort)
                val peer = upstream.accept()
                openSockets.add(peer)
                socket
            }
            // The configured tunnel cap is reachable; the 25th is rejected without starving the API.
            val excess = Socket("127.0.0.1", proxy.port).also { openSockets.add(it); it.soTimeout = 3_000 }
            writeRequest(excess, "CONNECT 127.0.0.1:${upstream.localPort} HTTP/1.1\r\n\r\n")
            assertEquals(503, readResponse(excess.getInputStream()).status)
            val apiSocket = Socket("127.0.0.1", proxy.port).also { openSockets.add(it); it.soTimeout = 3_000 }
            writeRequest(apiSocket, "GET http://api2.lptiyu.com/probe HTTP/1.1\r\nHost: api2.lptiyu.com\r\n\r\n")
            assertEquals("ok", readResponse(apiSocket.getInputStream()).bodyText)
            proxy.stop()
            tunnels.forEach { assertEquals(-1, it.getInputStream().read()) }
        }
    }

    @Test(timeout = 10_000)
    fun relayPreservesBytesAndHalfClose() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { upstream ->
            val proxy = LocalMitmProxy(serverSslContext(), {})
            proxies.add(proxy)
            proxy.start()
            val client = connectTunnel(proxy.port, upstream.localPort)
            upstream.accept().use { peer ->
                peer.soTimeout = 3_000
                val request = ByteArray(70_000) { (it % 251).toByte() }
                client.getOutputStream().write(request)
                client.shutdownOutput()
                org.junit.Assert.assertArrayEquals(request, peer.getInputStream().readBytes())
                val response = ByteArray(90_000) { (it % 239).toByte() }
                peer.getOutputStream().write(response)
                peer.shutdownOutput()
                org.junit.Assert.assertArrayEquals(response, client.getInputStream().readBytes())
            }
        }
    }

    @Test(timeout = 10_000)
    fun nonTargetPlainHttpIsForwardedWithoutCaptureOrProxyCredentials() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("cdn")); start() }
        servers.add(server)
        val transactions = CopyOnWriteArrayList<HttpTransaction>()
        val proxy = LocalMitmProxy(serverSslContext(), { transactions.add(it) })
        proxies.add(proxy)
        proxy.start()
        val socket = Socket("127.0.0.1", proxy.port).also { openSockets.add(it); it.soTimeout = 3_000 }
        writeRequest(socket, "GET ${server.url("/asset?q=1")} HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nProxy-Authorization: secret\r\n\r\n")
        assertEquals("cdn", readResponse(socket.getInputStream()).bodyText)
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/asset?q=1", request.path)
        assertNull(request.getHeader("Proxy-Authorization"))
        assertTrue(transactions.isEmpty())
    }

    @Test(timeout = 15_000)
    fun fragmentedTlsHandshakeAndApplicationRecordsMakeProgress() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("fragmented-ok")); start() }
        servers.add(server)
        val proxy = LocalMitmProxy(serverSslContext(), {}, targetHost = "127.0.0.1", targetBase = server.url("/").toString().trimEnd('/'))
        proxies.add(proxy)
        proxy.start()
        val raw = connectTunnel(proxy.port, 443)
        val fragmenting = object : Socket() {
            override fun isConnected() = true
            override fun getInputStream() = raw.getInputStream()
            override fun getOutputStream(): java.io.OutputStream = object : java.io.OutputStream() {
                override fun write(value: Int) = raw.getOutputStream().write(value)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    var position = 0
                    while (position < length) {
                        val count = minOf(17, length - position)
                        raw.getOutputStream().write(bytes, offset + position, count)
                        raw.getOutputStream().flush()
                        position += count
                        Thread.sleep(2)
                    }
                }
            }
            override fun close() = raw.close()
        }
        val tls = clientSslContext().socketFactory.createSocket(fragmenting, "127.0.0.1", 443, true) as SSLSocket
        openSockets.add(tls)
        tls.startHandshake()
        writeRequest(tls, "GET /probe HTTP/1.1\r\nHost: api2.lptiyu.com\r\nConnection: close\r\n\r\n")
        assertEquals("fragmented-ok", readResponse(tls.getInputStream()).bodyText)
    }

    private fun connectTunnel(proxyPort: Int, targetPort: Int): Socket {
        val socket = Socket("127.0.0.1", proxyPort)
        openSockets.add(socket)
        socket.soTimeout = 4_000
        writeRequest(socket, "CONNECT 127.0.0.1:$targetPort HTTP/1.1\r\n\r\n")
        assertEquals(200, readResponse(socket.getInputStream()).status)
        return socket
    }

    private class Response(
        val status: Int,
        val headers: List<HttpWire.Header>,
        val bodyBytes: ByteArray,
    ) {
        val bodyText: String get() = String(bodyBytes, StandardCharsets.UTF_8)

        fun header(name: String): String? =
            headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

        fun setCookieValue(): String? = header("Set-Cookie")?.split(';')?.firstOrNull()?.substringAfter('=')
    }

    private fun writeRequest(socket: Socket, text: String) {
        socket.getOutputStream().write(text.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }

    private fun readResponse(input: InputStream): Response {
        val head = requireNotNull(HttpWire.readHead(input)) { "no response head" }
        val lines = String(head, StandardCharsets.ISO_8859_1).split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = lines.drop(1).filter { it.contains(':') }.map {
            HttpWire.Header(it.substringBefore(':').trim(), it.substringAfter(':').trim())
        }
        val length = headers.firstOrNull { it.name.equals("Content-Length", true) }?.value?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = input.read(body, read, length - read)
            if (chunk < 0) break
            read += chunk
        }
        return Response(status, headers, body)
    }

    private fun headerValue(headers: List<HttpWire.Header>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    private fun gzip(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        return output.toByteArray()
    }

    private fun serverSslContext(): SSLContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=api2.lptiyu.com")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 86_400_000L * 3650),
            name,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, "api2.lptiyu.com")),
        )
        val certificate = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
        )
        return CertAuthority(keyPair.private, arrayOf(certificate)).serverContext()
    }

    private fun clientSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
    }
}
