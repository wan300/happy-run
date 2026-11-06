package com.lepao.companion.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/**
 * Byte-for-byte parity against the Python reference implementation used to validate the live
 * capture. The fixture contains only synthetic identity values.
 */
class ProtocolReferenceTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.getResourceAsStream("/core_protocol_reference.json")
            ?: error("missing core_protocol_reference.json")
        JSONObject(stream.reader(Charsets.UTF_8).readText())
    }

    private val identity: Identity by lazy {
        val source = fixture.getJSONObject("identity")
        val wire = LinkedHashMap<String, String>()
        COMMON_FIELDS.forEach { wire[it] = source.getString(it) }
        Identity(wire, source.getString("umid"))
    }

    private val timestamp: Long by lazy { fixture.getLong("timestamp") }
    private val nonce: String by lazy { fixture.getString("nonce") }
    private val timeStr: String by lazy { fixture.getString("time_str") }

    private fun extras(endpoint: String): Map<String, Any?> = when (endpoint) {
        "Run2/beforeRunV260", "report/getOssSts" -> emptyMap()
        "Run/getTimestampV278" -> mapOf("game_id" to "158", "point_ids" to "24560,2894")
        "Run/setRunLocationRecord" -> mapOf(
            "latitude" to "39.9518",
            "longitude" to "116.3452",
            "game_id" to "158",
            "point_ids" to "24560,2894",
        )
        "Run/stopRunV278" -> mapOf(
            "term_id" to "13281",
            "game_id" to "158",
            "start_time" to "1793778542",
            "end_time" to "1793779283",
            "used_time" to "741",
            "distance" to "2.03",
            "step_num" to "1690",
            "file_img" to "Public/Upload/pic/run_photo_screenshotImage/x.png",
            "record_file" to "Public/Upload/file/run_record/x.txt",
            "record_img" to "",
            "log_data" to "[{\"distance\":0.65,\"latitude\":39.9518,\"longtitude\":116.3452,\"point_id\":24560,\"time\":1793778564}]",
            "step_info" to "{\"interval\":60,\"list\":[1690]}",
            "run_check_res" to "[]",
            "pass_total" to 366,
            "pass_effective_num" to 0,
            "is_running_area_valid" to "1",
            "ip" to "192.168.1.20",
            "record_str" to Protocol.recordSignature("12", "12217924", timeStr),
            "gee_token" to "",
            "newMobileDeviceId" to "",
            "run_face_verify_id" to "1231231231231",
            "is_timeout_zfb" to 0,
        )
        "Run2/gyroscope" -> mapOf(
            "record_id" to "259921",
            "gyroscope_file" to "Public/Upload/file/run_gyroscope/x.txt",
        )
        "Run/recordDetailV270" -> mapOf("record_id" to "259921")
        "Login/RefreshToken" -> mapOf("access_token" to "A".repeat(32), "refresh_token" to "R".repeat(32))
        else -> error("unexpected endpoint $endpoint")
    }

    @Test
    fun everyEndpointMatchesThePythonReferenceByteForByte() {
        val cases = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val endpoint = case.getString("endpoint")
            val request = Protocol.buildRequest(
                endpoint,
                identity,
                extras(endpoint),
                timestamp,
                nonce = nonce,
                timeStr = if (endpoint == Endpoints.STOP) timeStr else null,
            )
            val expectedFields = case.getJSONObject("fields")
            assertEquals("$endpoint field count", expectedFields.length(), request.fields.size)
            val keys = expectedFields.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                assertEquals("$endpoint field $key", expectedFields.getString(key), Protocol.javaString(request.fields[key]))
            }
            val expectedBody = Base64.getDecoder().decode(case.getString("body_b64"))
            assertEquals("$endpoint body", expectedBody.toList(), request.body.toList())
            if (case.has("encrypted_plaintext")) {
                val form = String(request.body, Charsets.UTF_8)
                val key = form.split('&').first { it.startsWith("key=") }.substring(4)
                val plaintext = Protocol.decryptText(URLDecoder.decode(key, "UTF-8"), "PARAMS_NEW_GAME")
                assertEquals("$endpoint encrypted plaintext", case.getString("encrypted_plaintext"), plaintext)
            }
        }
    }

    @Test
    fun derivedSignatureAndDynamicValuesMatch() {
        val signature = fixture.getJSONObject("record_signature")
        assertEquals(
            signature.getString("expected"),
            Protocol.recordSignature(signature.getString("school_id"), signature.getString("uid"), signature.getString("time_str")),
        )
        val dynamic = fixture.getJSONObject("dynamic_value")
        assertEquals(
            dynamic.getString("expected"),
            Protocol.dynamicValue(dynamic.getString("timestamp"), dynamic.getString("umid")),
        )
        val mime = fixture.getJSONObject("mime_b64")
        assertEquals(mime.getString("expected"), Protocol.b64Mime(mime.getString("input").toByteArray()))
    }

    @Test
    fun formEncodingMatchesPythonQuotePlus() {
        val encoded = Protocol.formEncode(
            linkedMapOf(
                "data" to "a b+c/d=\u0001e\nf中文",
                "key" to "AAA+/==",
            ),
        )
        assertEquals("data=a+b%2Bc%2Fd%3D%01e%0Af%E4%B8%AD%E6%96%87&key=AAA%2B%2F%3D%3D", encoded)
    }
}
