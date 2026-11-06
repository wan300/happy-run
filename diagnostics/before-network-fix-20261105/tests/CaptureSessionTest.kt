package com.lepao.companion.capture

import com.lepao.companion.core.CapturePhase
import com.lepao.companion.core.CaptureStatus
import com.lepao.companion.core.COMMON_FIELDS
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.Identity
import com.lepao.companion.core.Protocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Account-context folding: which request wins, when a UMID must be dropped, and when the material is
 * complete enough for the run slice to use.
 */
class CaptureSessionTest {

    private val identity: Identity = Identity(
        LinkedHashMap<String, String>().apply {
            put("version", "4.3.2")
            put("version_name", "4.3.2")
            put("mobileModel", "V2172A")
            put("mobileDeviceId", "0123456789abcdef")
            put("mobileOsVersion", "12")
            put("ostype", "1")
            put("student_num", "20260101")
            put("school_id", "12")
            put("uid", "12217924")
            put("token", "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6")
        },
        "6f1d3c2b9a8e7d6c5b4a39281706f5e4",
    )

    private val otherIdentity: Identity = Identity(
        LinkedHashMap(identity.wire).apply {
            put("uid", "99999999")
            put("token", "Z9y8X7w6V5u4T3s2R1q0P9o8N7m6L5k4")
        },
        "ffffffffffffffffffffffffffffffff",
    )

    private val timestamp = 1793778542L

    private fun requestBody(
        target: Identity,
        endpoint: String = Endpoints.BEFORE_RUN,
        extra: Map<String, Any?> = emptyMap(),
        timeStr: String? = null,
    ): ByteArray = Protocol.buildRequest(endpoint, target, extra, timestamp, nonce = "123456", timeStr = timeStr).body

    private fun transaction(
        path: String,
        body: ByteArray,
        responseBody: String = "{\"status\":1,\"data\":{}}",
        statusCode: Int = 200,
        cookie: String? = null,
        responseHeaders: List<HttpWire.Header> = emptyList(),
        observedAt: Long = 1_000L,
    ): HttpTransaction {
        val headers = ArrayList<HttpWire.Header>()
        headers.add(HttpWire.Header("User-Agent", "okhttp/3.12.13"))
        headers.add(HttpWire.Header("Content-Type", "application/x-www-form-urlencoded"))
        if (cookie != null) headers.add(HttpWire.Header("Cookie", cookie))
        return HttpTransaction(
            endpoint = CaptureExtractor.endpointOf(path),
            path = path,
            method = "POST",
            requestHeaders = headers,
            requestBody = body,
            statusCode = statusCode,
            responseHeaders = responseHeaders,
            responseBody = responseBody.toByteArray(StandardCharsets.UTF_8),
            observedAt = observedAt,
        )
    }

    private fun loginResponse(access: String, refresh: String, uid: String = "12217924"): String {
        val data = JSONObject().apply {
            put("uid", uid)
            put("access_token", access)
            put("refresh_token", refresh)
            put("refresh_expire", 1795000000L)
        }
        return JSONObject().apply {
            put("status", 1)
            put("data", Protocol.encryptText(data.toString(), "NEW_GAME"))
        }.toString()
    }

    @Test
    fun identityAndUmidAreFoldedFromAnEncryptedBusinessRequest() {
        val session = CaptureSession(clock = { 500L })
        val status = session.record(transaction("/v3/api.php/Run2/beforeRunV260", requestBody(identity)))

        assertTrue(status.identitySeen)
        assertEquals(1, status.identityRequests)
        assertEquals(1, status.requestsSeen)
        assertEquals("Run2/beforeRunV260", status.lastEndpoint)
        assertEquals(listOf("Run2/beforeRunV260"), status.endpoints)
        assertFalse("no login response yet", status.loginSeen)
        assertFalse("cookies are still missing", status.contextReady)

        val material = requireNotNull(session.material())
        assertEquals(identity.umid, material.umid)
        for (field in COMMON_FIELDS) {
            assertEquals("wire field $field", identity[field], material.wireFields[field])
        }
        assertEquals("okhttp/3.12.13", material.userAgent)
        assertNull(material.tokenPair)
        val context = requireNotNull(material.toSessionContext())
        assertEquals(identity["uid"], context.identity["uid"])
        assertEquals(identity.umid, context.identity.umid)
        assertNull("no login response was observed yet", context.refreshToken)
    }

