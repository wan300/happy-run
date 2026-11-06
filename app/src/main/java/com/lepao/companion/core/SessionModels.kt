package com.lepao.companion.core

import org.json.JSONArray
import org.json.JSONObject

data class SessionCookie(
    var name: String,
    var value: String,
    var domain: String = "api2.lptiyu.com",
    var path: String = "/",
    var secure: Boolean = true,
    var expires: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("value", value)
        put("domain", domain)
        put("path", path)
        put("secure", secure)
        if (expires != null) put("expires", expires) else put("expires", JSONObject.NULL)
    }

    companion object {
        fun fromJson(source: JSONObject): SessionCookie = SessionCookie(
            name = source.optString("name"),
            value = source.optString("value"),
            domain = source.optString("domain", "api2.lptiyu.com"),
            path = source.optString("path", "/"),
            secure = source.optBoolean("secure", true),
            expires = JsonUtil.optLongOrNull(source, "expires"),
        )
    }
}

/**
 * The authenticated account context. Field names are the literal wire names so a request can be
 * built without a translation table, and so captured material can be imported verbatim.
 */
class Identity(
    val wire: LinkedHashMap<String, String> = LinkedHashMap(),
    var umid: String = "",
) {
    operator fun get(key: String): String = wire[key] ?: ""

    fun copy(): Identity = Identity(LinkedHashMap(wire), umid)

    fun put(key: String, value: String) {
        wire[key] = value
    }

    fun requireComplete() {
        val missing = COMMON_FIELDS.filter { get(it).isBlank() }
        if (missing.isNotEmpty()) {
            throw SessionException("Login context is missing fields: ${missing.joinToString(",")}")
        }
        if (umid.isBlank()) throw SessionException("Login context has no observed device UMID")
    }

    fun maskedUid(): String {
        val uid = get("uid")
        if (uid.length <= 4) return uid
        return uid.take(2) + "****" + uid.takeLast(2)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        wire.forEach { (key, value) -> put(key, value) }
        put("umid", umid)
    }

    companion object {
        fun fromJson(source: JSONObject): Identity {
            val map = LinkedHashMap<String, String>()
            for (key in COMMON_FIELDS) {
                if (source.has(key)) map[key] = source.optString(key)
            }
            return Identity(map, source.optString("umid"))
        }

        fun fromWireMap(values: Map<String, String>, umid: String): Identity {
            val map = LinkedHashMap<String, String>()
            for (key in COMMON_FIELDS) map[key] = values[key] ?: ""
            return Identity(map, umid)
        }
    }
}

class SessionContext(
    var identity: Identity,
    var cookies: MutableList<SessionCookie> = mutableListOf(),
    var userAgent: String = "",
    var deviceFields: MutableMap<String, String> = mutableMapOf(),
    var refreshToken: String? = null,
    var refreshExpire: Long? = null,
    /** The access token that belongs to [refreshToken]; refresh is only attempted when it matches. */
    var refreshAccessToken: String? = null,
    var refreshedAt: Long? = null,
    var capturedAt: Long = 0,
    var source: String = "",
    /** Set when the server rejected the session (e.g. business status 102): the token is dead. */
    var invalidatedAt: Long? = null,
) {
    fun copy(): SessionContext = SessionContext(
        identity = identity.copy(),
        cookies = cookies.map { it.copy() }.toMutableList(),
        userAgent = userAgent,
        deviceFields = LinkedHashMap(deviceFields),
        refreshToken = refreshToken,
        refreshExpire = refreshExpire,
        refreshAccessToken = refreshAccessToken,
        refreshedAt = refreshedAt,
        capturedAt = capturedAt,
        source = source,
        invalidatedAt = invalidatedAt,
    )

    fun cookieHeader(): String = cookies
        .filter { it.name.isNotBlank() }
        .joinToString("; ") { "${it.name}=${it.value}" }

    fun upsertCookie(cookie: SessionCookie) {
        cookies.removeAll { it.name == cookie.name }
        cookies.add(cookie)
    }

    fun applySetCookies(headers: List<String>) {
        for (header in headers) {
            val parts = header.split(';')
            if (parts.isEmpty()) continue
            val pair = parts.first().trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            var path = "/"
            var domain = "api2.lptiyu.com"
            var secure = true
            var expires: Long? = null
            for (attribute in parts.drop(1)) {
                val trimmed = attribute.trim()
                val eq = trimmed.indexOf('=')
                val key = (if (eq >= 0) trimmed.substring(0, eq) else trimmed).trim().lowercase()
                val attributeValue = if (eq >= 0) trimmed.substring(eq + 1).trim() else ""
                when (key) {
                    "path" -> path = attributeValue
                    "domain" -> domain = attributeValue.trimStart('.')
                    "secure" -> secure = true
                    "max-age" -> attributeValue.toLongOrNull()?.let { expires = System.currentTimeMillis() / 1000 + it }
                    "expires" -> {
                        expires = try {
                            java.time.ZonedDateTime.parse(
                                attributeValue,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                            ).toEpochSecond()
                        } catch (_: Exception) {
                            expires
                        }
                    }
                }
            }
            upsertCookie(SessionCookie(name, value, domain, path, secure, expires))
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("identity", identity.toJson())
        val cookieArray = JSONArray()
        cookies.forEach { cookieArray.put(it.toJson()) }
        put("cookies", cookieArray)
        put("user_agent", userAgent)
        put("device_fields", JSONObject(deviceFields as Map<*, *>))
        put("refresh_token", refreshToken ?: JSONObject.NULL)
        put("refresh_expire", refreshExpire ?: JSONObject.NULL)
        put("refresh_access_token", refreshAccessToken ?: JSONObject.NULL)
        put("refreshed_at", refreshedAt ?: JSONObject.NULL)
        put("captured_at", capturedAt)
        put("source", source)
        put("invalidated_at", invalidatedAt ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(source: JSONObject): SessionContext {
            val cookies = mutableListOf<SessionCookie>()
            val array = source.optJSONArray("cookies")
            if (array != null) {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { cookies.add(SessionCookie.fromJson(it)) }
                }
            }
            val deviceFields = LinkedHashMap<String, String>()
            val fields = source.optJSONObject("device_fields")
            if (fields != null) {
                val keys = fields.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    deviceFields[key] = fields.optString(key)
                }
            }
            return SessionContext(
                identity = Identity.fromJson(source.optJSONObject("identity") ?: JSONObject()),
                cookies = cookies,
                userAgent = source.optString("user_agent"),
                deviceFields = deviceFields,
                refreshToken = JsonUtil.optStringOrNull(source, "refresh_token"),
                refreshExpire = JsonUtil.optLongOrNull(source, "refresh_expire"),
                refreshAccessToken = JsonUtil.optStringOrNull(source, "refresh_access_token"),
                refreshedAt = JsonUtil.optLongOrNull(source, "refreshed_at"),
                capturedAt = JsonUtil.optLongOrNull(source, "captured_at") ?: 0L,
                source = source.optString("source"),
                invalidatedAt = JsonUtil.optLongOrNull(source, "invalidated_at"),
            )
        }
    }
}

data class LoginHealth(
    val uidPresent: Boolean,
    val accessTokenPresent: Boolean,
    val refreshTokenPresent: Boolean,
    val refreshExpire: Long?,
    val refreshDaysLeft: Double?,
    val refreshedAt: Long?,
    val canRefresh: Boolean,
    val refreshTokenMatchesAccessToken: Boolean,
    /** The server rejected this session (e.g. 102 other-device login); only a new capture helps. */
    val invalidated: Boolean = false,
)
