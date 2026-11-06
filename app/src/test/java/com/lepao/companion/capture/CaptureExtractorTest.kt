package com.lepao.companion.capture

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

/**
 * Extraction rules verified against requests produced by [Protocol.buildRequest] — the same encoder
 * the companion uses on the wire — and against responses shaped like the captured login replies.
 */
class CaptureExtractorTest {

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

    private val timestamp = 1793778542L
    private val nonce = "123456"

    private fun formOf(endpoint: String, extra: Map<String, Any?> = emptyMap(), timeStr: String? = null): Map<String, String> {
        val request = Protocol.buildRequest(
            endpoint,
            identity,
            extra,
            timestamp,
            nonce = nonce,
            timeStr = timeStr,
        )
        return CaptureExtractor.parseFormBody(String(request.body, Charsets.UTF_8))
    }

    @Test
    fun encryptedBeforeRunRequestYieldsIdentityAndUmid() {
        val form = formOf(Endpoints.BEFORE_RUN)
        assertEquals("only the encrypted key field is on the wire", setOf("key"), form.keys.toSet())

        val params = CaptureExtractor.decryptParams(form)
        assertNotNull("encrypted params must decrypt", params)
        requireNotNull(params)

        val extracted = CaptureExtractor.identityOf(params)
        for (field in COMMON_FIELDS) {
            assertEquals("identity field $field", identity[field], extracted[field])
        }
        assertEquals(timestamp.toString(), params["timestamp"])
        val dynamicName = Protocol.dynamicName(identity["mobileModel"], timestamp.toString())
        assertEquals(dynamicName, CaptureExtractor.dynamicFieldName(params))
        assertEquals(identity.umid, CaptureExtractor.umidFromRequest(Endpoints.BEFORE_RUN, params))
    }

    @Test
    fun plainStartFormYieldsIdentityButNoUmid() {
        val form = formOf(Endpoints.START, mapOf("game_id" to "158", "point_ids" to "24560,2894"))
        assertNull("a plain form has no key field", CaptureExtractor.decryptParams(form))
        assertTrue(CaptureExtractor.hasIdentityFields(form))
        assertEquals(identity["uid"], form["uid"])
        assertNull(CaptureExtractor.dynamicFieldName(form))
        assertNull(CaptureExtractor.umidFromRequest(Endpoints.START, form))
    }

    @Test
    fun stopRunDynamicFieldIsNotTreatedAsUmid() {
        val form = formOf(
            Endpoints.STOP,
            mapOf("oaid" to "oaid-fixture", "mac" to "02:00:00:00:00:01"),
            timeStr = "00:12:34",
        )
        val params = requireNotNull(CaptureExtractor.decryptParams(form))
        val dynamicName = requireNotNull(CaptureExtractor.dynamicFieldName(params))
        assertEquals("00:12:34", params[dynamicName])
        assertNull(CaptureExtractor.umidFromRequest(Endpoints.STOP, params))
        assertNull(CaptureExtractor.decodeUmid(params.getValue(dynamicName), params.getValue("timestamp")))

        val device = CaptureExtractor.deviceFieldsFrom(Endpoints.STOP, params)
        assertEquals("oaid-fixture", device["oaid"])
        assertEquals("02:00:00:00:00:01", device["mac"])
        assertTrue("other endpoints never contribute device fields", CaptureExtractor.deviceFieldsFrom(Endpoints.BEFORE_RUN, params).isEmpty())
    }

    @Test
    fun umidSuffixIsVerifiedAgainstTheRequestTimestamp() {
        val valid = Protocol.dynamicValue(timestamp.toString(), identity.umid)
        assertEquals(identity.umid, CaptureExtractor.decodeUmid(valid, timestamp.toString()))

        // Same 15-slot key, different timestamp: decryption succeeds but the md5 suffix must not.
        val sameKeyOtherTimestamp = (timestamp - 15).toString()
        val stale = Protocol.dynamicValue(sameKeyOtherTimestamp, identity.umid)
        assertNull(CaptureExtractor.decodeUmid(stale, timestamp.toString()))
        assertNull(CaptureExtractor.decodeUmid(valid, "not-a-timestamp"))
        assertNull(CaptureExtractor.decodeUmid("", timestamp.toString()))
    }

    @Test
    fun encryptedLoginResponseYieldsTokenPair() {
        val data = JSONObject().apply {
            put("uid", "12217924")
            put("access_token", identity["token"])
            put("refresh_token", "R1e2f3g4H5i6J7k8L9m0N1o2P3q4R5s6")
            put("refresh_expire", 1795000000L)
        }
        val envelope = JSONObject().apply {
            put("status", 1)
            put("data", Protocol.encryptText(data.toString(), "NEW_GAME"))
        }
        val pair = CaptureExtractor.extractTokenPair("Login/RefreshToken", 200, envelope.toString(), 4242L)
        assertNotNull(pair)
        requireNotNull(pair)
        assertEquals(identity["token"], pair.accessToken)
        assertEquals("R1e2f3g4H5i6J7k8L9m0N1o2P3q4R5s6", pair.refreshToken)
        assertEquals(1795000000L, pair.refreshExpire)
        assertEquals("12217924", pair.uid)
        assertEquals("Login/RefreshToken", pair.endpoint)
        assertEquals(4242L, pair.capturedAt)
    }

