package com.lepao.companion.run

import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.Protocol
import com.lepao.companion.core.RouteTemplate
import com.lepao.companion.core.RunOptions
import com.lepao.companion.core.RunRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EastCampusTest {
    private val vertices = RouteEngine.parseCorridor(
        File("src/main/assets/${RouteEngine.EAST_CAMPUS_ASSET}").readText(Charsets.UTF_8),
    )
    private val loop = RouteEngine.resample(vertices)

    @Test
    fun savedTemplateReplaysFiveLapsAndEndsAtTheRequestedDistance() {
        val plan = RouteTestSupport.plan(durationSec = 803).copy(
            distanceKm = "2.20",
            routeTemplate = RouteTemplate.EAST_CAMPUS_TRACK,
        )
        val saved = PlannedRun.fromJson(plan.toJson())
        assertEquals(RouteTemplate.EAST_CAMPUS_TRACK, saved.routeTemplate)
        val track = RouteEngine.buildTrack(vertices, saved)
        assertEquals(track, RouteEngine.buildTrack(vertices, plan))
        assertNotEquals(track, RouteEngine.buildTrack(vertices, plan.copy(seed = plan.seed + 1)))
        assertTrue(loop.length in 405.0..411.0)
        assertEquals(5, (track.last().arc / loop.length).toInt())
        assertEquals(803.0, track.last().t, 0.0)
        assertEquals(2200.0, track.last().arc, 0.000001)
        val deltas = track.zipWithNext { a, b -> Geo.distanceM(a.lat, a.lng, b.lat, b.lng) }
        assertEquals(2200.0, deltas.sum(), 2.0)
        assertTrue(deltas.all { it in 0.0..8.0 })
        val body = RunArtifacts.build(vertices, saved, RouteTestSupport.START_EPOCH)
            .getValue(ArtifactKinds.ROUTE.name).body
        val uploaded = JSONArray(Protocol.decryptText(body.toString(Charsets.US_ASCII), "NEW_GAME"))
        assertEquals(track.size, uploaded.length())
        assertEquals(RouteEngine.f32(track.last().lng), uploaded.getJSONObject(track.lastIndex).getDouble("o"), 0.0)

        val old = plan.toJson().apply { remove("route_template") }
        assertEquals(RouteTemplate.LEGACY_CORRIDOR, PlannedRun.fromJson(old).routeTemplate)
    }

    @Test
    fun repeatedVisitsCanMatchNearbyCheckpointsAcrossDifferentLaps() {
        val track = RouteEngine.buildTrack(loop, 803, 42, loopDistanceM = 2200.0)
            .map { TrackPoint(it.t, it.lat, it.lng, it.arc) }
        val checkpoint = track[30]
        val near = listOf(
            GeoPoint("1", checkpoint.lat, checkpoint.lng, mandatory = true),
            GeoPoint("2", checkpoint.lat + 0.00001, checkpoint.lng, mandatory = true),
        )
        val result = CheckpointSelector.select(near, track, 803, needed = 2, repeated = true)
        assertNull(result.error)
        assertEquals(setOf("1", "2"), result.points.map { it.id }.toSet())
        assertTrue(result.points[1].t - result.points[0].t >= 30.0)
        val far = near + GeoPoint("3", checkpoint.lat + 0.01, checkpoint.lng, mandatory = true)
        assertNotNull(CheckpointSelector.select(far, track, 803, needed = 2, repeated = true).error)
    }

    @Test
    fun eastCampusIdComesFromTheServerAndMustBeUnambiguous() {
        val zones = JSONArray().put(JSONObject().put("id", "700").put("name", "主校区"))
            .put(JSONObject().put("id", "701").put("name", "东校区"))
        val raw = JSONObject().put("school_name", "北京交通大学").put("school_id", "12")
            .put("run_zone_id", "700").put("run_zone_name", "主校区").put("run_zone_list", zones)
        val rules = RunRules(raw)
        assertTrue(EastCampusZone.supports(rules, "12"))
        assertFalse(EastCampusZone.supports(rules, "13"))
        assertFalse(EastCampusZone.isSelected(rules))
        assertEquals("701", EastCampusZone.targetId(rules))
        zones.put(JSONObject().put("id", "702").put("name", "东区"))
        assertNull(EastCampusZone.targetId(rules))
        raw.put("run_zone_id", "701").put("run_zone_name", "东校区")
        assertTrue(EastCampusZone.isSelected(RunRules(raw)))
        assertFalse(Endpoints.SET_RUN_ZONE in Endpoints.READ_ONLY)
        assertFalse(Endpoints.contract(Endpoints.SET_RUN_ZONE).newerSignature)
        assertFalse(Endpoints.contract(Endpoints.SET_RUN_ZONE).encrypted)
        assertFalse(Endpoints.contract(Endpoints.SET_RUN_ZONE).dynamic)
        val request = Protocol.buildRequest(
            Endpoints.SET_RUN_ZONE, RunFixtures.identity(), mapOf("run_zone_id" to "701"),
            RunFixtures.START_EPOCH, nonce = "123456",
        )
        assertEquals(14, request.fields.size)
        assertTrue(request.body.toString(Charsets.UTF_8).contains("&run_zone_id=701&sign="))
    }

    @Test
    fun plannerUsesTheEastCampusDistanceAndNeverRequestsADetour() {
        val first = loop.at(100.0)
        val second = loop.at(200.0)
        val request = PlanRequest(
            nowEpoch = RunFixtures.START_EPOCH,
            schoolId = "12",
            identitySchoolId = "12",
            configBlockers = emptyList(),
            unsupportedFlags = emptyList(),
            weekTime = (0..6).map(Int::toString).toSet(),
            runStartTime = 0,
            runEndTime = 86400,
            timeRules = listOf(RunFixtures.RULE),
            checkpoints = listOf(GeoPoint("1", first[0], first[1]), GeoPoint("2", second[0], second[1])),
            pointNumOnline = 2,
            seed = 42,
            routeTemplate = RouteTemplate.EAST_CAMPUS_TRACK,
        )
        val result = PlanEvaluator.evaluate(request) { duration, seed, via, distance ->
            assertTrue(via.isEmpty())
            assertEquals(2.20, distance, 0.0)
            RouteEngine.buildTrack(loop, duration, seed, loopDistanceM = distance * 1000)
                .map { TrackPoint(it.t, it.lat, it.lng, it.arc) }
        }
        assertTrue(result.blockers.toString(), result.ok)
        assertEquals("2.20", result.plan?.distanceKm)
        assertEquals(RouteTemplate.EAST_CAMPUS_TRACK, result.plan?.routeTemplate)
        assertEquals(listOf("1", "2"), result.plan?.pointIds)
    }

    private fun eastRequest(
        customDistanceKm: Double? = null,
        customPaceSecPerKm: Double? = null,
    ): PlanRequest {
        val first = loop.at(100.0)
        val second = loop.at(200.0)
        return PlanRequest(
            nowEpoch = RunFixtures.START_EPOCH,
            schoolId = "12",
            identitySchoolId = "12",
            configBlockers = emptyList(),
            unsupportedFlags = emptyList(),
            weekTime = (0..6).map(Int::toString).toSet(),
            runStartTime = 0,
            runEndTime = 86400,
            timeRules = listOf(RunFixtures.RULE),
            checkpoints = listOf(GeoPoint("1", first[0], first[1]), GeoPoint("2", second[0], second[1])),
            pointNumOnline = 2,
            seed = 42,
            routeTemplate = RouteTemplate.EAST_CAMPUS_TRACK,
            customDistanceKm = customDistanceKm,
            customPaceSecPerKm = customPaceSecPerKm,
        )
    }

    private fun buildLoopTrack(duration: Int, seed: Long, km: Double): List<TrackPoint> =
        RouteEngine.buildTrack(loop, duration, seed, loopDistanceM = km * 1000)
            .map { TrackPoint(it.t, it.lat, it.lng, it.arc) }

    @Test
    fun advancedOptionsChooseDistanceAndPace() {
        var loopKm = 0.0
        var trackLength = 0.0
        val result = PlanEvaluator.evaluate(eastRequest(customDistanceKm = 2.5, customPaceSecPerKm = 330.0)) {
                duration, seed, _, km ->
            loopKm = km
            buildLoopTrack(duration, seed, km).also { track -> trackLength = track.lastOrNull()?.arc ?: 0.0 }
        }

        assertTrue(result.blockers.toString(), result.ok)
        assertEquals("2.50", result.plan?.distanceKm)
        assertEquals(825, result.plan?.durationSec)
        assertEquals(2.5, loopKm, 0.0)
        assertEquals(2500.0, trackLength, 0.001)
        assertEquals(2, result.plan?.points?.size)
        assertEquals(RouteTemplate.EAST_CAMPUS_TRACK, result.plan?.routeTemplate)
    }

    @Test
    fun advancedDistanceMustExceedOneKilometre() {
        val result = PlanEvaluator.evaluate(eastRequest(customDistanceKm = 1.0)) { duration, seed, _, km ->
            buildLoopTrack(duration, seed, km)
        }

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("必须大于 1.00 km") })
    }

    @Test
    fun advancedDistanceOutsideTheSchoolRangeBlocks() {
        val result = PlanEvaluator.evaluate(eastRequest(customDistanceKm = 3.5)) { duration, seed, _, km ->
            buildLoopTrack(duration, seed, km)
        }

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("不在学校区间") })
    }

    @Test
    fun advancedPaceOutsideThreeToNineMinutesBlocks() {
        val result = PlanEvaluator.evaluate(eastRequest(customPaceSecPerKm = 170.0)) { duration, seed, _, km ->
            buildLoopTrack(duration, seed, km)
        }

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("3:00–9:00") })
    }

    @Test
    fun advancedPaceOutsideTheSchoolRangeBlocks() {
        val result = PlanEvaluator.evaluate(eastRequest(customPaceSecPerKm = 250.0)) { duration, seed, _, km ->
            buildLoopTrack(duration, seed, km)
        }

        assertFalse(result.ok)
        assertTrue(result.blockers.any { it.contains("不在学校区间") })
    }

    @Test
    fun advancedOptionTextParsingEnforcesTheBounds() {
        assertEquals(3.0, RunOptions.parseDistance("3.0")!!, 0.0)
        assertNull(RunOptions.parseDistance("1.0"))
        assertNull(RunOptions.parseDistance("x"))

        assertEquals(330.0, RunOptions.parsePace("5:30")!!, 0.0)
        assertEquals(330.0, RunOptions.parsePace("5.5")!!, 0.0)
        assertNull(RunOptions.parsePace("2:59"))
        assertNull(RunOptions.parsePace("9:01"))
        assertEquals("5:30", RunOptions.formatPace(330.0))
    }

    @Test
    fun fenceRejectsSegmentsCrossingAnExcludedArea() {
        // The server serialises vertices as "lng,lat"; keep the fixture in the real order so a
        // swapped parser fails here instead of blocking a live run.
        val raw = JSONObject().put(
            "run_zone_latlng", JSONArray(listOf("116.34,39.94", "116.35,39.94", "116.35,39.95", "116.34,39.95")),
        ).put(
            "run_zone_latlng_invalid",
            JSONArray(listOf("116.344,39.944", "116.346,39.944", "116.346,39.946", "116.344,39.946")),
        )
        val fence = RunFence.from(raw)!!
        val start = TrackPoint(0.0, 39.945, 116.343)
        assertNull(fence.blocker(listOf(start, TrackPoint(2.0, 39.947, 116.343))))
        assertNotNull(fence.blocker(listOf(start, TrackPoint(2.0, 39.945, 116.347))))
        assertNull(RunFence.from(JSONObject()))
    }
}
