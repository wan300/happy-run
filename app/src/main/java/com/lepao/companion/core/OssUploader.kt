package com.lepao.companion.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ArtifactKind(val name: String, val prefix: String, val extension: String, val contentType: String)

object ArtifactKinds {
    val ROUTE = ArtifactKind("route", "Public/Upload/file/run_record/", ".txt", "text/plain")
    val SCREENSHOT = ArtifactKind("screenshot", "Public/Upload/pic/run_photo_screenshotImage/", ".png", "image/png")
    val SENSOR = ArtifactKind("sensor", "Public/Upload/file/run_gyroscope/", ".txt", "text/plain")

    val ALL = listOf(ROUTE, SCREENSHOT, SENSOR)

    fun byName(name: String): ArtifactKind =
        ALL.firstOrNull { it.name == name } ?: throw ProtocolException("Unknown artifact kind: $name")

    /** Same shape as the observed client: prefix/yyyy-MM-dd/(1..999)/uuid.ext in +08:00. */
    fun objectKey(kind: ArtifactKind, epoch: Long = RunClock.nowEpoch()): String {
        val date = RunClock.local(epoch).toLocalDate().toString()
        val bucket = (java.security.SecureRandom().nextInt(999) + 1)
        return kind.prefix + date + "/" + bucket + "/" + UUID.randomUUID() + kind.extension
    }
}

data class OssReceipt(
    val kind: String,
    val key: String,
    val sha256: String,
    val bytes: Int,
    val etag: String,
    val requestId: String?,
)

/**
 * OSS Header-V1 `PutObject`, matching the inspected Android client's STS upload path.
 *
 * The STS `callback` field observed in the app is unused by the captured upload call, so no
 * callback header is invented here.
 */
class OssUploader(
    private val audit: AuditLog? = null,
    private val client: OkHttpClient = ApiTransport.defaultClient(),
) {

    fun upload(sts: JSONObject, kind: ArtifactKind, key: String, body: ByteArray): OssReceipt {
        if (sts.optString("bucket") != Endpoints.OSS_BUCKET) {
            throw ProtocolException("STS returned a bucket outside the inspected configuration")
        }
        val expirationText = sts.optString("Expiration")
        val expiration = parseExpiration(expirationText)
        val now = RunClock.nowEpoch()
        if (expiration - now < 60) {
            throw ProtocolException("STS is too close to expiry; request fresh credentials")
        }
        if (!key.startsWith(kind.prefix) || !key.endsWith(kind.extension)) {
            throw ProtocolException("Object key is outside run artifact prefixes")
        }
        val accessKeyId = sts.optString("AccessKeyId")
        val accessKeySecret = sts.optString("AccessKeySecret")
        val securityToken = sts.optString("SecurityToken")
        if (accessKeyId.isBlank() || accessKeySecret.isBlank() || securityToken.isBlank()) {
            throw ProtocolException("STS response is incomplete")
        }

        val contentMd5 = Base64.getEncoder().encodeToString(md5(body))
        val date = httpDate(now)
        val ossHeaders = sortedMapOf(
            "x-oss-forbid-overwrite" to "true",
            "x-oss-security-token" to securityToken,
        )
        val canonicalHeaders = buildString {
            ossHeaders.forEach { (header, value) -> append(header).append(':').append(value.trim()).append('\n') }
        }
        val material = "PUT\n$contentMd5\n${kind.contentType}\n$date\n$canonicalHeaders/${Endpoints.OSS_BUCKET}/$key"
        val signature = Base64.getEncoder().encodeToString(
            hmacSha1(accessKeySecret.toByteArray(Charsets.UTF_8), material.toByteArray(Charsets.UTF_8)),
        )
        val headers = mapOf(
            "Content-MD5" to contentMd5,
            "Content-Type" to kind.contentType,
            "Date" to date,
            "Authorization" to "OSS $accessKeyId:$signature",
            "x-oss-forbid-overwrite" to "true",
            "x-oss-security-token" to securityToken,
        )
        val sha256 = sha256(body)
        audit?.recordOss("oss_put", key, body.size, sha256, headers)

        val url = Endpoints.OSS_ENDPOINT + quotePath(key)
        val builder = Request.Builder().url(url).put(
            body.toRequestBody(kind.contentType.toMediaType()),
        )
        headers.forEach { (name, value) -> builder.header(name, value) }

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (error: java.io.IOException) {
            audit?.recordEvent("oss_delivery_uncertain", null, key)
            throw DeliveryUncertain("OSS delivery outcome is unknown; inspect audit before continuing", error)
        }
        response.use { value ->
            val etag = value.header("ETag")
            val requestId = value.header("x-oss-request-id")
            audit?.recordOss("oss_response", key, 0, sha256, mapOf("status" to value.code.toString(), "etag" to (etag ?: "")))
            if (value.code != 200 || etag.isNullOrBlank()) {
                throw ProtocolException("OSS PUT was not confirmed (HTTP ${value.code})")
            }
            return OssReceipt(kind.name, key, sha256, body.size, etag, requestId)
        }
    }

    private fun parseExpiration(text: String): Long = try {
        Instant.parse(text).epochSecond
    } catch (error: Exception) {
        throw ProtocolException("STS Expiration is not an ISO-8601 instant", error)
    }

    companion object {
        private val HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

        fun httpDate(epoch: Long): String = HTTP_DATE.format(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC),
        )

        fun md5(body: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("MD5").digest(body)

        fun sha256(body: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it) }

        fun hmacSha1(key: ByteArray, material: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(material)
        }

        fun quotePath(path: String): String {
            val builder = StringBuilder()
            for (byte in path.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xff
                val char = value.toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                    char == '-' || char == '_' || char == '.' || char == '~' || char == '/'
                ) {
                    builder.append(char)
                } else {
                    builder.append('%').append(String.format(Locale.ROOT, "%02X", value))
                }
            }
            return builder.toString()
        }
    }
}
