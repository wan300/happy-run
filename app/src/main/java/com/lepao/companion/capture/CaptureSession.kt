package com.lepao.companion.capture

import com.lepao.companion.core.CaptureMaterial
import com.lepao.companion.core.CapturePhase
import com.lepao.companion.core.CaptureStatus
import com.lepao.companion.core.CapturedTokenPair
import com.lepao.companion.core.COMMON_FIELDS
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.SessionCookie
import java.nio.charset.StandardCharsets

/** One decrypted request/response pair observed by [LocalMitmProxy] on the target host. */
class HttpTransaction(
    val endpoint: String,
    val path: String,
    val method: String,
    val requestHeaders: List<HttpWire.Header>,
    val requestBody: ByteArray,
    val statusCode: Int,
    val responseHeaders: List<HttpWire.Header>,
    val responseBody: ByteArray,
    val observedAt: Long,
)

/**
 * Folds observed transactions into the single account context the run slice needs.
 *
 * Pure Kotlin (no Android, no store): the caller receives every status/material revision through
 * callbacks, which keeps the consistency rules unit-testable and the service a thin wrapper.
 */
class CaptureSession(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onStatus: ((CaptureStatus) -> Unit)? = null,
    private val onMaterial: ((CaptureMaterial) -> Unit)? = null,
    private val onDiag: ((String) -> Unit)? = null,
) {

    private val wireFields = LinkedHashMap<String, String>()
    private var umid: String = ""
    private var userAgent: String = ""
    private val cookies = LinkedHashMap<String, SessionCookie>()
    private val deviceFields = LinkedHashMap<String, String>()
    private var tokenPair: CapturedTokenPair? = null
    private var observedAt: Long = 0L

    private val endpoints = ArrayDeque<String>()
    private val hints = LinkedHashSet<String>()
    private val warnings = LinkedHashSet<String>()

    private var requestsSeen = 0
    private var loginResponses = 0
    private var identityRequests = 0
    private var loginSeen = false

    private var active = false
    private var vpnActive = false
    private var phase: CapturePhase = CapturePhase.STARTING
    private var error: String? = null
    private var lastEndpoint: String? = null
    private var lastMaterialSignature: String? = null
    private var lastMaterial: CaptureMaterial? = null

    val startedAt: Long = clock()

    @Synchronized
    fun markStarting(): CaptureStatus {
        active = true
        phase = CapturePhase.STARTING
        error = null
        return publish()
    }

    @Synchronized
    fun markRunning(vpnEstablished: Boolean): CaptureStatus {
        active = true
        vpnActive = vpnEstablished
        error = null
        if (phase == CapturePhase.STARTING) phase = derivedPhase()
        return publish()
    }

    @Synchronized
    fun markError(message: String): CaptureStatus {
        active = false
        vpnActive = false
        phase = CapturePhase.ERROR
        error = message
        onDiag?.invoke("capture error: $message")
        return publish()
    }

    /**
     * Diagnostic failure that leaves the tunnel running (for example "the app bypassed the proxy").
     * The phase stays [CapturePhase.ERROR] until a transaction proves traffic flows again.
     */
    @Synchronized
    fun markWarning(message: String): CaptureStatus {
        if (phase != CapturePhase.ERROR) {
            phase = CapturePhase.ERROR
            error = message
            onDiag?.invoke("capture warning: $message")
        }
        return publish()
    }

    /** Transport/revoke stop: keep the captured material, drop the transient session state. */
    @Synchronized
    fun markStopped(hint: String? = null): CaptureStatus {
        active = false
        vpnActive = false
        phase = CapturePhase.IDLE
        error = null
        if (hint != null) hints.add(hint)
        return publish()
    }

    @Synchronized
    fun addHint(hint: String): CaptureStatus {
        hints.add(hint)
        return publish()
    }

    @Synchronized
    fun record(transaction: HttpTransaction): CaptureStatus {
        requestsSeen += 1
        val endpoint = transaction.endpoint
        observedAt = if (transaction.observedAt > 0) transaction.observedAt else clock()
        rememberEndpoint(endpoint)

        userAgent = header(transaction.requestHeaders, "User-Agent")?.takeIf { it.isNotBlank() } ?: userAgent
        for (header in transaction.requestHeaders) {
            if (header.name.equals("Cookie", ignoreCase = true)) {
                for (cookie in CaptureExtractor.parseCookieHeader(header.value)) upsertCookie(cookie)
            }
        }
        for (header in transaction.responseHeaders) {
            if (header.name.equals("Set-Cookie", ignoreCase = true)) {
                CaptureExtractor.parseSetCookie(header.value)?.let(::upsertCookie)
            }
        }

        val form = CaptureExtractor.parseFormBody(String(transaction.requestBody, StandardCharsets.UTF_8))
        val params = CaptureExtractor.decryptParams(form) ?: form
        if (CaptureExtractor.hasIdentityFields(params)) {
            identityRequests += 1
            recordIdentity(endpoint, params)
        }
        if (CaptureExtractor.deviceFieldsFrom(endpoint, params).isNotEmpty()) {
            deviceFields.putAll(CaptureExtractor.deviceFieldsFrom(endpoint, params))
        }
        if (CaptureExtractor.isLoginEndpoint(endpoint)) {
            val pair = CaptureExtractor.extractTokenPair(
                endpoint = endpoint,
                statusCode = transaction.statusCode,
                responseBody = String(transaction.responseBody, StandardCharsets.UTF_8),
                capturedAt = transaction.observedAt,
            )
            if (pair != null) {
                loginResponses += 1
                recordTokenPair(pair)
            }
        }
        if (phase == CapturePhase.ERROR && error != null) error = null
        if (phase != CapturePhase.IDLE) phase = derivedPhase()
        val status = publish()
        emitMaterialIfChanged()
        return status
    }

    @Synchronized
    fun status(): CaptureStatus = buildStatus()

    @Synchronized
    fun material(): CaptureMaterial? = lastMaterial

    private fun recordIdentity(endpoint: String, params: Map<String, String>) {
        val nextUid = params["uid"].orEmpty()
        val nextToken = params["token"].orEmpty()
        val previousUid = wireFields["uid"].orEmpty()
        val previousToken = wireFields["token"].orEmpty()
        val switchedAccount = wireFields.isNotEmpty() && previousUid.isNotEmpty() && nextUid.isNotEmpty() &&
            previousUid != nextUid
        val rotatedToken = !switchedAccount && wireFields.isNotEmpty() &&
            previousToken.isNotEmpty() && nextToken.isNotEmpty() && previousToken != nextToken
        if (switchedAccount || rotatedToken) {
            if (umid.isNotEmpty()) {
                warnings.add("身份上下文发生变化，已丢弃与旧账号不匹配的 UMID")
                umid = ""
            }
        }
        val extracted = CaptureExtractor.umidFromRequest(endpoint, params)
        if (!extracted.isNullOrBlank()) {
            if (umid.isNotEmpty() && umid != extracted) {
                warnings.add("观测到多个 UMID 候选，已采用时间最新的一个")
            }
            umid = extracted
        }
        val identity = CaptureExtractor.identityOf(params)
        if (wireFields.isNotEmpty() && wireFields["uid"] == identity["uid"] &&
            wireFields["token"] != identity["token"] && identity["token"].orEmpty().isNotEmpty() &&
            wireFields["token"].orEmpty().isNotEmpty()
        ) {
            warnings.add("业务请求的 access_token 已更新，采用最新一次请求")
        }
        wireFields.putAll(identity)
    }

    private fun recordTokenPair(pair: CapturedTokenPair) {
        val existing = tokenPair
        if (existing != null && existing.accessToken == pair.accessToken && existing.refreshToken == pair.refreshToken) {
            return
        }
        tokenPair = pair
        loginSeen = true
        val identityToken = wireFields["token"].orEmpty()
        val identityUid = wireFields["uid"].orEmpty()
        if (identityToken.isNotEmpty() && identityToken != pair.accessToken) {
            warnings.add("登录响应的 access_token 与已捕获业务请求的 token 不一致，自动续期将被跳过")
        }
        if (!pair.uid.isNullOrEmpty() && identityUid.isNotEmpty() && pair.uid != identityUid) {
            warnings.add("登录响应属于另一个 uid，已按最新响应记录")
        }
    }

    private fun derivedPhase(): CapturePhase = when {
        contextReady() -> CapturePhase.CONTEXT_READY
        loginSeen -> CapturePhase.LOGIN_SEEN
        else -> CapturePhase.RUNNING
    }

    private fun identityComplete(): Boolean =
        wireFields.isNotEmpty() && COMMON_FIELDS.all { wireFields[it]?.isNotBlank() == true }

    private fun contextReady(): Boolean = identityComplete() && umid.isNotBlank() && cookies.isNotEmpty()

    private fun rememberEndpoint(endpoint: String) {
        if (endpoint.isEmpty()) return
        endpoints.remove(endpoint)
        endpoints.addLast(endpoint)
        while (endpoints.size > MAX_ENDPOINTS) endpoints.removeFirst()
        lastEndpoint = endpoint
    }

    private fun upsertCookie(cookie: SessionCookie) {
        if (cookie.name.isBlank()) return
        cookies[cookie.name] = cookie
        while (cookies.size > MAX_COOKIES) {
            val oldest = cookies.keys.firstOrNull() ?: break
            cookies.remove(oldest)
        }
    }

    private fun buildStatus(): CaptureStatus = CaptureStatus(
        phase = phase,
        active = active,
        vpnActive = vpnActive,
        requestsSeen = requestsSeen,
        loginResponses = loginResponses,
        identityRequests = identityRequests,
        loginSeen = loginSeen,
        tokenPairSeen = tokenPair != null,
        identitySeen = identityComplete(),
        contextReady = contextReady(),
        startedAt = startedAt,
        lastEndpoint = lastEndpoint,
        endpoints = endpoints.toList(),
        error = error,
        hints = hints.toList(),
    )

    private fun publish(): CaptureStatus {
        val status = buildStatus()
        onStatus?.invoke(status)
        return status
    }

    private fun emitMaterialIfChanged() {
        if (wireFields.isEmpty() && umid.isEmpty() && cookies.isEmpty() && tokenPair == null) return
        val signature = buildString {
            append(wireFields).append('|').append(umid).append('|').append(userAgent).append('|')
            append(cookies.entries.joinToString(",") { "${it.key}=${it.value.value}" }).append('|')
            append(deviceFields).append('|')
            append(tokenPair?.accessToken).append('|').append(tokenPair?.refreshToken)
            append('|').append(warnings)
        }
        if (signature == lastMaterialSignature) return
        lastMaterialSignature = signature
        val material = CaptureMaterial(
            wireFields = LinkedHashMap(wireFields),
            umid = umid,
            userAgent = userAgent,
            cookies = cookies.values.map { it.copy() }.toMutableList(),
            deviceFields = LinkedHashMap(deviceFields),
            tokenPair = tokenPair,
            observedAt = if (observedAt > 0) observedAt else clock(),
            warnings = warnings.toMutableList(),
        )
        lastMaterial = material
        onMaterial?.invoke(material)
        val endpoint = lastEndpoint ?: return
        onDiag?.invoke("capture material updated (endpoint=$endpoint, identity=$identityRequests, logins=$loginResponses)")
    }

    private fun header(headers: List<HttpWire.Header>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    companion object {
        private const val MAX_ENDPOINTS = 10
        private const val MAX_COOKIES = 24
    }
}