    @Test
    fun contextBecomesReadyWithCookiesAndUmid() {
        val session = CaptureSession()
        val status = session.record(
            transaction(
                "/v3/api.php/Run2/beforeRunV260",
                requestBody(identity),
                cookie = "PHPSESSID=abc123; theme=dark",
            ),
        )
        assertTrue(status.identitySeen)
        assertTrue(status.contextReady)
        assertEquals(CapturePhase.CONTEXT_READY, status.phase)

        val material = requireNotNull(session.material())
        assertEquals(2, material.cookies.size)
        val context = requireNotNull(material.toSessionContext())
        assertEquals(identity["uid"], context.identity["uid"])
        assertEquals(identity.umid, context.identity.umid)
        assertEquals("okhttp/3.12.13", context.userAgent)
        assertEquals("android-capture", context.source)

        val withCookies = session.record(
            transaction(
                "/v3/api.php/User/User",
                requestBody(identity, Endpoints.RECORD_DETAIL, mapOf("record_id" to "259921")),
                cookie = "PHPSESSID=def456",
                responseHeaders = listOf(
                    HttpWire.Header("Set-Cookie", "PHPSESSID=ghi789; Path=/; Domain=api2.lptiyu.com"),
                ),
            ),
        )
        assertEquals(2, withCookies.endpoints.size)
        assertEquals("ghi789", session.material()?.cookies?.first { it.name == "PHPSESSID" }?.value)
    }

    @Test
    fun loginResponseAddsATokenPairAndAdvancedPhase() {
        val session = CaptureSession()
        session.record(transaction("/v3/api.php/Run2/beforeRunV260", requestBody(identity)))
        val status = session.record(
            transaction(
                "/v3/api.php/Login/RefreshToken",
                requestBody(
                    identity,
                    Endpoints.REFRESH,
                    mapOf("access_token" to identity["token"], "refresh_token" to "OLD-REFRESH"),
                ),
                responseBody = loginResponse(identity["token"], "NEW-REFRESH"),
            ),
        )
        assertEquals("Login/RefreshToken", status.lastEndpoint)
        assertTrue(status.loginSeen)
        assertTrue(status.tokenPairSeen)
        assertEquals(1, status.loginResponses)
        assertEquals(CapturePhase.LOGIN_SEEN, status.phase)

        val material = requireNotNull(session.material())
        val pair = requireNotNull(material.tokenPair)
        assertEquals(identity["token"], pair.accessToken)
        assertEquals("NEW-REFRESH", pair.refreshToken)
        val context = requireNotNull(material.toSessionContext())
        assertEquals("NEW-REFRESH", context.refreshToken)
        assertEquals(identity["token"], context.refreshAccessToken)
    }

    @Test
    fun mismatchedTokenFamilyDisablesRefreshWithAWarning() {
        val session = CaptureSession()
        session.record(
            transaction(
                "/v3/api.php/Run2/beforeRunV260",
                requestBody(identity),
                cookie = "PHPSESSID=abc123",
            ),
        )
        session.record(
            transaction(
                "/v3/api.php/Login/RefreshToken",
                requestBody(identity, Endpoints.REFRESH, mapOf("refresh_token" to "OLD-REFRESH")),
                responseBody = loginResponse("SOME-OTHER-ACCESS-TOKEN", "NEW-REFRESH"),
            ),
        )
        val material = requireNotNull(session.material())
        assertTrue(
            "the mismatch must be reported",
            material.warnings.any { it.contains("access_token") },
        )
        val context = requireNotNull(material.toSessionContext())
        assertNull("refresh is skipped for a foreign token family", context.refreshAccessToken)
        assertEquals("NEW-REFRESH", context.refreshToken)
    }

