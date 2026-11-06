package com.lepao.companion.run

import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.TimeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local `validate_finish` mirror: the manifest must be refused before any upload is dispatched. */
class ManifestValidatorTest {

    private val plan = RunFixtures.plan()
    private val startEpoch = RunFixtures.START_EPOCH
    private val endEpoch = startEpoch + RunFixtures.DURATION + 2
    private val artifacts = setOf(ArtifactKinds.SCREENSHOT.name, ArtifactKinds.ROUTE.name)

    private fun valid(): FinishManifest = FinishManifestBuilder.build(
        sessionId = RunFixtures.SESSION_ID,
        plan = plan,
        startEpoch = startEpoch,
        endEpoch = endEpoch,
        ip = "192.168.1.2",
    )

    private fun validate(
        manifest: FinishManifest = valid(),
        rule: TimeRule = plan.rule,
        kinds: Set<String> = artifacts,
        now: Long = endEpoch,
    ): ValidationResult = ManifestValidator.validate(
        manifest = manifest,
        sessionId = RunFixtures.SESSION_ID,
        startTime = startEpoch,
        nowEpoch = now,
        plan = plan,
        rule = rule,
        artifactKinds = kinds,
        gyroscopeRequired = false,
    )

    @Test
    fun validManifestPasses() {
        val result = validate()
        assertTrue(result.errors.joinToString("; "), result.ok)
        assertTrue(result.ok)
    }

    @Test
    fun manifestShapeMatchesTheWireSchema() {
        val manifest = valid()
        assertTrue(manifest.logDataJson().startsWith("[{"))
        assertTrue(manifest.logDataJson().contains("\"point_id\":24560"))
        assertTrue(manifest.logDataJson().contains("\"longtitude\":"))
        assertTrue(manifest.stepInfoJson().startsWith("{\"interval\":60,\"list\":["))
        assertTrue(manifest.logData.map { it.pointId }.toSet() == setOf(24560, 2894))
    }

    @Test
    fun distanceOutsideRuleIsRejected() {
        val strict = plan.rule.copy(minDistanceKm = 2.5, maxDistanceKm = 3.0)
        val result = validate(rule = strict)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("里程") })
    }

    @Test
    fun checkinCountMismatchIsRejected() {
        val manifest = valid().copy(logData = valid().logData.take(1))
        val result = validate(manifest = manifest)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("打卡记录数") })
    }

    @Test
    fun checkinTimeOutsideRunIsRejected() {
        val broken = valid().logData.mapIndexed { index, log ->
            if (index == 0) log.copy(time = startEpoch - 100) else log
        }
        val result = validate(manifest = valid().copy(logData = broken))
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("打卡时间") })
    }

    @Test
    fun endTimeInTheFutureIsRejected() {
        val result = validate(now = endEpoch - 60)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("结束时间") })
    }

    @Test
    fun invalidStepBucketsAreRejected() {
        val wrongInterval = validate(manifest = valid().copy(stepInterval = 30))
        assertFalse(wrongInterval.ok)
        assertTrue(wrongInterval.errors.any { it.contains("步数分桶间隔") })

        val negative = validate(manifest = valid().copy(stepBuckets = listOf(-1, 100)))
        assertFalse(negative.ok)
        assertTrue(negative.errors.any { it.contains("步数分桶") })
    }

    @Test
    fun effectiveCheckinsMustReachRuleMinimum() {
        val strict = plan.rule.copy(minLogNum = 3)
        val result = validate(rule = strict)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("有效打卡数") })
    }

    @Test
    fun artifactSetMustMatchConfiguration() {
        val missingRoute = validate(kinds = setOf(ArtifactKinds.SCREENSHOT.name))
        assertFalse(missingRoute.ok)
        assertTrue(missingRoute.errors.any { it.contains("材料集合") })

        val gyroscope = ManifestValidator.validate(
            manifest = valid(),
            sessionId = RunFixtures.SESSION_ID,
            startTime = startEpoch,
            nowEpoch = endEpoch,
            plan = plan,
            rule = plan.rule,
            artifactKinds = artifacts,
            gyroscopeRequired = true,
        )
        assertFalse(gyroscope.ok)
    }

    @Test
    fun invalidIpIsRejected() {
        val result = validate(manifest = valid().copy(ip = "not-an-ip"))
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("IP") })
    }

    @Test
    fun unsignedCheckinsMustCarryZeroCoordinates() {
        val broken = valid().logData.map { it.copy(time = 0, latitude = 30.0) }
        val result = validate(manifest = valid().copy(logData = broken))
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("坐标为") || it.contains("未打卡点") })
    }

    @Test
    fun ipLiteralAcceptsBothFamilies() {
        assertTrue(IpLiteral.isValid("192.168.1.2"))
        assertTrue(IpLiteral.isValid("10.0.0.255"))
        assertTrue(IpLiteral.isValid("fe80::1"))
        assertFalse(IpLiteral.isValid("192.168.1.256"))
        assertFalse(IpLiteral.isValid("192.168.1"))
        assertFalse(IpLiteral.isValid(""))
    }
}
