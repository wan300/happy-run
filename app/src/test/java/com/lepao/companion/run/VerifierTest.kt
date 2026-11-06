package com.lepao.companion.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop/detail acceptance checks and the URL-decoded object-key comparison. */
class VerifierTest {

    private val routeKey = "Public/Upload/file/run_record/2026-11-04/992/e61ff9ad-3dcd-4deb-8b27-905d8017a164.txt"

    private fun detail(
        uid: String = RunFixtures.UID,
        startTime: String = RunFixtures.START_TIME,
        gameId: String = RunFixtures.GAME_ID,
        recordStatus: String = "1",
        distance: Any? = RunFixtures.DISTANCE,
        duration: Any? = RunFixtures.DURATION.toString(),
        stepNum: Any? = RunFixtures.STEPS.toString(),
        file: String = "https://lptiyu-data.oss-cn-hangzhou.aliyuncs.com/$routeKey",
    ) = RecordDetail(uid, startTime, gameId, recordStatus, distance, duration, stepNum, file)

    private fun checks(detail: RecordDetail, routeKey: String? = this.routeKey): Map<String, Boolean> =
        Verifier.detailChecks(
            detail = detail,
            uid = RunFixtures.UID,
            startTime = RunFixtures.START_TIME,
            gameId = RunFixtures.GAME_ID,
            distance = RunFixtures.DISTANCE,
            usedTime = RunFixtures.DURATION,
            stepNum = RunFixtures.STEPS,
            routeKey = routeKey,
        )

    @Test
    fun allEightDetailChecksPass() {
        val result = checks(detail())

        assertEquals(Verifier.DETAIL_CHECK_KEYS, result.keys.toList())
        assertEquals(8, result.size)
        assertTrue(result.values.all { it })
    }

    @Test
    fun singleMismatchFailsOnlyThatCheck() {
        val result = checks(detail(distance = "2.04"))

        assertFalse(result["distance_matches"]!!)
        assertTrue(result["uid_matches"]!!)
        assertTrue(result["duration_matches"]!!)
        assertEquals(1, result.values.count { !it })
    }

    @Test
    fun invalidRecordStatusFails() {
        assertFalse(checks(detail(recordStatus = "0"))["record_valid"]!!)
    }

    @Test
    fun numericComparisonIgnoresWireTypes() {
        assertTrue(checks(detail(distance = 2.03, duration = 741, stepNum = 1690)).values.all { it })
        assertFalse(checks(detail(duration = 742))["duration_matches"]!!)
        assertFalse(checks(detail(stepNum = 1689))["steps_match"]!!)
    }

    @Test
    fun routeKeyIsMatchedAfterUrlDecoding() {
        assertTrue(Verifier.routeKeyMatches("https://oss/x/$routeKey", routeKey))
        assertTrue(Verifier.routeKeyMatches("https://oss/x/$routeKey?Expires=1&Signature=a%2Fb", routeKey))
        val encoded = routeKey.replace("/", "%2F")
        assertTrue(Verifier.routeKeyMatches("https://oss/x/$encoded", routeKey))
        assertTrue(Verifier.routeKeyMatches("$routeKey", routeKey))
        assertFalse(Verifier.routeKeyMatches("https://oss/x/Public/Upload/file/run_record/2026-11-04/992/other.txt", routeKey))
        assertFalse(Verifier.routeKeyMatches("https://oss/x/$routeKey", null))
    }

    @Test
    fun percentDecodeKeepsPlusLiteral() {
        assertEquals("a+b", Verifier.percentDecode("a+b"))
        assertEquals("a/b", Verifier.percentDecode("a%2Fb"))
    }

    @Test
    fun stopChecksCompareIdentityAndMeasurements() {
        val ack = StopAck(
            recordId = "259921",
            uid = RunFixtures.UID,
            startTime = RunFixtures.START_TIME,
            gameId = RunFixtures.GAME_ID,
            distance = "2.03",
            duration = 741,
        )
        val result = Verifier.stopChecks(
            ack,
            RunFixtures.UID,
            RunFixtures.START_TIME,
            RunFixtures.GAME_ID,
            RunFixtures.DISTANCE,
            RunFixtures.DURATION,
        )
        assertEquals(Verifier.STOP_CHECK_KEYS, result.keys.toList())
        assertTrue(result.values.all { it })
    }

    @Test
    fun stopAckWithoutNumericRecordIdIsRejected() {
        assertEquals(null, StopAck.from(mapOf("record_id" to "abc")))
        assertEquals(null, StopAck.from(emptyMap()))
        assertEquals("259921", StopAck.from(mapOf("record_id" to 259921.0))?.recordId)
    }

    @Test
    fun recordDetailParsesWireFields() {
        val parsed = RecordDetail.from(
            mapOf(
                "uid" to 12345,
                "start_time" to 1700000000,
                "game_id" to RunFixtures.GAME_ID,
                "record_status" to 1,
                "distance" to 2.03,
                "time" to 741,
                "step_num" to 1690,
                "file" to "https://oss/x/$routeKey",
            ),
        )!!
        assertEquals(RunFixtures.UID, parsed.uid)
        assertEquals(RunFixtures.START_TIME, parsed.startTime)
        assertEquals("1", parsed.recordStatus)
        assertTrue(com.lepao.companion.core.JsonUtil.numericEqual(parsed.duration, 741))
    }
}
