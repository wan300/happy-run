package com.lepao.companion.run

import com.lepao.companion.core.TimeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Planner tests: window gates, unsupported branches, fixed-profile clamping and checkpoint picks. */
class AutoPlannerTest {

    private val wednesdayAt18 = ZonedDateTime.of(2026, 11, 4, 18, 0, 0, 0, ZoneOffset.ofHours(8)).toEpochSecond()
    private val allWeek: Set<String> = (0..6).map { it.toString() }.toSet()

    private fun rule(
        minDistance: Double = 1.0,
        maxDistance: Double = 3.0,
        minPace: Double = 300.0,
        maxPace: Double = 420.0,
        minLogNum: Int = 2,
        start: Int = 0,
        end: Int = 86400,
    ) = TimeRule(
        startTime = start,
        endTime = end,
        minLogNum = minLogNum,
        minDistanceKm = minDistance,
        maxDistanceKm = maxDistance,
        minPaceSecPerKm = minPace,
        maxPaceSecPerKm = maxPace,
    )

    private fun request(
        nowEpoch: Long = wednesdayAt18,
        weekTime: Set<String> = allWeek,
        runStart: Int = 6 * 3600,
        runEnd: Int = 22 * 3600,
        timeRules: List<TimeRule> = listOf(rule()),
        checkpoints: List<GeoPoint> = defaultCheckpoints(),
        pointNumOnline: Int = 2,
        configBlockers: List<String> = emptyList(),
        unsupportedFlags: List<String> = emptyList(),
    ) = PlanRequest(
        nowEpoch = nowEpoch,
        schoolId = "1001",
        identitySchoolId = "1001",
        configBlockers = configBlockers,
        unsupportedFlags = unsupportedFlags,
        weekTime = weekTime,
        runStartTime = runStart,
        runEndTime = runEnd,
        timeRules = timeRules,
        checkpoints = checkpoints,
        pointNumOnline = pointNumOnline,
        seed = 42L,
    )

    /** Dense synthetic corridor: 2 s samples along a straight line, arc ≈ 2 m/s. */
    private fun track(durationSec: Int): List<TrackPoint> {
        val samples = ArrayList<TrackPoint>()
        var t = 0.0
        while (t <= durationSec) {
            samples.add(TrackPoint(t, 30.0 + t * 0.00001, 120.0, t * 2.0))
            t += 2.0
        }
        return samples
    }

    private fun defaultCheckpoints(): List<GeoPoint> = listOf(
        GeoPoint("24560", 30.0 + 20 * 0.00001, 120.0),
        GeoPoint("2894", 30.0 + 200 * 0.00001, 120.0),
    )

    private fun evaluate(request: PlanRequest): PlanResult =
        PlanEvaluator.evaluate(request) { duration, _, _ -> track(duration) }

