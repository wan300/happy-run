package com.lepao.companion.run

import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The stop body must stay byte-compatible with the 38-field capture, including the device fields
 * a plain login capture never sees.
 */
class StopFieldsTest {

    private val identity = RunFixtures.identity()
    private val plan = RunFixtures.plan()
    private val timestamp = RunFixtures.START_EPOCH

    private val keys = mapOf(
        ArtifactKinds.SCREENSHOT.name to
            "Public/Upload/pic/run_photo_screenshotImage/2026-11-04/41/stub.png",
        ArtifactKinds.ROUTE.name to
            "Public/Upload/file/run_record/2026-11-04/992/stub.txt",
    )

    private val manifest = FinishManifestBuilder.build(
        sessionId = RunFixtures.SESSION_ID,
        plan = plan,
        startEpoch = RunFixtures.START_EPOCH,
        endEpoch = RunFixtures.START_EPOCH + RunFixtures.DURATION + 2,
        ip = "192.168.1.2",
    )

    /** The exact request_fields set of the 2026-11-02 `Run/stopRunV278` capture (38 names). */
    private val capturedFields = setOf(
        "distance", "end_time", "file_img", "game_id", "gee_token", "ip",
        "is_running_area_valid", "is_timeout_zfb", "log_data", "mac", "mobileDeviceId",
        "mobileModel", "mobileOsVersion", "newMobileDeviceId", "nonce", "oaid", "ostype",
        "pass_effective_num", "pass_total", "record_file", "record_img", "record_str",
        "run_check_res", "run_face_verify_id", "school_id", "start_time", "step_info",
        "step_num", "student_num", "term_id", "timestamp", "token", "uid", "used_time",
        "version", "version_name", "sign",
    )

    private fun requestFields(session: RunSessionInfo): Map<String, Any?> {
        val extra = StopFields.build(identity, session, manifest, keys)
        return Protocol.buildRequest(
            Endpoints.STOP,
            identity,
            extra,
            timestamp,
            timeStr = RunFixtures.FORMAL_STR,
        ).fields
    }

    @Test
    fun capturedDeviceFieldsProduceTheExact38FieldBody() {
        val fields = requestFields(RunFixtures.session())

        assertEquals(38, fields.size)
        val dynamic = Protocol.dynamicName(identity["mobileModel"], timestamp.toString())
        assertEquals(capturedFields + dynamic, fields.keys.toSet())
        assertEquals("oaid-value", fields["oaid"])
        assertEquals("mac-value", fields["mac"])
        assertEquals(RunFixtures.FORMAL_STR, fields[dynamic])
    }

    @Test
    fun missingDeviceFieldsFallBackDeterministically() {
        val session = RunFixtures.session().copy(deviceFields = emptyMap())
        val fields = requestFields(session)

        assertEquals(38, fields.size)
        assertEquals(StopFields.FALLBACK_MAC, fields["mac"])
        val expectedOaid = UUID.nameUUIDFromBytes(
            ("lepao-oaid:" + identity["mobileDeviceId"]).toByteArray(Charsets.UTF_8),
        ).toString()
        assertEquals(expectedOaid, fields["oaid"])
        assertEquals(expectedOaid, StopFields.derivedOaid(identity))
        // Same input, same request: the fallback never drifts between attempts or restarts.
        assertEquals(expectedOaid, requestFields(session)["oaid"])
    }

    @Test
    fun onlyOneMissingDeviceFieldIsBackfilled() {
        val onlyOaid = RunFixtures.session().copy(deviceFields = mapOf("oaid" to "real-oaid"))
        val fields = requestFields(onlyOaid)
        assertEquals("real-oaid", fields["oaid"])
        assertEquals(StopFields.FALLBACK_MAC, fields["mac"])

        val onlyMac = RunFixtures.session().copy(deviceFields = mapOf("mac" to "aa:bb:cc:dd:ee:ff"))
        val other = requestFields(onlyMac)
        assertEquals("aa:bb:cc:dd:ee:ff", other["mac"])
        assertEquals(StopFields.derivedOaid(identity), other["oaid"])
    }

    @Test
    fun recordSignatureMatchesTheProtocolRule() {
        val fields = requestFields(RunFixtures.session())
        assertEquals(
            Protocol.recordSignature(
                identity["school_id"],
                identity["uid"],
                RunFixtures.FORMAL_STR,
            ),
            fields["record_str"],
        )
    }

    @Test
    fun stopBodyCarriesTheMeasuredAndArtifactFields() {
        val fields = requestFields(RunFixtures.session())

        assertEquals(RunFixtures.START_TIME, fields["start_time"])
        assertEquals((RunFixtures.START_EPOCH + RunFixtures.DURATION + 2).toString(), fields["end_time"])
        assertEquals(RunFixtures.DURATION.toString(), fields["used_time"])
        assertEquals(RunFixtures.DISTANCE, fields["distance"])
        assertEquals(RunFixtures.STEPS.toString(), fields["step_num"])
        assertEquals(keys[ArtifactKinds.SCREENSHOT.name], fields["file_img"])
        assertEquals(keys[ArtifactKinds.ROUTE.name], fields["record_file"])
        assertEquals("", fields["record_img"])
        assertEquals("[]", fields["run_check_res"])
        val logData = fields["log_data"] as String
        assertTrue(logData.startsWith("[{"))
        assertTrue(logData.contains("\"point_id\":24560"))
        assertTrue(logData.contains("\"longtitude\":"))
        val stepInfo = fields["step_info"] as String
        assertTrue(stepInfo.startsWith("{\"interval\":60,\"list\":["))
        assertFalse(fields.containsKey("file"))
    }

    @Test
    fun reservedNamesInCapturedFieldsCannotReplaceRequestContext() {
        val polluted = RunFixtures.session().copy(
            deviceFields = mapOf("oaid" to "o", "mac" to "m", "token" to "hijacked", "sign" to "x"),
        )
        val fields = requestFields(polluted)

        assertEquals(38, fields.size)
        assertEquals(identity["token"], fields["token"])
        assertEquals("o", fields["oaid"])
    }
}
