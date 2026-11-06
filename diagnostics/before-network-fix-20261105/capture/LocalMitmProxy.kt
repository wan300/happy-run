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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

/**
 * The `127.0.0.1` HTTP proxy the VPN exposes to the officially allowed application.
 *
 * Only `api2.lptiyu.com:443` is terminated and inspected. Every other CONNECT target is rejected
 * with 502: the official app's ad/tracker SDKs otherwise open dozens of long-lived sockets, and the
 * old byte-pipe fallback multiplied that into helper threads until ART aborted the process on the
 * test device. Login traffic needs only the API host; unrelated SDKs fail fast and harmlessly.
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
) {

    private val connectionCounter = AtomicInteger()
    private val executor: ExecutorService = ThreadPoolExecutor(
        MIN_WORKERS,
        MAX_WORKERS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
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

    private val activeRelays = AtomicInteger()
    private val relayCounter = AtomicInteger()

    /** Binds `127.0.0.1:0` and returns the chosen port. */
    fun start(): Int {
        if (running && port > 0) return port
        val socket = ServerSocket()
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
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val clientSocket = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            acceptedConnections += 1
            try {
                executor.execute { handleConnection(clientSocket) }
            } catch (_: Exception) {
                closeQuietly(clientSocket)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
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
                handleConnect(socket, request)
            } else {
                handlePlainRequest(socket, request)
            }
        } catch (_: IOException) {
            ioFailures += 1
        } catch (error: Exception) {
            ioFailures += 1
            onDiag("proxy connection failed: ${error.javaClass.simpleName}")
        } finally {
            closeQuietly(socket)
        }
    }

    private fun handleConnect(socket: Socket, request: HttpWire.RequestHead) {
        val authority = HttpWire.parseAuthority(request.target)
        if (authority == null) {
            writeErrorResponse(socket.getOutputStream(), 400)
            return
        }
        if (!authority.host.equals(targetHost, ignoreCase = true) || authority.port != HTTPS_PORT) {
            // 非目标域名不再返回 502：直接透传，否则官方应用的 CDN/其它接口全部失败，
            // 用户看到的是“开了采集就上不了网”。
            relayTunnel(socket, authority)
            return
        }
        val output = socket.getOutputStream()
        output.write(CONNECT_ESTABLISHED)
        output.flush()
        terminateAndServe(socket)
    }

    /**
     * 非目标域名的 CONNECT 隧道：直接透传到真实服务端。
     *
     * 这类连接只做字节转发，不解析内容；连接数受 [MAX_RELAYS] 限制，超出预算就快速失败，
     * 不会像旧的字节泵实现那样无限堆线程把进程拖垮。
     */
    private fun relayTunnel(client: Socket, authority: HttpWire.Authority) {
        if (activeRelays.incrementAndGet() > MAX_RELAYS) {
            activeRelays.decrementAndGet()
            writeErrorResponse(client.getOutputStream(), 503)
            return
        }
        var upstream: Socket? = null
        var established = false
        try {
            val target = Socket()
            target.tcpNoDelay = true
            target.soTimeout = RELAY_IDLE_TIMEOUT_MS
            target.connect(InetSocketAddress(authority.host, authority.port), RELAY_CONNECT_TIMEOUT_MS)
            upstream = target
            client.soTimeout = RELAY_IDLE_TIMEOUT_MS
            val clientOutput = client.getOutputStream()
            clientOutput.write(CONNECT_ESTABLISHED)
            clientOutput.flush()
            established = true
            relayedTunnels += 1

            val pump = Thread({
                try {
                    val buffer = ByteArray(RELAY_BUFFER_BYTES)
                    val source = target.getInputStream()
                    val sink = client.getOutputStream()
                    while (running) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        sink.flush()
                    }
                } catch (_: Exception) {
                    // 任一端关闭都会结束这半条隧道。
                } finally {
                    closeQuietly(target)
                    closeQuietly(client)
                }
            }, "capture-relay-down-${relayCounter.incrementAndGet()}")
            pump.isDaemon = true
            pump.start()

            val buffer = ByteArray(RELAY_BUFFER_BYTES)
            val source = client.getInputStream()
            val sink = target.getOutputStream()
            while (running) {
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sink.flush()
            }
        } catch (_: Exception) {
            // 上游连不上时仍然快速失败，让应用立即报错而不是干等。
            if (!established) {
                try {
                    writeErrorResponse(client.getOutputStream(), 502)
                } catch (_: Exception) {
                    // The client is already gone.
                }
            }
        } finally {
            upstream?.let { closeQuietly(it) }
            closeQuietly(client)
            activeRelays.decrementAndGet()
        }
    }

    private fun terminateAndServe(socket: Socket) {        val tunnel = TlsTerminator(sslContext).open(socket)
        try {
            val input = BufferedInputStream(tunnel.input, BUFFER_BYTES)
            val output = BufferedOutputStream(tunnel.output, BUFFER_BYTES)
            while (running) {
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
                forwardedRequests += 1
                notifyTransaction(request, path, body, result)
                if (request.wantsConnectionClose() || result.connectionClose) break
                // Keep-alive is supported, but an idle socket must not pin a worker (and its TLS
                // engine) for long: the official SDKs open many connections at once.
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

    private fun handlePlainRequest(socket: Socket, request: HttpWire.RequestHead) {
        val absolute = HttpWire.parseAbsoluteTarget(request.target)
        val host = absolute?.authority?.host
            ?: request.header("Host")?.let { HttpWire.parseAuthority(it, 80)?.host }
        if (host != null && host.equals(targetHost, ignoreCase = true)) {
            if (request.isChunked()) {
                writeErrorResponse(socket.getOutputStream(), 400)
                return
            }
            val body = readBody(socket.getInputStream(), request)
            if (body == null) {
                writeErrorResponse(socket.getOutputStream(), 400)
                return
            }
            val path = absolute?.path ?: normalisePath(request.target)
            val result = forward(request, path, body)
            if (result == null) {
                writeErrorResponse(socket.getOutputStream(), 502)
                return
            }
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES)
            writeForwardedResponse(output, result, includeBody = !request.method.equals("HEAD", true))
            output.flush()
            forwardedRequests += 1
            notifyTransaction(request, path, body, result)
            return
        }
        // Unrelated hosts are rejected for the same reason as in handleConnect.
        writeErrorResponse(socket.getOutputStream(), 502)
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
        return try {
            client.newCall(builder.build()).execute().use { response -> toResult(response) }
        } catch (error: Exception) {
            forwardErrors += 1
            onDiag("forward failed (${CaptureExtractor.endpointOf(path)}): ${error.javaClass.simpleName}")
            null
        }
    }

    private fun toResult(response: Response): ForwardResult? {
        val bodyBytes = readLimited(response.body?.byteStream(), MAX_RESPONSE_BODY) ?: return null
        val headers = ArrayList<HttpWire.Header>(response.headers.size)
        for (name in response.headers.names()) {
            if (name.lowercase() in RESPONSE_HEADER_DROP) continue
            for (value in response.headers.values(name)) headers.add(HttpWire.Header(name, value))
        }
        val connectionClose = response.header("Connection")?.contains("close", ignoreCase = true) == true
        return ForwardResult(response.code, response.message, headers, bodyBytes, connectionClose)
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

    private fun closeQuietly(target: java.io.Closeable?) {
        if (target == null) return
        try {
            target.close()
        } catch (_: IOException) {
            // Ignore.
        }
    }

    private class ForwardResult(
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
        private const val KEEP_ALIVE_IDLE_MS = 1_000
        private const val MIN_WORKERS = 2
        private const val MAX_WORKERS = 8
        private const val WORKER_STACK_BYTES = 512L * 1024L
        private const val MAX_REQUEST_BODY = 2 * 1024 * 1024
        private const val MAX_RESPONSE_BODY = 8 * 1024 * 1024
        private const val MAX_RELAYS = 24
        private const val RELAY_CONNECT_TIMEOUT_MS = 10_000
        private const val RELAY_IDLE_TIMEOUT_MS = 60_000
        private const val RELAY_BUFFER_BYTES = 16 * 1024

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
