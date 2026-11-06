package com.lepao.companion.run

import com.lepao.companion.core.StateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** §6.1/§6.2 of `docs/run-engine.md`: corridor geometry and deterministic track generation. */
class RouteEngineTest {

    private val corridor: RouteEngine.Corridor by lazy { RouteTestSupport.corridor() }

    @Test
    fun corridorAssetHasTheExpectedGeometry() {
        val points = RouteTestSupport.corridorPoints()
        assertEquals("走廊 asset 必须保留 374 个原始点", 374, points.size)
        // The verified record's loop is 2070.8 m; 1% tolerance as required by the spec.
        assertEquals(2070.8, corridor.length, 20.708)
        assertTrue("重采样应细于 0.5 m，实际 ${corridor.size} 个点", corridor.size > 4000)
    }

    @Test
    fun atInterpolatesAndClampsToTheCorridorEnds() {
        val line = RouteEngine.resample(
            listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.001)),
        )
        val total = line.length
        assertTrue("合成走廊长度应约为 111 m，实际 $total", total > 100.0)

        val start = line.at(0.0)
        assertEquals(0.0, start[0], 1e-12)
        assertEquals(0.0, start[1], 1e-12)

        val third = line.at(total / 3.0)
        assertEquals(0.0, third[0], 1e-12)
        assertEquals(0.001 / 3.0, third[1], 1e-12)

        val end = line.at(total)
        assertEquals(0.0, end[0], 1e-12)
        assertEquals(0.001, end[1], 1e-12)

        // Out-of-range distances clamp instead of extrapolating.
        assertEquals(0.0, line.at(-50.0)[1], 1e-12)
        assertEquals(0.001, line.at(total + 50.0)[1], 1e-12)
    }

    @Test
    fun bearingFollowsTheCorridorDirection() {
        val eastward = RouteEngine.resample(
            listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.001)),
        )
        assertEquals(PI / 2.0, eastward.bearing(eastward.length / 2.0), 1e-9)

        val northward = RouteEngine.resample(
            listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.001, 0.0)),
        )
        assertEquals(0.0, northward.bearing(northward.length / 2.0), 1e-9)
    }

    @Test
    fun parseCorridorAcceptsTheWriterEnvelope() {
        val text = """{"kind":"lepao_corridor","points":[[39.9,116.3],[39.9001,116.3001],[39.9002,116.3002]]}"""
        val points = RouteEngine.parseCorridor(text)
        assertEquals(3, points.size)
        assertEquals(39.9001, points[1][0], 0.0)
        assertEquals(116.3002, points[2][1], 0.0)
    }

    @Test
    fun parseCorridorRejectsMalformedPayloads() {
        assertThrows(StateException::class.java) { RouteEngine.parseCorridor("""{"kind":1}""") }
        assertThrows(StateException::class.java) { RouteEngine.parseCorridor("""{"points":[[39.9]]}""") }
        assertThrows(StateException::class.java) { RouteEngine.parseCorridor("""{"points":[[39.9,116.3]]}""") }
    }

    @Test
    fun trackUsesTheDocumentedTwoSecondSampling() {
        val track = RouteTestSupport.track()
        assertEquals("741 s / 2 s + 1 = 371 点", 371, track.size)
        assertEquals(0.0, track.first().t, 0.0)
        assertEquals(740.0, track.last().t, 1e-9)
        track.forEachIndexed { index, sample ->
            assertEquals(index * 2.0, sample.t, 1e-9)
            assertTrue("速度必须为正：${sample.speed}", sample.speed > 0.0)
            assertTrue("速度应在跑步范围内：${sample.speed}", sample.speed > 0.3 && sample.speed < 8.0)
        }
        // The integrated distance covers the corridor once minus the small seed offset.
        val travelled = track.dropLast(1).sumOf { it.speed * RouteEngine.ROUTE_DT }
        assertTrue("单次跑步轨迹应覆盖走廊 2009–2071 m，实际 $travelled", travelled in 2009.0..2071.0)
        assertEquals(minOf(track[track.size - 2].speed, 1.6), track.last().speed, 1e-12)
    }

    @Test
    fun sameSeedProducesTheSameTrack() {
        val first = RouteEngine.buildTrack(corridor, 300, 424242L)
        val second = RouteEngine.buildTrack(corridor, 300, 424242L)
        assertEquals(first, second)
        assertEquals(151, first.size)
    }

    @Test
    fun differentSeedsProduceDifferentTracks() {
        val first = RouteEngine.buildTrack(corridor, 300, 100L)
        val second = RouteEngine.buildTrack(corridor, 300, 101L)
        assertNotEquals(first[0].lat, second[0].lat, 0.0)
        assertNotEquals(first[0].lng, second[0].lng, 0.0)
        assertNotEquals(first.map { it.arc }, second.map { it.arc })
    }

    @Test
    fun seedSelectsDirectionAndStartOffset() {
        // Even seeds traverse the corridor forward, odd seeds backward; the offset moves the start.
        val forward = RouteEngine.buildTrack(corridor, 120, 8L)
        val backward = RouteEngine.buildTrack(corridor, 120, 9L)
        val shifted = RouteEngine.buildTrack(corridor, 120, 16L)
        assertTrue("正向第二个点应沿走廊前进：${forward[1].arc}", forward[1].arc > forward[0].arc)
        assertTrue("反向第二个点应沿走廊后退：${backward[1].arc}", backward[1].arc < backward[0].arc)
        assertTrue("起点偏移应小于 1 m：${forward[0].arc}", forward[0].arc in 0.0..1.0)
        assertEquals(
            "同一偏移桶的正反向起点应关于走廊两端对称",
            forward[0].arc,
            corridor.length - backward[0].arc,
            1e-9,
        )
        assertTrue("起点偏移应随 seed 变化：${forward[0].arc} -> ${shifted[0].arc}", shifted[0].arc > forward[0].arc)
        // One traversal, not a wrapped lap that teleports at the open-corridor seam.
        assertTrue(
            "轨迹应覆盖走廊的大部分：${forward[0].arc} -> ${forward.last().arc}",
            forward.last().arc - forward[0].arc > corridor.length * 0.9,
        )
    }

    @Test
    fun onePassTrackStartsAndEndsOnTheCorridorEnds() {
        // The corridor is an open polyline: one traversal must run from the seed offset to the far
        // end (or back to the near end), never jump across the seam.
        val forward = RouteEngine.buildTrack(corridor, 240, 10L) // even seed: forward
        val backward = RouteEngine.buildTrack(corridor, 240, 11L) // odd seed: backward

        assertEquals(0.06, forward.first().arc, 1e-9)
        assertEquals(corridor.length, forward.last().arc, 1e-6)
        assertEquals(corridor.length - 0.06, backward.first().arc, 1e-9)
        assertEquals(0.0, backward.last().arc, 1e-6)

        for (index in 0 until forward.size - 1) {
            assertTrue(
                "正向轨迹必须单调前进：${forward[index].arc} -> ${forward[index + 1].arc}",
                forward[index + 1].arc > forward[index].arc,
            )
            assertTrue(
                "反向轨迹必须单调后退：${backward[index].arc} -> ${backward[index + 1].arc}",
                backward[index + 1].arc < backward[index].arc,
            )
            assertTrue(
                "2 s 采样不应出现跨接缝跳跃：${forward[index].arc} -> ${forward[index + 1].arc}",
                abs(forward[index + 1].arc - forward[index].arc) < 50.0,
            )
        }

        val corridorEnd = corridor.at(corridor.length)
        val forwardLast = forward.last()
        assertTrue(
            "正向终点必须贴在走廊末端",
            RouteEngine.haversine(corridorEnd[0], corridorEnd[1], forwardLast.lat, forwardLast.lng) < 3.0,
        )
        val corridorStart = corridor.at(0.0)
        val backwardLast = backward.last()
        assertTrue(
            "反向终点必须贴在走廊起点",
            RouteEngine.haversine(corridorStart[0], corridorStart[1], backwardLast.lat, backwardLast.lng) < 3.0,
        )

        // positionAt must agree with the sampled ends in both directions.
        assertEquals(forwardLast, RouteEngine.positionAt(forward, 240.0))
        assertEquals(backward.last(), RouteEngine.positionAt(backward, 240.0))
    }

    @Test
    fun trackStaysNearTheCorridorPolyline() {
        val track = RouteTestSupport.track(durationSec = 240, seed = 33L)
        for (sample in track) {
            val nearest = corridor.at(sample.arc)
            val distance = RouteEngine.haversine(nearest[0], nearest[1], sample.lat, sample.lng)
            assertTrue("横向摆动应小于 3 m，实际 $distance", distance < 3.0)
        }
    }

    @Test
    fun positionAtInterpolatesAndClamps() {
        val track = RouteTestSupport.track(durationSec = 120, seed = 7L)
        assertEquals(track.first(), RouteEngine.positionAt(track, -10.0))
        assertEquals(track.last(), RouteEngine.positionAt(track, 10000.0))

        val from = track[25]
        val to = track[26]
        assertEquals(50.0, from.t, 0.0)
        assertEquals(52.0, to.t, 0.0)
        val middle = RouteEngine.positionAt(track, 51.0)
        assertEquals(51.0, middle.t, 1e-12)
        assertEquals((from.lat + to.lat) / 2.0, middle.lat, 1e-12)
        assertEquals((from.lng + to.lng) / 2.0, middle.lng, 1e-12)
        assertEquals((from.speed + to.speed) / 2.0, middle.speed, 1e-12)
    }

    @Test
    fun positionAtRejectsAnEmptyTrack() {
        assertThrows(StateException::class.java) { RouteEngine.positionAt(emptyList(), 1.0) }
    }

    @Test
    fun trackGenerationStaysInsideTheTimeBudget() {
        RouteEngine.buildTrack(corridor, RouteTestSupport.DURATION, 1L) // warm up
        val started = System.nanoTime()
        repeat(3) { index -> RouteEngine.buildTrack(corridor, RouteTestSupport.DURATION, 100L + index) }
        val averageMs = (System.nanoTime() - started) / 1_000_000.0 / 3.0
        assertTrue("741 s 轨迹平均生成耗时 ${averageMs} ms，应小于 2000 ms", averageMs < 2000.0)
    }

    @Test
    fun f32MatchesFloatWidening() {
        val widened = RouteEngine.f32(116.345276)
        assertNotEquals(116.345276, widened, 0.0)
        assertEquals(116.345276, widened, 1e-6)
        assertTrue(abs(widened - 116.345276) < 1e-6)
    }

    // ------------------------------------------------------- checkpoint steering (§6.2)

    /** Perpendicular waypoint at [arc], [offsetM] metres off the corridor on the +normal side. */
    private fun waypointAt(arc: Double, offsetM: Double): RouteEngine.Waypoint {
        val base = corridor.at(arc)
        val normal = corridor.bearing(arc) + PI / 2.0
        val cosLat = maxOf(0.25, kotlin.math.cos(Math.toRadians(base[0])))
        return RouteEngine.Waypoint(
            base[0] + offsetM * kotlin.math.cos(normal) / 111_320.0,
            base[1] + offsetM * kotlin.math.sin(normal) / 111_320.0 / cosLat,
        )
    }

    private fun minGap(track: List<RouteEngine.Sample>, waypoint: RouteEngine.Waypoint): Double =
        track.minOf { RouteEngine.haversine(waypoint.latitude, waypoint.longitude, it.lat, it.lng) }

    @Test
    fun steeredTrackSweepsPastEveryWaypoint() {
        val waypoints = listOf(
            waypointAt(300.0, 60.0),
            waypointAt(1000.0, 100.0),
            waypointAt(1700.0, 80.0),
        )
        for (seed in listOf(20261104L, 20261105L, 424242L)) {
            val track = RouteEngine.buildTrack(corridor, RouteTestSupport.DURATION, seed, waypoints)
            for (waypoint in waypoints) {
                val gap = minGap(track, waypoint)
                assertTrue(
                    "seed $seed 的轨迹应掠过打卡点，最近样本 $gap m",
                    gap < RouteEngine.VIA_TARGET_M + 6.0,
                )
            }
        }
    }

    @Test
    fun steeredTrackIsDeterministic() {
        val waypoints = listOf(waypointAt(500.0, 80.0), waypointAt(1500.0, 80.0))
        val first = RouteEngine.buildTrack(corridor, 300, 424242L, waypoints)
        val second = RouteEngine.buildTrack(corridor, 300, 424242L, waypoints)
        assertEquals(first, second)
    }

    @Test
    fun waypointAtTheSkippedStartIsStillCovered() {
        // Seed 7992 traverses forward starting ~59.9 m in, i.e. past an arc-10 waypoint; the
        // generator must pull the start offset back so the sweep still happens.
        val waypoint = waypointAt(10.0, 30.0)
        val track = RouteEngine.buildTrack(corridor, 240, 7992L, listOf(waypoint))

        assertTrue(
            "起点偏移必须收回，实际最近样本 ${minGap(track, waypoint)} m",
            minGap(track, waypoint) < 12.0,
        )
    }

    @Test
    fun waypointInsideTargetLeavesTheTrackUntouched() {
        val near = waypointAt(900.0, 2.0)
        val plain = RouteEngine.buildTrack(corridor, 300, 20261104L)
        val steered = RouteEngine.buildTrack(corridor, 300, 20261104L, listOf(near))

        assertEquals(plain, steered)
    }

    @Test
    fun unreachableWaypointIsIgnored() {
        val far = listOf(RouteEngine.Waypoint(31.0, 120.0))
        val plain = RouteEngine.buildTrack(corridor, 300, 20261104L)
        val steered = RouteEngine.buildTrack(corridor, 300, 20261104L, far)

        assertEquals(plain, steered)
    }

    @Test
    fun steeredTrackHasNoTeleports() {
        val waypoints = listOf(
            waypointAt(200.0, 100.0),
            waypointAt(1200.0, 100.0),
            waypointAt(1900.0, 100.0),
        )
        val track = RouteEngine.buildTrack(corridor, RouteTestSupport.DURATION, 20261105L, waypoints)

        for (index in 0 until track.size - 1) {
            val step = RouteEngine.haversine(
                track[index].lat,
                track[index].lng,
                track[index + 1].lat,
                track[index + 1].lng,
            )
            assertTrue("2 s 位移应在合理范围，实际 $step m", step < 15.0)
        }
    }
}
