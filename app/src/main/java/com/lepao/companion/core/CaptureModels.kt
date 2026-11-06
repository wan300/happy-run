package com.lepao.companion.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CapturePhase {
    IDLE,
    STARTING,
    RUNNING,
    LOGIN_SEEN,
    CONTEXT_READY,
    ERROR,
}

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.IDLE,
    val active: Boolean = false,
    val vpnActive: Boolean = false,
    val requestsSeen: Int = 0,
    val loginResponses: Int = 0,
    val identityRequests: Int = 0,
    val loginSeen: Boolean = false,
    val tokenPairSeen: Boolean = false,
    val identitySeen: Boolean = false,
    val contextReady: Boolean = false,
    val startedAt: Long = 0L,
    val lastEndpoint: String? = null,
    val endpoints: List<String> = emptyList(),
    val error: String? = null,
    val hints: List<String> = emptyList(),
) {
    companion object {
        fun idle() = CaptureStatus()
    }
}

data class CapturedTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpire: Long?,
    val uid: String?,
    val endpoint: String,
    val capturedAt: Long = RunClock.nowEpoch(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("refresh_expire", refreshExpire ?: JSONObject.NULL)
        put("uid", uid ?: JSONObject.NULL)
        put("endpoint", endpoint)
        put("captured_at", capturedAt)
    }

    companion object {
        fun fromJson(source: JSONObject): CapturedTokenPair? {
            val access = JsonUtil.optStringOrNull(source, "access_token") ?: return null
            val refresh = JsonUtil.optStringOrNull(source, "refresh_token") ?: return null
            return CapturedTokenPair(
                accessToken = access,
                refreshToken = refresh,
                refreshExpire = JsonUtil.optLongOrNull(source, "refresh_expire"),
                uid = JsonUtil.optStringOrNull(source, "uid"),
                endpoint = source.optString("endpoint"),
                capturedAt = JsonUtil.optLongOrNull(source, "captured_at") ?: 0L,
            )
        }
    }
}

/**
 * Everything the capture VPN observed that is required to act as the same signed-in account:
 * authenticated wire fields, the UMID-derived dynamic key material, cookies, UA and tokens.
 */
class CaptureMaterial(
    val wireFields: LinkedHashMap<String, String>,
    var umid: String,
    var userAgent: String,
    var cookies: MutableList<SessionCookie>,
    var deviceFields: MutableMap<String, String>,
    var tokenPair: CapturedTokenPair?,
    var observedAt: Long,
    var warnings: MutableList<String> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("wire_fields", JSONObject(wireFields as Map<*, *>))
        put("umid", umid)
        put("user_agent", userAgent)
        put("cookies", org.json.JSONArray().apply { cookies.forEach { put(it.toJson()) } })
        put("device_fields", JSONObject(deviceFields as Map<*, *>))
        put("token_pair", tokenPair?.toJson() ?: JSONObject.NULL)
        put("observed_at", observedAt)
        put("warnings", org.json.JSONArray(warnings))
    }

    /** Build the persistent account session; returns null while mandatory material is absent. */
    fun toSessionContext(): SessionContext? {
        if (wireFields.keys.none { it == "uid" }) return null
        val identity = Identity.fromWireMap(wireFields, umid)
        val missing = COMMON_FIELDS.filter { identity[it].isBlank() }
        if (missing.isNotEmpty() || umid.isBlank()) return null
        val session = SessionContext(
            identity = identity,
            cookies = cookies.map { it.copy() }.toMutableList(),
            userAgent = userAgent,
            deviceFields = LinkedHashMap(deviceFields),
            refreshToken = tokenPair?.refreshToken,
            refreshExpire = tokenPair?.refreshExpire,
            refreshAccessToken = tokenPair?.accessToken,
            capturedAt = observedAt,
            source = "android-capture",
        )
        if (tokenPair != null && tokenPair?.accessToken != identity["token"]) {
            session.refreshAccessToken = null
            warnings.add("捕获到的刷新令牌与业务请求的 access_token 不属于同一令牌族，自动续期将跳过")
        }
        return session
    }

    companion object {
        fun fromJson(source: JSONObject): CaptureMaterial? {
            val wire = source.optJSONObject("wire_fields") ?: return null
            val fields = LinkedHashMap<String, String>()
            val ua = source.optString("user_agent")
            val cookieList = mutableListOf<SessionCookie>()
            source.optJSONArray("cookies")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { cookieList.add(SessionCookie.fromJson(it)) }
                }
            }
            val device = LinkedHashMap<String, String>()
            source.optJSONObject("device_fields")?.let { objectFields ->
                val keys = objectFields.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    device[key] = objectFields.optString(key)
                }
            }
            val keys = wire.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                fields[key] = wire.optString(key)
            }
            val warnings = mutableListOf<String>()
            source.optJSONArray("warnings")?.let { array ->
                for (index in 0 until array.length()) warnings.add(array.optString(index))
            }
            return CaptureMaterial(
                wireFields = fields,
                umid = source.optString("umid"),
                userAgent = ua,
                cookies = cookieList,
                deviceFields = device,
                tokenPair = source.optJSONObject("token_pair")?.let { CapturedTokenPair.fromJson(it) },
                observedAt = JsonUtil.optLongOrNull(source, "observed_at") ?: 0L,
                warnings = warnings,
            )
        }
    }
}

object CaptureStore {

    private const val CAPTURE_FILE = "capture.enc.json"
    private lateinit var appContext: Context
    private lateinit var secure: SecureStore

    @Volatile
    var status: CaptureStatus = CaptureStatus.idle()
        private set

    @Volatile
    var material: CaptureMaterial? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        secure = SecureStore(appContext)
    }

    fun updateStatus(value: CaptureStatus) {
        status = value
    }

    fun updateMaterial(value: CaptureMaterial?) {
        material = value
        persist()
    }

    fun reset() {
        material = null
        status = CaptureStatus.idle()
        File(appContext.filesDir, CAPTURE_FILE).delete()
    }

    fun restore() {
        val json = secure.readJson(File(appContext.filesDir, CAPTURE_FILE)) ?: return
        material = runCatching { CaptureMaterial.fromJson(json) }.getOrNull()
        if (material != null) {
            status = status.copy(
                identitySeen = true,
                tokenPairSeen = material?.tokenPair != null,
                contextReady = material?.toSessionContext() != null,
            )
        }
    }

    private fun persist() {
        val value = material ?: return
        try {
            secure.writeJson(File(appContext.filesDir, CAPTURE_FILE), value.toJson())
        } catch (_: Exception) {
            // The in-memory material is still usable; persistence is retried on the next update.
        }
    }

    fun newRequestId(): String = UUID.randomUUID().toString()
}
