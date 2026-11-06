package com.lepao.companion.capture

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ConcurrentHashMap
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

/**
 * The `127.0.0.1` HTTP proxy the VPN exposes to the officially allowed application.
 *
 * Only the target API is terminated and inspected. Other HTTPS and HTTP destinations are
 * forwarded by a bounded selector relay, so idle SDK/CDN connections never pin MITM workers.
 * Parsing lives in [HttpWire], extraction in [CaptureExtractor]; this class only moves bytes and
 * normalises headers.
 */
class LocalMitmProxy(
    private val sslContext: SSLContext,
    private val onTransaction: (HttpTransaction) -> Unit,
    private val onDiag: (String) -> Unit = { System.err.println("proxy-diag: $it") },
    private val targetHost: String = DEFAULT_TARGET_HOST,
    private val targetBase: String = "https://$DEFAULT_TARGET_HOST",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val protectSocket: (Socket) -> Boolean = { true },
) {

    private val connectionCounter = AtomicInteger()
    private val requestCounter = AtomicInteger()
    private val executor: ExecutorService = ThreadPoolExecutor(
        MAX_WORKERS,
        MAX_WORKERS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { task ->
            Thread(
                null,
                task,
                "capture-proxy-${connectionCounter.incrementAndGet()}",
                WORKER_STACK_BYTES,
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var acceptedConnections: Int = 0
        private set

    @Volatile
    var forwardedRequests: Int = 0
        private set

    @Volatile
    var forwardErrors: Int = 0
        private set

    /** Connections that ended with an I/O error rather than a clean close (diagnostic only). */
    @Volatile
    var ioFailures: Int = 0
        private set

    /** CONNECT 隧道透传数量（非目标域名走真实网络，不再返回 502）。 */
    @Volatile
    var relayedTunnels: Int = 0
        private set

    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val relay = TunnelRelay(protectSocket, onDiag)

    /** Binds `127.0.0.1:0` and returns the chosen port. */
    fun start(): Int {
        if (running && port > 0) return port
        val socket = ServerSocketChannel.open().socket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 64)
        serverSocket = socket
        port = socket.localPort
        running = true
        val acceptThread = Thread({ acceptLoop(socket) }, "capture-proxy-accept")
        acceptThread.isDaemon = true
        acceptThread.start()
        return port
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Already closed.
        }
        serverSocket = null
        for (socket in activeSockets) closeQuietly(socket)
        executor.shutdownNow()
        relay.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val clientSocket = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            acceptedConnections += 1
            activeSockets.add(clientSocket)
            try {
                executor.execute { handleConnection(clientSocket) }
            } catch (_: Exception) {
                onDiag("proxy capacity reached active=${activeSockets.size}")
                activeSockets.remove(clientSocket)
                closeQuietly(clientSocket)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var handedOff = false
        try {
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val head = HttpWire.readHead(socket.getInputStream()) ?: return
            val request = HttpWire.parseRequestHead(head)
            if (request == null) {
                writeErrorResponse(socket.getOutputStream(), 400)
                return
            }
            if (request.method.equals("CONNECT", ignoreCase = true)) {
                handedOff = handleConnect(socket, request)
            } else {
                handedOff = handlePlainRequest(socket, request)
            }
        } catch (error: IOException) {
            synchronized(this) { ioFailures += 1 }
            onDiag("proxy I/O failed: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            synchronized(this) { ioFailures += 1 }
            onDiag("proxy connection failed: ${error.javaClass.simpleName}")
        } finally {
            activeSockets.remove(socket)
            if (!handedOff) closeQuietly(socket)
        }
    }

    private fun handleConnect(socket: Socket, request: HttpWire.RequestHead): Boolean {
        val started = System.nanoTime()
        val authority = HttpWire.parseAuthority(request.target)
        if (authority == null || authority.port !in 1..65535) {
            writeErrorResponse(socket.getOutputStream(), 400)
            return false
        }
        if (!authority.host.equals(targetHost, ignoreCase = true) || authority.port != HTTPS_PORT) {
            return relay.open(socket, authority).also { if (it) synchronized(this) { relayedTunnels += 1 } }
        }
        val output = socket.getOutputStream()
        output.write(CONNECT_ESTABLISHED)
        output.flush()
        onDiag("proxy connect connection=${socket.port} target=true connect_ms=${elapsedMs(started)}")
        terminateAndServe(socket)
        return false
    }

    private fun terminateAndServe(socket: Socket) {
        val handshakeStarted = System.nanoTime()
        val tunnel = try {
            TlsTerminator(sslContext).open(socket)
        } catch (error: Exception) {
            onDiag("proxy tls connection=${socket.port} status=failed tls_ms=${elapsedMs(handshakeStarted)} error=${error.javaClass.simpleName}")
            throw error
        }
        onDiag("proxy tls connection=${socket.port} status=ok tls_ms=${elapsedMs(handshakeStarted)}")
        try {
            val input = PushbackInputStream(BufferedInputStream(tunnel.input, BUFFER_BYTES), 1)
            val output = BufferedOutputStream(tunnel.output, BUFFER_BYTES)
            while (running) {
                val first = input.read()
                if (first < 0) break
                input.unread(first)
                // The idle timeout applies only between requests, never to a request body.
                tunnel.setReadTimeout(CLIENT_READ_TIMEOUT_MS)
                val requestStarted = System.nanoTime()
                val head = HttpWire.readHead(input) ?: break
                val request = HttpWire.parseRequestHead(head)
                if (request == null) {
                    writeErrorResponse(output, 400)
                    break
                }
                if (request.isChunked()) {
                    onDiag("chunked request body is not supported (${CaptureExtractor.endpointOf(request.target)})")
                    writeErrorResponse(output, 400)
                    break
                }
                val body = readBody(input, request)
                if (body == null) {
                    writeErrorResponse(output, 400)
                    break
                }
                val path = normalisePath(request.target)
                val result = forward(request, path, body)
                if (result == null) {
                    writeErrorResponse(output, 502)
                    break
                }
                writeForwardedResponse(output, result, includeBody = !request.method.equals("HEAD", true))
                output.flush()
                synchronized(this) { forwardedRequests += 1 }
                onDiag("proxy request id=${result.id} connection=${socket.port} status=${result.code} total_ms=${elapsedMs(requestStarted)} bytes=${result.body.size}")
                notifyTransaction(request, path, body, result)
                if (request.wantsConnectionClose() || result.connectionClose) break
                // Unrelated tunnels use the selector and do not consume these bounded workers.
                tunnel.setReadTimeout(KEEP_ALIVE_IDLE_MS)
            }
        } catch (_: IOException) {
            // The application closed the tunnel; the transaction already saw its response.
        } catch (error: Exception) {
            onDiag("mitm connection failed: ${error.javaClass.simpleName}")
        } finally {
            tunnel.close()
        }
    }

    private fun handlePlainRequest(socket: Socket, request: HttpWire.RequestHead): Boolean {
        val absolute = HttpWire.parseAbsoluteTarget(request.target)
        val host = absolute?.authority?.host
            ?: request.header("Host")?.let { HttpWire.parseAuthority(it, 80)?.host }
        if (host != null && host.equals(targetHost, ignoreCase = true)) {
            if (request.isChunked()) {
                writeErrorResponse(socket.getOutputStream(), 400)
                return false
            }
            val body = readBody(socket.getInputStream(), request)
            if (body == null) {
                writeErrorResponse(socket.getOutputStream(), 400)
                return false
            }
            val path = absolute?.path ?: normalisePath(request.target)
            val result = forward(request, path, body)
            if (result == null) {
                writeErrorResponse(socket.getOutputStream(), 502)
                return false
            }
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES)
            writeForwardedResponse(output, result, includeBody = !request.method.equals("HEAD", true))
            output.flush()
            synchronized(this) { forwardedRequests += 1 }
            notifyTransaction(request, path, body, result)
            return false
        }
        if (absolute == null || absolute.secure || absolute.authority.port !in 1..65535) {
            writeErrorResponse(socket.getOutputStream(), 400)
            return false
        }
        // Use origin-form and close after the response: a plain proxy connection could otherwise
        // be reused for a different host. Body framing, including chunked uploads, stays untouched.
        val headers = request.headers.filterNot {
            it.name.lowercase() in setOf("proxy-connection", "proxy-authorization", "connection")
        } + HttpWire.Header("Connection", "close")
        return relay.open(socket, absolute.authority,
            HttpWire.buildRequestHead(request.method, absolute.path, request.version, headers))
    }

    private fun readBody(input: InputStream, request: HttpWire.RequestHead): ByteArray? {
        val length = request.contentLengthOrNull() ?: 0
        if (length < 0 || length > MAX_REQUEST_BODY) return null
        if (length == 0) return EMPTY
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = input.read(buffer, read, length - read)
            if (chunk < 0) return null
            read += chunk
        }
        return buffer
    }

    private fun forward(request: HttpWire.RequestHead, path: String, body: ByteArray): ForwardResult? {
        val builder = Request.Builder().url(targetBase + path)
        for (header in request.headers) {
            if (header.name.lowercase() in REQUEST_HEADER_DROP) continue
            builder.addHeader(header.name, header.value)
        }
        val method = request.method.uppercase()
        val requestBody = if (method == "GET" || method == "HEAD") {
            null
        } else {
            body.toRequestBody(null)
        }
        builder.method(method, requestBody)
        val started = System.nanoTime()
        val id = requestCounter.incrementAndGet()
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val headersMs = elapsedMs(started)
                val result = toResult(response, id)
                onDiag("proxy upstream id=$id status=${response.code} headers_ms=$headersMs upstream_ms=${elapsedMs(started)} protocol=${response.protocol}")
                if (result == null) {
                    synchronized(this) { forwardErrors += 1 }
                    onDiag("proxy response exceeds buffer limit")
                }
                result
            }
        } catch (error: Exception) {
            synchronized(this) { forwardErrors += 1 }
            onDiag("forward failed id=$id upstream_ms=${elapsedMs(started)} (${CaptureExtractor.endpointOf(path)}): ${error.javaClass.simpleName}")
            null
        }
    }

    private fun toResult(response: Response, id: Int): ForwardResult? {
        val bodyBytes = readLimited(response.body?.byteStream(), MAX_RESPONSE_BODY) ?: return null
        val headers = ArrayList<HttpWire.Header>(response.headers.size)
        for (name in response.headers.names()) {
            if (name.lowercase() in RESPONSE_HEADER_DROP) continue
            for (value in response.headers.values(name)) headers.add(HttpWire.Header(name, value))
        }
        val connectionClose = response.header("Connection")?.contains("close", ignoreCase = true) == true
        return ForwardResult(id, response.code, response.message, headers, bodyBytes, connectionClose)
    }

    private fun readLimited(input: InputStream?, limit: Int): ByteArray? {
        if (input == null) return EMPTY
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun writeForwardedResponse(output: OutputStream, result: ForwardResult, includeBody: Boolean) {
        val headers = ArrayList<HttpWire.Header>(result.headers.size + 2)
        headers.addAll(result.headers)
        headers.add(HttpWire.Header("Content-Length", result.body.size.toString()))
        if (result.connectionClose) headers.add(HttpWire.Header("Connection", "close"))
        val body = if (includeBody) result.body else EMPTY
        val reason = result.message.trim().ifEmpty { HttpWire.reasonPhrase(result.code) }
        output.write(HttpWire.buildResponse(result.code, reason, headers, body))
    }

    private fun writeErrorResponse(output: OutputStream, status: Int) {
        val headers = listOf(
            HttpWire.Header("Content-Length", "0"),
            HttpWire.Header("Connection", "close"),
        )
        output.write(HttpWire.buildResponse(status, HttpWire.reasonPhrase(status), headers, EMPTY))
        output.flush()
    }

    private fun notifyTransaction(
        request: HttpWire.RequestHead,
        path: String,
        body: ByteArray,
        result: ForwardResult,
    ) {
        val endpoint = CaptureExtractor.endpointOf(path)
        val headers = ArrayList<HttpWire.Header>(result.headers.size)
        headers.addAll(result.headers)
        try {
            onTransaction(
                HttpTransaction(
                    endpoint = endpoint,
                    path = path,
                    method = request.method.uppercase(),
                    requestHeaders = request.headers,
                    requestBody = body,
                    statusCode = result.code,
                    responseHeaders = headers,
                    responseBody = result.body,
                    observedAt = clock(),
                ),
            )
        } catch (error: Exception) {
            onDiag("capture callback failed: ${error.javaClass.simpleName}")
        }
    }

    private fun normalisePath(target: String): String {
        val absolute = HttpWire.parseAbsoluteTarget(target)
        if (absolute != null) return absolute.path
        if (target.startsWith("/")) return target
        return "/$target"
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun closeQuietly(target: java.io.Closeable?) {
        if (target == null) return
        try {
            target.close()
        } catch (_: IOException) {
            // Ignore.
        }
    }

    private class ForwardResult(
        val id: Int,
        val code: Int,
        val message: String,
        val headers: List<HttpWire.Header>,
        val body: ByteArray,
        val connectionClose: Boolean,
    )

    companion object {
        const val DEFAULT_TARGET_HOST: String = "api2.lptiyu.com"

        private const val LOOPBACK = "127.0.0.1"
        private const val HTTPS_PORT = 443
        private const val BUFFER_BYTES = 16 * 1024
        private const val CLIENT_READ_TIMEOUT_MS = 30_000
        private const val KEEP_ALIVE_IDLE_MS = 15_000
        private const val MAX_WORKERS = 16
        private const val WORKER_STACK_BYTES = 512L * 1024L
        private const val MAX_REQUEST_BODY = 2 * 1024 * 1024
        private const val MAX_RESPONSE_BODY = 8 * 1024 * 1024

        private val EMPTY = ByteArray(0)
        private val CONNECT_ESTABLISHED =
            "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)

        /** Headers the app sends that belong to the proxy hop or that OkHttp must regenerate. */
        private val REQUEST_HEADER_DROP = setOf(
            "host", "connection", "proxy-connection", "content-length", "transfer-encoding",
            "accept-encoding", "te", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate",
            "expect",
        )

        /** Hop-by-hop headers plus encoding headers whose framing no longer applies to the body. */
        private val RESPONSE_HEADER_DROP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
            "trailer", "transfer-encoding", "upgrade", "content-length", "content-encoding",
        )
    }
}
