package com.lepao.companion.qa

import com.lepao.companion.core.COMMON_FIELDS
import com.lepao.companion.core.Identity
import com.lepao.companion.core.Protocol
import com.lepao.companion.core.ProtocolException
import com.lepao.companion.core.WireRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Byte-exact parity against the PC reference implementation.
 *
 * The fixtures are produced by `android/tools/gen_fixtures.py` from
 * `lepao_client/protocol.py` with the fixed synthetic identity/timestamp/nonce from
 * `docs/verification.md`. Nothing here touches Android APIs, so this runs as a plain JVM test.
 */
class ProtocolGoldenTest {

    private val fixture: JSONObject by lazy { loadFixture() }
    private val meta: JSONObject by lazy { fixture.getJSONObject("meta") }
    private val identity: Identity by lazy {
        val source = meta.getJSONObject("identity")
        Identity.fromWireMap(
            COMMON_FIELDS.associateWith { source.getString(it) },
            meta.getString("umid"),
        )
    }
    private val timestamp: Long by lazy { meta.getLong("timestamp") }
    private val nonce: String by lazy { meta.getString("nonce") }

    @Test
    fun fixtureCoversEveryDocumentedEndpoint() {
        val expected = listOf(
            "Run2/beforeRunV260",
            "Run/getTimestampV278",
            "Run/setRunLocationRecord",
            "report/getOssSts",
            "Run/stopRunV278",
            "Run2/gyroscope",
            "Run/recordDetailV270",
            "Login/RefreshToken",
        )
        val cases = fixture.getJSONArray("requests")
        val actual = (0 until cases.length()).map { cases.getJSONObject(it).getString("endpoint") }
        assertEquals("fixture endpoint set", expected, actual)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val contract = com.lepao.companion.core.Endpoints.contract(case.getString("endpoint"))
            assertEquals(
                "contract encrypted @${case.getString("endpoint")}",
                contract.encrypted,
                case.getBoolean("encrypted"),
            )
        }
    }

    @Test
    fun everyRequestMatchesReferenceFieldsAndBodyBytes() {
        val cases = fixture.getJSONArray("requests")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val endpoint = case.getString("endpoint")
            val context = "@$name ($endpoint)"

            val request = rebuild(case)
            assertEquals("endpoint $context", endpoint, request.endpoint)

            val expectedFields = case.getJSONObject("fields")
            val expectedKeys = expectedFields.keys().asSequence().toList()
            assertEquals("field key set $context", expectedKeys.toSet(), request.fields.keys.toSet())
            assertEquals("field order $context", stringList(case.getJSONArray("field_order")), request.fields.keys.toList())
            for (key in expectedKeys) {
                assertEquals(
                    "field '$key' $context",
                    Protocol.javaString(expectedFields.get(key)),
                    Protocol.javaString(request.fields[key]),
                )
            }

            val expectedBody = Base64.getDecoder().decode(case.getString("body_b64"))
            assertEquals("body ascii text $context", case.getString("body"), String(request.body, StandardCharsets.US_ASCII))
            assertArrayEquals("body bytes $context", expectedBody, request.body)
        }
    }

    @Test
    fun encryptedRequestsDecryptToReferencePlaintext() {
        var checked = 0
        val cases = fixture.getJSONArray("requests")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (!case.getBoolean("encrypted")) continue
            checked++
            val context = "@${case.getString("name")} (${case.getString("endpoint")})"
            val request = rebuild(case)
            val form = decodeForm(String(request.body, StandardCharsets.US_ASCII))
            assertEquals("encrypted form has only key $context", setOf("key"), form.keys)
            assertTrue(
                "encrypted body is exactly one key form $context",
                String(request.body, StandardCharsets.US_ASCII).startsWith("key="),
            )
            assertEquals(
                "PARAMS_NEW_GAME plaintext $context",
                case.getString("encrypted_plaintext"),
                Protocol.decryptText(form.getValue("key"), "PARAMS_NEW_GAME"),
            )
        }
        assertEquals("encrypted endpoint count", 4, checked)
    }

    @Test
    fun plaintextRequestsSerializeEveryFieldAsForm() {
        val cases = fixture.getJSONArray("requests")
        var checked = 0
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getBoolean("encrypted")) continue
            checked++
            val context = "@${case.getString("name")} (${case.getString("endpoint")})"
            val request = rebuild(case)
            val actual = decodeForm(String(request.body, StandardCharsets.US_ASCII))
            val expected = case.getJSONObject("form")
            val expectedKeys = expected.keys().asSequence().toList()
            assertEquals("form key set $context", expectedKeys.toSet(), actual.keys)
            for (key in expectedKeys) {
                assertEquals("form '$key' $context", expected.getString(key), actual[key])
            }
        }
        assertEquals("plaintext endpoint count", 4, checked)
    }

    @Test
    fun stopRequestCarriesTheThirtyEightFieldShape() {
        val stop = caseNamed("stop")
        val fields = stop.getJSONObject("fields")
        assertEquals("stop field count", 38, fields.length())
        assertEquals("dynamic field key", "lpd3", stringList(stop.getJSONArray("field_order")).last { it.startsWith("lp") })
        assertEquals("timeStr", meta.getString("formal_start_str"), fields.getString("lpd3"))
        assertEquals("record_str", stop.getJSONObject("extra").getString("record_str"), fields.getString("record_str"))
        assertTrue(
            "log_data stays a compact JSON string",
            fields.getString("log_data").startsWith("[{\"distance\":640.0,"),
        )
    }

    @Test
    fun formEncodeMatchesQuotePlus() {
        val vectors = fixture.getJSONObject("vectors").getJSONArray("form_encode")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val input = LinkedHashMap<String, String>()
            val pairs = vector.getJSONArray("input")
            for (pairIndex in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(pairIndex)
                input[pair.getString(0)] = pair.getString(1)
            }
            assertEquals(
                "formEncode ${vector.getString("name")}",
                vector.getString("expected"),
                Protocol.formEncode(input),
            )
        }
    }

    @Test
    fun recordSignatureMatchesReferenceAndRejectsNegativeDomain() {
        val vectors = fixture.getJSONObject("vectors").getJSONArray("record_signature")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            assertEquals(
                "recordSignature uid=${vector.getString("uid")}",
                vector.getString("expected"),
                Protocol.recordSignature(
                    vector.getString("school_id"),
                    vector.getString("uid"),
                    vector.getString("time_str"),
                ),
            )
        }
        val errors = fixture.getJSONObject("vectors").getJSONArray("record_signature_errors")
        for (index in 0 until errors.length()) {
            val vector = errors.getJSONObject(index)
            assertThrows(
                "recordSignature must reject ${vector.getString("reason")}",
                ProtocolException::class.java,
            ) {
                Protocol.recordSignature(
                    vector.getString("school_id"),
                    vector.getString("uid"),
                    vector.getString("time_str"),
                )
            }
        }
    }

    @Test
    fun dynamicNameAndValueMatchReference() {
        val vectors = fixture.getJSONObject("vectors")
        val names = vectors.getJSONArray("dynamic_name")
        for (index in 0 until names.length()) {
            val vector = names.getJSONObject(index)
            assertEquals(
                "dynamicName ${vector.getString("model")}",
                vector.getString("expected"),
                Protocol.dynamicName(vector.getString("model"), vector.getString("timestamp")),
            )
        }
        val nameErrors = vectors.getJSONArray("dynamic_name_errors")
        for (index in 0 until nameErrors.length()) {
            val vector = nameErrors.getJSONObject(index)
            assertThrows(
                "dynamicName must reject ${vector.getString("reason")}",
                ProtocolException::class.java,
            ) {
                Protocol.dynamicName(vector.getString("model"), vector.getString("timestamp"))
            }
        }
        val values = vectors.getJSONArray("dynamic_value")
        for (index in 0 until values.length()) {
            val vector = values.getJSONObject(index)
            assertEquals(
                "dynamicValue ${vector.getString("timestamp")}",
                vector.getString("expected"),
                Protocol.dynamicValue(vector.getString("timestamp"), vector.getString("umid")),
            )
        }
    }

    @Test
    fun base64MimeMatchesPythonEncodebytes() {
        val vectors = fixture.getJSONObject("vectors").getJSONArray("b64_mime")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val input = if (vector.has("input_hex")) hexToBytes(vector.getString("input_hex"))
            else vector.getString("input_utf8").toByteArray(StandardCharsets.UTF_8)
            val expected = vector.getString("expected")
            val actual = Protocol.b64Mime(input)
            assertEquals("b64Mime ${vector.getString("name")} encoding", expected, actual)
            if (expected.isNotEmpty()) {
                assertTrue(
                    "b64Mime ${vector.getString("name")} ends with newline",
                    actual.endsWith("\n"),
                )
                for (line in actual.split("\n").dropLast(1)) {
                    assertTrue(
                        "b64Mime ${vector.getString("name")} line width (${line.length})",
                        line.length <= 76,
                    )
                }
            }
        }
    }

    @Test
    fun jsonQuoteAndMd5MatchReference() {
        val vectors = fixture.getJSONObject("vectors")
        val quotes = vectors.getJSONArray("json_quote")
        for (index in 0 until quotes.length()) {
            val vector = quotes.getJSONObject(index)
            assertEquals(
                "jsonQuote ${vector.getString("input")}",
                vector.getString("expected"),
                Protocol.jsonQuote(vector.getString("input")),
            )
        }
        val md5 = vectors.getJSONArray("md5")
        for (index in 0 until md5.length()) {
            val vector = md5.getJSONObject(index)
            assertEquals("md5 ${vector.getString("input")}", vector.getString("expected"), Protocol.md5Hex(vector.getString("input")))
        }
    }

    @Test
    fun decodeResponseHandlesPlainEncryptedAndFailureEnvelopes() {
        val responses = fixture.getJSONObject("responses")
        val payload = responses.getJSONObject("payload")

        val plain = Protocol.decodeResponse(JSONObject(responses.getJSONObject("plain").toString()))
        assertJsonEquivalent(payload, plain.getJSONObject("data"), "plain data")
        assertEquals("plain status", 1, plain.getInt("status"))
        assertEquals("plain msg", "ok", plain.getString("msg"))

        val encrypted = Protocol.decodeResponse(JSONObject(responses.getJSONObject("encrypted").toString()))
        val encryptedData = encrypted.get("data")
        assertTrue("encrypted data must decode to an object, got ${encryptedData.javaClass}", encryptedData is JSONObject)
        assertJsonEquivalent(payload, encryptedData as JSONObject, "encrypted data")

        for (key in listOf("failure", "failure_string_status", "failure_missing_status", "undecryptable")) {
            val envelope = responses.getJSONObject(key)
            assertThrows(
                "decodeResponse must reject '$key'",
                ProtocolException::class.java,
            ) {
                Protocol.decodeResponse(JSONObject(envelope.toString()))
            }
        }
    }

    @Test
    fun tryDecryptEnvelopeDataIsBestEffort() {
        val responses = fixture.getJSONObject("responses")
        val payload = responses.getJSONObject("payload")
        val plain = Protocol.tryDecryptEnvelopeData(responses.getJSONObject("plain"))
        assertJsonEquivalent(payload, plain!!, "plain tryDecrypt")
        val encrypted = Protocol.tryDecryptEnvelopeData(responses.getJSONObject("encrypted"))
        assertJsonEquivalent(payload, encrypted!!, "encrypted tryDecrypt")
        assertNull("invalid ciphertext is not decrypted", Protocol.tryDecryptEnvelopeData(responses.getJSONObject("undecryptable")))
    }

    // --- helpers -------------------------------------------------------------------------

    private fun loadFixture(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/protocol_golden.json")
            ?: throw AssertionError("Missing test resource fixtures/protocol_golden.json; run android/tools/gen_fixtures.py")
        val text = stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        return JSONObject(text)
    }

    private fun caseNamed(name: String): JSONObject {
        val cases = fixture.getJSONArray("requests")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getString("name") == name) return case
        }
        throw AssertionError("No fixture case named $name")
    }

    private fun rebuild(case: JSONObject): WireRequest {
        val extra = LinkedHashMap<String, Any?>()
        val order = case.getJSONArray("extra_order")
        val source = case.getJSONObject("extra")
        for (index in 0 until order.length()) {
            val key = order.getString(index)
            extra[key] = unwrap(source.get(key))
        }
        val timeStr = if (case.isNull("time_str")) null else case.getString("time_str")
        return Protocol.buildRequest(
            case.getString("endpoint"),
            identity,
            extra,
            timestamp,
            nonce,
            timeStr,
        )
    }

    private fun unwrap(value: Any?): Any? = if (value == JSONObject.NULL) null else value

    private fun stringList(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    private fun decodeForm(body: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        if (body.isEmpty()) return values
        for (pair in body.split("&")) {
            val separator = pair.indexOf('=')
            if (separator < 0) {
                values[URLDecoder.decode(pair, "UTF-8")] = ""
            } else {
                values[URLDecoder.decode(pair.substring(0, separator), "UTF-8")] =
                    URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }
        }
        return values
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun assertJsonEquivalent(expected: JSONObject, actual: JSONObject, label: String) {
        val expectedKeys = expected.keys().asSequence().toSet()
        val actualKeys = actual.keys().asSequence().toSet()
        assertEquals("$label key set", expectedKeys, actualKeys)
        for (key in expectedKeys) {
            val expectedValue = expected.get(key)
            val actualValue = actual.get(key)
            when {
                expectedValue is JSONObject -> assertJsonEquivalent(
                    expectedValue,
                    actualValue as? JSONObject ?: throw AssertionError("$label '$key' is not an object"),
                    "$label.$key",
                )
                expectedValue is JSONArray -> assertEquals("$label '$key' array", expectedValue.toString(), (actualValue as JSONArray).toString())
                expectedValue is Number && actualValue is Number -> assertEquals(
                    "$label '$key' number",
                    expectedValue.toDouble(),
                    actualValue.toDouble(),
                    0.0,
                )
                else -> assertEquals("$label '$key'", expectedValue, actualValue)
            }
        }
    }
}
