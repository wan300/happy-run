package com.lepao.companion.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Verified TLS transport with persisted cookies and no implicit POST retries.
 *
 * `write = false` permits only the read-only acceptance endpoints. Any transport failure raises
 * [DeliveryUncertain] instead of retrying, because a retried POST may create a duplicate record.
 */
class ApiTransport(
    val session: SessionContext,
    private val write: Boolean,
    private val audit: AuditLog? = null,
    private val persist: ((SessionContext) -> Unit)? = null,
    private val client: OkHttpClient = defaultClient(),
) {

    fun api(request: WireRequest): JSONObject {
        if (!write && request.endpoint !in Endpoints.READ_ONLY) {
            throw ProtocolException("This transport permits read-only acceptance endpoints")
        }
        audit?.recordRequest(request.endpoint, request.fields, request.body)
        val builder = Request.Builder()
            .url(Endpoints.API_BASE + request.endpoint)
            .header("Connection", "close")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(request.body.toRequestBody(FORM_MEDIA_TYPE))
        if (session.userAgent.isNotBlank()) builder.header("User-Agent", session.userAgent)
        val cookie = session.cookieHeader()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (error: IOException) {
            audit?.recordEvent("delivery_uncertain", request.endpoint, error.javaClass.simpleName)
            throw DeliveryUncertain("HTTP delivery outcome is unknown; no request was retried", error)
        }

        val statusCode: Int
        val headers: Map<String, List<String>>
        val bodyBytes: ByteArray
        try {
            response.use { value ->
                statusCode = value.code
                headers = value.headers.toMultimap()
                bodyBytes = value.body?.bytes() ?: ByteArray(0)
            }
        } catch (error: IOException) {
            audit?.recordEvent("delivery_uncertain", request.endpoint, "response-read")
            throw DeliveryUncertain("HTTP response was not fully received; no request was retried", error)
        }

        val auditBody = if (bodyBytes.size <= MAX_AUDIT_BODY) bodyBytes else bodyBytes.copyOf(MAX_AUDIT_BODY)
        audit?.recordResponse(request.endpoint, statusCode, headers, auditBody)

        val setCookies = headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        if (setCookies.isNotEmpty()) {
            session.applySetCookies(setCookies)
        }
        persist?.invoke(session)

        if (statusCode != 200) {
            throw DeliveryUncertain("Unexpected HTTP status $statusCode; inspect encrypted audit")
        }
        val text = String(bodyBytes, StandardCharsets.UTF_8)
        val envelope = try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw DeliveryUncertain("HTTP response was not JSON; inspect encrypted audit", error)
        }
        return Protocol.decodeResponse(envelope)
    }

    companion object {
        private const val MAX_AUDIT_BODY = 256 * 1024
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
    }
}