    @Test
    fun plainObjectLoginResponseYieldsTokenPair() {
        val data = JSONObject().apply {
            put("uid", 12217924)
            put("access_token", "ACCESS-PLAIN")
            put("refresh_token", "REFRESH-PLAIN")
        }
        val envelope = JSONObject().apply {
            put("status", "1")
            put("data", data)
        }
        val pair = CaptureExtractor.extractTokenPair("Login/quickLoginV339", 200, envelope.toString(), 1L)
        assertNotNull(pair)
        assertEquals("ACCESS-PLAIN", pair?.accessToken)
        assertEquals("REFRESH-PLAIN", pair?.refreshToken)
        assertNull("refresh_expire is optional", pair?.refreshExpire)
    }

    @Test
    fun tokenAliasIsAcceptedOnlyWithoutAccessToken() {
        val aliased = JSONObject().apply {
            put("status", 1)
            put("data", JSONObject().put("uid", "1").put("token", "TOKEN-ALIAS").put("refresh_token", "R"))
        }
        assertEquals("TOKEN-ALIAS", CaptureExtractor.extractTokenPair("Login/LoginV14", 200, aliased.toString(), 1L)?.accessToken)

        val preferred = JSONObject().apply {
            put("status", 1)
            put(
                "data",
                JSONObject().put("uid", "1").put("token", "TOKEN-ALIAS")
                    .put("access_token", "ACCESS-CANONICAL").put("refresh_token", "R"),
            )
        }
        assertEquals("ACCESS-CANONICAL", CaptureExtractor.extractTokenPair("Login/LoginV14", 200, preferred.toString(), 1L)?.accessToken)
    }

    @Test
    fun incompleteOrUnrelatedResponsesDoNotYieldAPair() {
        val missingRefresh = JSONObject().apply {
            put("status", 1)
            put("data", JSONObject().put("uid", "1").put("access_token", "A"))
        }
        assertNull(CaptureExtractor.extractTokenPair("Login/RefreshToken", 200, missingRefresh.toString(), 1L))

        val businessFailure = JSONObject().apply {
            put("status", 0)
            put("data", JSONObject().put("access_token", "A").put("refresh_token", "R"))
        }
        assertNull(CaptureExtractor.extractTokenPair("Login/RefreshToken", 200, businessFailure.toString(), 1L))

        val full = JSONObject().apply {
            put("status", 1)
            put("data", JSONObject().put("access_token", "A").put("refresh_token", "R"))
        }
        assertNull("HTTP failures are not login material", CaptureExtractor.extractTokenPair("Login/RefreshToken", 502, full.toString(), 1L))
        assertNull("non-login endpoints never yield refresh tokens", CaptureExtractor.extractTokenPair(Endpoints.BEFORE_RUN, 200, full.toString(), 1L))
        assertNull(CaptureExtractor.extractTokenPair("Login/RefreshToken", 200, "not json", 1L))
    }

    @Test
    fun endpointAndFormParsingMatchTheWireShape() {
        assertEquals(
            "Run2/beforeRunV260",
            CaptureExtractor.endpointOf("/v3/api.php/Run2/beforeRunV260?x=1&y=2"),
        )
        assertEquals("User/User", CaptureExtractor.endpointOf("https://api2.lptiyu.com/v3/api.php/User/User"))
        assertEquals("User/User", CaptureExtractor.endpointOf("/v3/api.php/User/User"))

        val form = CaptureExtractor.parseFormBody("a=1&b=hello+world&c=%E4%B8%AD%E6%96%87&d&e=")
        assertEquals("1", form["a"])
        assertEquals("hello world", form["b"])
        assertEquals("中文", form["c"])
        assertEquals("", form["d"])
        assertEquals("", form["e"])
        assertFalse(CaptureExtractor.hasIdentityFields(form))
    }

    @Test
    fun cookiesAreParsedFromRequestAndResponseHeaders() {
        val requestCookies = CaptureExtractor.parseCookieHeader("PHPSESSID=abc123; theme=dark; empty=")
        assertEquals(3, requestCookies.size)
        assertEquals("PHPSESSID", requestCookies[0].name)
        assertEquals("abc123", requestCookies[0].value)
        assertEquals("api2.lptiyu.com", requestCookies[0].domain)

        val changed = requireNotNull(
            CaptureExtractor.parseSetCookie("PHPSESSID=def456; Path=/v3; Domain=.lptiyu.com; Max-Age=3600; Secure; HttpOnly"),
        )
        assertEquals("PHPSESSID", changed.name)
        assertEquals("def456", changed.value)
        assertEquals("/v3", changed.path)
        assertEquals("lptiyu.com", changed.domain)
        assertTrue(changed.secure)
        assertNotNull(changed.expires)
    }
}