    @Test
    fun accountSwitchDropsTheStaleUmid() {
        val session = CaptureSession()
        session.record(transaction("/v3/api.php/Run2/beforeRunV260", requestBody(identity)))
        assertEquals(identity.umid, session.material()?.umid)

        // A plain start request for another account carries no dynamic field at all.
        val status = session.record(
            transaction(
                "/v3/api.php/Run/getTimestampV278",
                requestBody(otherIdentity, Endpoints.START, mapOf("game_id" to "158")),
            ),
        )
        assertEquals("99999999", session.material()?.wireFields?.get("uid"))
        assertEquals("", session.material()?.umid)
        assertTrue("the ten wire fields are still present", status.identitySeen)
        assertFalse("a context needs the new account UMID", status.contextReady)
        assertNull(session.material()?.toSessionContext())
        assertTrue(session.material()?.warnings?.any { it.contains("UMID") } == true)
    }

    @Test
    fun endpointsAreDistinctMostRecentLastAndCapped() {
        val session = CaptureSession()
        var last: CaptureStatus? = null
        for (index in 1..12) {
            last = session.record(
                transaction("/v3/api.php/User/User$index", requestBody(identity), observedAt = 1_000L + index),
            )
        }
        val status = requireNotNull(last)
        assertEquals(10, status.endpoints.size)
        assertEquals("User/User12", status.endpoints.last())
        assertEquals("User/User3", status.endpoints.first())
        assertEquals("User/User12", status.lastEndpoint)

        val repeated = session.record(transaction("/v3/api.php/User/User3", requestBody(identity)))
        assertEquals(10, repeated.endpoints.size)
        assertEquals("User/User3", repeated.endpoints.last())
        assertEquals("no duplicate entries", 10, repeated.endpoints.toSet().size)
    }

    @Test
    fun stopRunContributesDeviceFieldsButNeverAUmid() {
        val session = CaptureSession()
        session.record(transaction("/v3/api.php/Run2/beforeRunV260", requestBody(identity)))
        val status = session.record(
            transaction(
                "/v3/api.php/Run/stopRunV278",
                requestBody(
                    identity,
                    Endpoints.STOP,
                    mapOf("oaid" to "oaid-fixture", "mac" to "02:00:00:00:00:01", "distance" to "2.03"),
                    timeStr = "00:12:34",
                ),
            ),
        )
        assertEquals(identity.umid, session.material()?.umid)
        assertEquals("oaid-fixture", session.material()?.deviceFields?.get("oaid"))
        assertEquals("02:00:00:00:00:01", session.material()?.deviceFields?.get("mac"))
        assertEquals(2, status.identityRequests)
    }

    @Test
    fun lifecycleStatusesKeepCapturedMaterial() {
        val published = ArrayList<CaptureStatus>()
        val materials = ArrayList<com.lepao.companion.core.CaptureMaterial>()
        val session = CaptureSession(
            onStatus = { published.add(it) },
            onMaterial = { materials.add(it) },
        )
        assertEquals(CapturePhase.STARTING, session.markStarting().phase)
        session.record(transaction("/v3/api.php/Run2/beforeRunV260", requestBody(identity), cookie = "PHPSESSID=abc"))
        session.markRunning(true)
        assertTrue(session.status().active)
        assertTrue(session.status().vpnActive)
        assertEquals(CapturePhase.CONTEXT_READY, session.status().phase)

        val stopped = session.markStopped("已采集 10 分钟")
        assertEquals(CapturePhase.IDLE, stopped.phase)
        assertFalse(stopped.active)
        assertFalse(stopped.vpnActive)
        assertTrue(stopped.hints.any { it.contains("10 分钟") })
        assertNotNull("material survives a stop", session.material())
        assertEquals(identity.umid, session.material()?.umid)
        assertTrue(published.isNotEmpty())
        assertTrue(materials.isNotEmpty())
    }

    @Test
    fun diagnosticErrorIsClearedByTheNextObservedTransaction() {
        val session = CaptureSession()
        session.markRunning(true)
        val warning = session.markWarning("官方应用未走代理，请停止后重试")
        assertEquals(CapturePhase.ERROR, warning.phase)
        assertEquals("官方应用未走代理，请停止后重试", warning.error)
        assertTrue("the tunnel is still running", warning.active)

        val recovered = session.record(
            transaction("/v3/api.php/Run2/beforeRunV260", requestBody(identity), cookie = "PHPSESSID=abc"),
        )
        assertNull(recovered.error)
        assertEquals(CapturePhase.CONTEXT_READY, recovered.phase)
    }
}