    @Test
    fun outsideEntryWindowBlocks() {
        val late = ZonedDateTime.of(2026, 11, 4, 23, 0, 0, 0, ZoneOffset.ofHours(8)).toEpochSecond()
        val result = evaluate(request(nowEpoch = late))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("时间窗") })
    }

    @Test
    fun entryClosesAt22EvenWhenServerSays23() {
        val cutoff = ZonedDateTime.of(2026, 11, 4, 22, 0, 0, 0, ZoneOffset.ofHours(8)).toEpochSecond()
        assertTrue(evaluate(request(nowEpoch = cutoff - 1, runEnd = 23 * 3600)).ok)
        val atCutoff = evaluate(request(nowEpoch = cutoff, runEnd = 23 * 3600))
        assertFalse(atCutoff.ok)
        assertTrue(atCutoff.blockers.any { it.contains("22:00") })
        assertFalse(evaluate(request(nowEpoch = cutoff + 1800, runEnd = 23 * 3600)).ok)
    }

    @Test
    fun earlierServerCutoffIsNotExtended() {
        val at21 = ZonedDateTime.of(2026, 11, 4, 21, 0, 0, 0, ZoneOffset.ofHours(8)).toEpochSecond()
        assertFalse(evaluate(request(nowEpoch = at21, runEnd = 21 * 3600)).ok)
    }

    @Test
    fun displayedRulesAndPersistedRulesUse22Cutoff() {
        val raw = org.json.JSONObject().put("run_end_time", 23 * 3600)
        val rules = com.lepao.companion.core.RunRules(raw)
        assertEquals(22 * 3600, rules.runEndTime)
        assertEquals("raw server data remains available for auditing", 23 * 3600, rules.raw.getInt("run_end_time"))
        assertEquals(22 * 3600, com.lepao.companion.core.RunRules.parse(rules.rawJson).runEndTime)
        assertEquals(0, com.lepao.companion.core.RunRules(org.json.JSONObject()).runEndTime)
    }

    @Test
    fun wrongWeekdayBlocks() {
        val result = evaluate(request(weekTime = setOf("1", "2")))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("星期") })
    }

    @Test
    fun unsupportedBranchBlocks() {
        val result = evaluate(request(unsupportedFlags = listOf("is_check_face")))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("is_check_face") })
    }

    @Test
    fun configBlockersFromBeforeRunBlock() {
        val result = evaluate(request(configBlockers = listOf("配置缺少 game_id / term_id")))

        assertFalse(result.ok)
        assertEquals(listOf("配置缺少 game_id / term_id"), result.blockers)
    }

    @Test
    fun ambiguousTimeRuleBlocks() {
        val overlapping = listOf(rule(start = 0, end = 86400), rule(start = 12 * 3600, end = 23 * 3600))
        val result = evaluate(request(timeRules = overlapping))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("时段规则") })
    }

    @Test
    fun distanceIsClampedInsideSchoolWindow() {
        val result = evaluate(request(timeRules = listOf(rule(minDistance = 2.5, maxDistance = 3.0))))

        assertTrue(result.ok)
        val plan = result.plan!!
        assertEquals("2.51", plan.distanceKm)
        assertEquals(1, result.warnings.count { it.contains("里程") })
        assertEquals(2, plan.points.size)
    }

    @Test
    fun paceIsClampedInsideSchoolWindow() {
        val result = evaluate(request(timeRules = listOf(rule(minPace = 300.0, maxPace = 360.0))))

        assertTrue(result.ok)
        val plan = result.plan!!
        assertEquals(359, (plan.durationSec / plan.distanceValue).toInt())
        assertEquals(1, result.warnings.count { it.contains("配速") })
        assertEquals(round(plan.distanceValue * 359.0), plan.durationSec)
    }

    @Test
    fun planFieldsFollowTheVerifiedProfile() {
        val result = evaluate(request())

        assertTrue(result.ok)
        val plan = result.plan!!
        assertEquals("2.03", plan.distanceKm)
        assertEquals(741, plan.durationSec)
        assertEquals(1690, plan.stepNum)
        assertEquals(60, plan.stepInterval)
        assertEquals(maxOf(60, track(741).size - 5), plan.passTotal)
        assertEquals(listOf("24560", "2894"), plan.pointIds)
        assertEquals(0.65, plan.points.first().distance, 0.0)
        assertTrue(plan.points.all { it.checkinT >= 5.0 && it.checkinT <= plan.durationSec - 5.0 })
        assertTrue(plan.points.zipWithNext().all { (a, b) -> b.checkinT - a.checkinT >= 30.0 })
        assertTrue(plan.points.all { it.id in setOf("24560", "2894") })
    }

    @Test
    fun tooFewConfiguredPointsBlocks() {
        val result = evaluate(
            request(checkpoints = listOf(GeoPoint("1", 30.0002, 120.0))),
        )

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("打卡点位不足") })
    }

    @Test
    fun checkpointsOutsideCorridorBlock() {
        val far = listOf(
            GeoPoint("1", 31.0, 120.0),
            GeoPoint("2", 31.1, 120.1),
        )
        val result = evaluate(request(checkpoints = far))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("点位与轨迹不匹配") && it.contains("20 m") })
    }

    @Test
    fun checkpointBeyondTwentyMetresIsRejected() {
        // 25 m east of the synthetic line (1 deg of longitude is ~96.4 km at 30 deg latitude).
        val offRoute = listOf(
            GeoPoint("1", 30.0002, 120.0 + 25.0 / 96_400.0),
            GeoPoint("2", 30.0020, 120.0 + 25.0 / 96_400.0),
        )

        val result = evaluate(request(checkpoints = offRoute))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("点位与轨迹不匹配") })
    }

    @Test
    fun checkpointInsideTwentyMetresIsAccepted() {
        // 18 m east of the synthetic line: inside the required check-in radius.
        val nearRoute = listOf(
            GeoPoint("1", 30.0002, 120.0 + 18.0 / 96_400.0),
            GeoPoint("2", 30.0020, 120.0 + 18.0 / 96_400.0),
        )

        val result = evaluate(request(checkpoints = nearRoute))

        assertTrue(result.ok)
        assertEquals(listOf("1", "2"), result.plan!!.pointIds)
    }

    @Test
    fun invalidDistanceIntervalBlocks() {
        val result = evaluate(request(timeRules = listOf(rule(minDistance = 2.0, maxDistance = 2.0))))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("里程区间无效") })
    }

    @Test
    fun tooNarrowPaceIntervalBlocks() {
        val result = evaluate(request(timeRules = listOf(rule(minPace = 364.5, maxPace = 365.5))))

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("配速区间过窄") })
    }

    @Test
    fun schoolMismatchBlocks() {
        val result = PlanEvaluator.evaluate(
            request().copy(identitySchoolId = "9999"),
        ) { duration, _, _ -> track(duration) }

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("学校") })
    }

    @Test
    fun stepSumStaysCloseToDeclaredTotal() {
        val buckets = StepBuckets.of(741, 1690, 42L)

        assertEquals(12, buckets.size)
        assertTrue(buckets.all { it > 0 })
        val total = buckets.sum()
        assertTrue("bucket sum $total must stay near the declared step count", total in 1500..1800)
    }

    @Test
    fun planPersistsTheWaypointsForTrackRebuild() {
        val checkpoints = listOf(
            GeoPoint("1", 30.0002, 120.0),
            GeoPoint("2", 30.0020, 120.0),
            GeoPoint("3", 30.0040, 120.0),
        )
        val result = evaluate(request(checkpoints = checkpoints))

        assertTrue(result.ok)
        val restored = com.lepao.companion.core.PlannedRun.fromJson(result.plan!!.toJson().toString())
        // `needed` is 2, so the planner steers past the two closest checkpoints only.
        assertEquals(2, restored.via.size)
        assertEquals(listOf("1", "2"), restored.via.map { it.id })
        assertEquals(30.0002, restored.via.first().latitude, 0.0)
        assertEquals(120.0, restored.via.first().longitude, 0.0)
    }

    @Test
    fun planWrittenBeforeTheSteeringSliceStillParses() {
        val legacy = org.json.JSONObject()
            .put("version", 1)
            .put("seed", 42L)
            .put("duration", 741)
            .put("distance", "2.03")
            .put("pass_total", 366)
            .put("points", org.json.JSONArray())

        val plan = com.lepao.companion.core.PlannedRun.fromJson(legacy)

        assertEquals(42L, plan.seed)
        assertTrue(plan.via.isEmpty())
    }

    private fun round(value: Double): Int = Math.round(value).toInt()
}
