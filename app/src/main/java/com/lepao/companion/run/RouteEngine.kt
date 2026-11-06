package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.PlannedVia
import com.lepao.companion.core.StateException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Corridor geometry and simulated track generation.
 *
 * Every function is deterministic for a given seed so the foreground service can rebuild the exact
 * track after a process restart. The geometry follows `docs/run-engine.md` §6.1/§6.2.
 *
 * The corridor reading/parsing half of this object is deliberately free of Android dependencies so
 * JVM unit tests can feed the same fixed polyline; only [corridor] touches `Context.assets`.
 */
object RouteEngine {

    /**
     * One simulated position.
     *
     * [arc] is the wrapped arc-length offset on the corridor (0..length). [speed] is the
     * metres-per-second value the route writer serialises; it is derived from the unwrapped
     * integrated distance so a corridor wrap never produces a negative speed.
     */
    data class Sample(
        val t: Double,
        val lat: Double,
        val lng: Double,
        val arc: Double,
        val speed: Double = 0.0,
    )

    /** Corridor asset written by the capture slice: `{"points":[[lat,lng],...]}`. */
    const val CORRIDOR_ASSET = "corridor.json"

    /** Resampling step of the arc-length index; the spec requires <= 0.5 m. */
    const val CORRIDOR_STEP_M = 0.5

    /** Route sampling period in seconds (2 s), matching the observed writer. */
    const val ROUTE_DT = 2.0

    private const val EARTH_M = 6371000.0
    private const val SPEED_STEP = 0.1
    private const val M_PER_DEGREE = 111320.0
    private const val TWO_PI = 2.0 * PI
    private const val MIN_SPEED = 0.15
    private const val MIN_CORRIDOR_POINTS = 3
    /** Largest seed-derived start offset; keeps the track on the corridor and within ~3% length. */
    private const val MAX_START_OFFSET_M = 60.0

    /** A checkpoint the generated track must sweep past; only its geometry matters here. */
    data class Waypoint(val latitude: Double, val longitude: Double)

    /** Desired closest approach of the generated track to a waypoint, in metres. */
    const val VIA_TARGET_M = 4.0

    /** A waypoint farther than this from the corridor is skipped: the detour would be implausible. */
    const val VIA_MAX_DETOUR_M = 260.0

    /** Waypoints closer than this along the corridor merge into a single bend. */
    private const val VIA_KNOT_GAP_M = 25.0

    /** Corridor length kept ahead of a waypoint when the seed offset has to be clamped back. */
    private const val VIA_COVERAGE_MARGIN_M = 10.0

    /** Smallest large-shift detour half-width; widened for bigger shifts to keep the bend gentle. */
    private const val VIA_WIDTH_BASE_M = 120.0
    private const val VIA_WIDTH_PER_M = 1.2
    private const val VIA_WIDTH_MIN_M = 40.0
    private const val VIA_WIDTH_MAX_M = 400.0

    /**
     * Arc-length indexed, uniformly resampled copy of the corridor polyline.
     *
     * Built by [of]; lookups are O(log n) and independent of the original vertex spacing.
     */
    class Corridor private constructor(
        private val lat: DoubleArray,
        private val lng: DoubleArray,
        private val arc: DoubleArray,
    ) {

        /** Total polyline length in metres. */
        val length: Double = if (arc.isEmpty()) 0.0 else arc[arc.size - 1]

        /** Number of resampled vertices. */
        val size: Int get() = lat.size

        /** Interpolated `[lat, lng]` at [distance] metres; clamped to the corridor ends. */
        fun at(distance: Double): DoubleArray {
            if (lat.isEmpty()) throw StateException("走廊没有可用点位")
            if (lat.size == 1 || length <= 0.0) return doubleArrayOf(lat[0], lng[0])
            val clamped = distance.coerceIn(0.0, length)
            var low = 0
            var high = arc.size - 1
            while (low < high - 1) {
                val middle = (low + high) ushr 1
                if (arc[middle] <= clamped) low = middle else high = middle
            }
            val span = arc[high] - arc[low]
            val ratio = if (span <= 0.0) 0.0 else (clamped - arc[low]) / span
            return doubleArrayOf(
                lat[low] + (lat[high] - lat[low]) * ratio,
                lng[low] + (lng[high] - lng[low]) * ratio,
            )
        }

        /**
         * Track heading in radians at [distance], using the same finite difference as the Python
         * reference (2 m before/after, `atan2(dLng, dLat)`).
         */
        fun bearing(distance: Double): Double {
            val before = at(max(0.0, distance - 2.0))
            val after = at(min(length, distance + 2.0))
            return atan2(after[1] - before[1], after[0] - before[0])
        }

        companion object {

            /** Resample [points] (`[lat, lng]` pairs) into an arc-length index. */
            fun of(points: List<DoubleArray>, stepM: Double = CORRIDOR_STEP_M): Corridor {
                if (points.isEmpty()) throw StateException("走廊点位为空")
                if (stepM <= 0.0) throw StateException("走廊重采样步长必须为正")
                val lat = ArrayList<Double>(points.size * 8)
                val lng = ArrayList<Double>(points.size * 8)
                val arc = ArrayList<Double>(points.size * 8)
                var total = 0.0
                for (index in 0 until points.size - 1) {
                    val from = points[index]
                    val to = points[index + 1]
                    val segment = haversine(from[0], from[1], to[0], to[1])
                    if (segment <= 1e-6) continue
                    val pieces = max(1, ceil(segment / stepM).toInt())
                    for (piece in 0 until pieces) {
                        val ratio = piece.toDouble() / pieces
                        lat.add(from[0] + (to[0] - from[0]) * ratio)
                        lng.add(from[1] + (to[1] - from[1]) * ratio)
                        arc.add(total + segment * ratio)
                    }
                    total += segment
                }
                val last = points[points.size - 1]
                lat.add(last[0])
                lng.add(last[1])
                arc.add(total)
                return Corridor(lat.toDoubleArray(), lng.toDoubleArray(), arc.toDoubleArray())
            }
        }
    }

    /**
     * One local detour: around [arc] the track is translated by a smooth raised-cosine weight
     * towards the checkpoint, peaking at ([north], [east]) metres on the waypoint's own arc.
     */
    private data class Bump(val arc: Double, val width: Double, val north: Double, val east: Double)

    /**
     * Smooth check-point steering applied on top of the corridor.
     *
     * Each waypoint contributes a compact detour: the track is translated towards the checkpoint
     * (a fixed direction, not the corridor normal, so sharp corridor corners cannot make the offset
     * swing) by `distance - VIA_TARGET_M` metres, weighted by a raised-cosine window of half-width
     * [Bump.width]. Displacements of overlapping bumps are blended, and the window is sized so a
     * waypoint's own bump reaches its full value at the waypoint arc.
     */
    private class ViaPath(private val bumps: List<Bump>) {

        val isEmpty: Boolean get() = bumps.isEmpty()

        val firstWaypointArc: Double get() = bumps.first().arc
        val lastWaypointArc: Double get() = bumps.last().arc

        /** Displacement in metres as `[north, east]` at [arc]; zero outside every bump. */
        fun displacementAt(arc: Double): DoubleArray {
            if (bumps.isEmpty()) return ZERO
            var north = 0.0
            var east = 0.0
            var total = 0.0
            for (bump in bumps) {
                val delta = arc - bump.arc
                if (abs(delta) >= bump.width) continue
                val weight = 0.5 * (1.0 + cos(PI * delta / bump.width))
                if (weight <= 0.0) continue
                north += weight * bump.north
                east += weight * bump.east
                total += weight
            }
            if (total <= 0.0) return ZERO
            val norm = max(1.0, total)
            return doubleArrayOf(north / norm, east / norm)
        }

        companion object {
            private val ZERO = doubleArrayOf(0.0, 0.0)
            val EMPTY = ViaPath(emptyList())
        }
    }

    /**
     * Build the steering detours for [via]. Waypoints already closer than [VIA_TARGET_M] and
     * waypoints beyond [VIA_MAX_DETOUR_M] contribute nothing.
     */
    private fun warp(corridor: Corridor, via: List<Waypoint>): ViaPath {
        if (via.isEmpty() || corridor.length <= 0.0) return ViaPath.EMPTY
        val raw = ArrayList<Bump>(via.size)
        for (waypoint in via) {
            val nearest = nearestArc(corridor, waypoint)
            val arc = nearest[0]
            val distance = nearest[1]
            if (distance <= VIA_TARGET_M) continue
            if (distance - VIA_TARGET_M > VIA_MAX_DETOUR_M) continue
            val point = corridor.at(arc)
            val cosLat = max(0.25, cos(Math.toRadians(point[0])))
            val north = (waypoint.latitude - point[0]) * M_PER_DEGREE
            val east = (waypoint.longitude - point[1]) * M_PER_DEGREE * cosLat
            val norm = sqrt(north * north + east * east)
            if (norm <= 1e-9) continue
            val shift = distance - VIA_TARGET_M
            raw.add(Bump(arc, 0.0, north / norm * shift, east / norm * shift))
        }
        if (raw.isEmpty()) return ViaPath.EMPTY
        raw.sortBy { it.arc }
        // Two waypoints on the same metre of corridor cannot both be swept past; keep the harder.
        val merged = ArrayList<Bump>(raw.size)
        for (bump in raw) {
            val last = merged.lastOrNull()
            if (last != null && bump.arc - last.arc < VIA_KNOT_GAP_M) {
                val candidate = hypot(bump.north, bump.east)
                val current = hypot(last.north, last.east)
                if (candidate > current) merged[merged.size - 1] = bump
            } else {
                merged.add(bump)
            }
        }
        // The detour must be wide enough to look like a bend, but narrow enough that neighbouring
        // waypoints keep their own peak (otherwise the blend would pull each of them short).
        val bumps = ArrayList<Bump>(merged.size)
        for (index in merged.indices) {
            val bump = merged[index]
            val magnitude = hypot(bump.north, bump.east)
            var width = min(VIA_WIDTH_MAX_M, VIA_WIDTH_BASE_M + VIA_WIDTH_PER_M * magnitude)
            val previousGap = if (index > 0) bump.arc - merged[index - 1].arc else Double.MAX_VALUE
            val nextGap = if (index < merged.size - 1) merged[index + 1].arc - bump.arc else Double.MAX_VALUE
            width = min(width, 0.4 * min(previousGap, nextGap))
            bumps.add(bump.copy(width = width.coerceAtLeast(VIA_WIDTH_MIN_M)))
        }
        return ViaPath(bumps)
    }

    /** Nearest corridor arc to [waypoint]: 1 m scan, then a 0.1 m refinement. */
    private fun nearestArc(corridor: Corridor, waypoint: Waypoint): DoubleArray {
        var bestArc = 0.0
        var bestDistance = Double.MAX_VALUE
        var arc = 0.0
        while (arc <= corridor.length) {
            val point = corridor.at(arc)
            val distance = haversine(waypoint.latitude, waypoint.longitude, point[0], point[1])
            if (distance < bestDistance) {
                bestDistance = distance
                bestArc = arc
            }
            arc += 1.0
        }
        var refinedArc = bestArc
        var step = -1.0
        while (step <= 1.0) {
            val candidate = (bestArc + step).coerceIn(0.0, corridor.length)
            val point = corridor.at(candidate)
            val distance = haversine(waypoint.latitude, waypoint.longitude, point[0], point[1])
            if (distance < bestDistance) {
                bestDistance = distance
                refinedArc = candidate
            }
            step += 0.1
        }
        return doubleArrayOf(refinedArc, bestDistance)
    }

    /**
     * Keep the seed offset small enough that a forward traversal starts before the first waypoint
     * (and a backward traversal ends after the last one), so the detours are actually traversed.
     */
    private fun clampStartOffset(seedOffset: Double, direction: Double, warp: ViaPath, length: Double): Double {
        if (warp.isEmpty) return seedOffset
        val limit = if (direction > 0) {
            (warp.firstWaypointArc - VIA_COVERAGE_MARGIN_M).coerceAtLeast(0.0)
        } else {
            (length - warp.lastWaypointArc - VIA_COVERAGE_MARGIN_M).coerceAtLeast(0.0)
        }
        return min(seedOffset, limit)
    }

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dPhi = p2 - p1
        val dLambda = Math.toRadians(lng2 - lng1)
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(p1) * cos(p2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_M * asin(sqrt(h))
    }

    /** Match the Android writer, which serialises float fields widened to double. */
    fun f32(value: Double): Double =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value.toFloat()).getFloat(0).toDouble()

    /** Raw corridor vertices read from `assets/corridor.json` as `[lat, lng]` pairs. */
    fun corridor(context: Context): List<DoubleArray> {
        val text = try {
            context.assets.open(CORRIDOR_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: java.io.IOException) {
            throw StateException("无法读取走廊文件：${error.message}")
        }
        return parseCorridor(text)
    }

    /**
     * Parse the corridor envelope without any Android or org.json dependency, so the same parser is
     * exercised by JVM unit tests.
     */
    fun parseCorridor(text: String): List<DoubleArray> {
        val key = text.indexOf("\"points\"")
        if (key < 0) throw StateException("走廊文件缺少 points 数组")
        var index = text.indexOf('[', key)
        if (index < 0) throw StateException("走廊文件 points 不是数组")
        index += 1
        val points = ArrayList<DoubleArray>(512)
        while (true) {
            index = skipSeparators(text, index)
            if (index >= text.length) throw StateException("走廊文件 points 数组未闭合")
            when (text[index]) {
                ']' -> {
                    if (points.size < MIN_CORRIDOR_POINTS) throw StateException("走廊点位过少")
                    return points
                }
                '[' -> {
                    val (latitude, afterLat) = readNumber(text, index + 1)
                    val (longitude, afterLng) = readNumber(text, afterLat)
                    var close = skipSeparators(text, afterLng)
                    if (close >= text.length || text[close] != ']') {
                        throw StateException("走廊点位格式不正确")
                    }
                    if (!latitude.isFinite() || !longitude.isFinite()) {
                        throw StateException("走廊坐标不是有限数值")
                    }
                    if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
                        throw StateException("走廊坐标超出范围")
                    }
                    points.add(doubleArrayOf(latitude, longitude))
                    index = close + 1
                }
                else -> throw StateException("走廊点位格式不正确")
            }
        }
    }

    /** Resample [points] into a [Corridor]; kept as a named helper for callers holding raw assets. */
    fun resample(points: List<DoubleArray>, stepM: Double = CORRIDOR_STEP_M): Corridor =
        Corridor.of(points, stepM)

    /** Bind the waypoints persisted on a plan to the geometry type used for track generation. */
    fun waypoints(via: List<PlannedVia>): List<Waypoint> =
        via.map { Waypoint(it.latitude, it.longitude) }

    fun buildTrack(
        context: Context,
        durationSec: Int,
        seed: Long,
        via: List<Waypoint> = emptyList(),
    ): List<Sample> = buildTrack(Corridor.of(corridor(context)), durationSec, seed, via)

    fun buildTrack(
        corridor: List<DoubleArray>,
        durationSec: Int,
        seed: Long,
        via: List<Waypoint> = emptyList(),
    ): List<Sample> = buildTrack(Corridor.of(corridor), durationSec, seed, via)

    /**
     * Build the deterministic simulated track for one run.
     *
     * Direction and corridor offset come from [seed]; the speed profile, lateral wobble and 2 s
     * sampling follow §6.2 of the spec. [via] steers the path past the school's checkpoints: each
     * waypoint bends the centreline smoothly so the track sweeps within [VIA_TARGET_M] of it and
     * then rejoins the corridor. The same seed and waypoint list always produce the same samples.
     */
    fun buildTrack(
        corridor: Corridor,
        durationSec: Int,
        seed: Long,
        via: List<Waypoint> = emptyList(),
    ): List<Sample> {
        if (durationSec <= 0) throw StateException("跑步时长必须为正")
        if (corridor.length <= 0.0) throw StateException("走廊长度为零")

        val rng = Random(seed)
        val phaseA = rng.nextDouble() * TWO_PI
        val phaseB = rng.nextDouble() * TWO_PI
        val wobbleA = rng.nextDouble() * TWO_PI
        val wobbleB = rng.nextDouble() * TWO_PI
        val direction = if (seed % 2L == 0L) 1.0 else -1.0
        val warp = warp(corridor, via)
        // The captured corridor is an open polyline, not a closed loop. Traversing it once keeps
        // every 2 s sample on the corridor; wrapping would teleport ~574 m at the seam. A small
        // seed-derived start offset still changes the first checkpoint neighbourhood; it is clamped
        // back when it would skip a waypoint the track has to sweep past.
        val seedOffset = ((seed ushr 3) % 1000L).toDouble() / 1000.0 * MAX_START_OFFSET_M
        val offset = clampStartOffset(seedOffset, direction, warp, corridor.length)
        val traversable = (corridor.length - offset).coerceAtLeast(corridor.length * 0.9)
        val duration = durationSec.toDouble()

        fun rawSpeed(t: Double): Double {
            var ramp = 0.30 + 0.70 * min(1.0, t / 14.0)
            val remaining = duration - t
            if (remaining < 16.0) ramp *= max(0.45, 0.45 + 0.55 * (remaining / 16.0))
            val wobble = 1.0 + 0.11 * sin(TWO_PI * t / 103.0 + phaseA) +
                0.055 * sin(TWO_PI * t / 29.0 + phaseB)
            return max(MIN_SPEED, ramp * wobble)
        }

        // 0.1 s trapezoidal integration, scaled so the run covers the remaining corridor once.
        val tickCount = durationSec * 10 + 1
        val speeds = DoubleArray(tickCount) { rawSpeed(it * SPEED_STEP) }
        var area = 0.0
        for (index in 0 until tickCount - 1) {
            area += (speeds[index] + speeds[index + 1]) / 2.0 * SPEED_STEP
        }
        val scale = traversable / area
        val integrated = DoubleArray(tickCount)
        for (index in 0 until tickCount - 1) {
            integrated[index + 1] = integrated[index] +
                (speeds[index] + speeds[index + 1]) / 2.0 * SPEED_STEP * scale
        }

        fun travelledAt(t: Double): Double {
            val clamped = t.coerceIn(0.0, duration)
            val position = clamped / SPEED_STEP
            val low = position.toInt()
            if (low >= tickCount - 1) return integrated[tickCount - 1]
            val ratio = position - low
            return integrated[low] + (integrated[low + 1] - integrated[low]) * ratio
        }

        val noise = Random(seed + 1)
        val count = durationSec / 2 + 1
        val samples = ArrayList<Sample>(count)
        val travelled = DoubleArray(count)
        for (index in 0 until count) {
            val t = index * ROUTE_DT
            val distance = travelledAt(t)
            travelled[index] = distance
            val arc = if (direction > 0) {
                (offset + distance).coerceAtMost(corridor.length)
            } else {
                (corridor.length - offset - distance).coerceAtLeast(0.0)
            }
            val base = corridor.at(arc)
            var lat = base[0]
            var lng = base[1]
            // Checkpoint steering: a smooth, direction-independent translation that bends the
            // path out towards each checkpoint and back onto the corridor.
            val detour = warp.displacementAt(arc)
            if (detour[0] != 0.0 || detour[1] != 0.0) {
                lat += detour[0] / M_PER_DEGREE
                lng += detour[1] / M_PER_DEGREE / max(0.25, cos(Math.toRadians(base[0])))
            }
            val side = 1.1 * sin(arc / 41.0 + wobbleA) +
                0.45 * sin(arc / 9.7 + wobbleB) +
                noise.nextGaussian() * 0.18
            val heading = corridor.bearing(arc) + PI / 2.0 + if (direction < 0) PI else 0.0
            lat += side * cos(heading) / M_PER_DEGREE
            lng += side * sin(heading) / M_PER_DEGREE / max(0.25, cos(Math.toRadians(lat)))
            samples.add(Sample(t, lat, lng, arc))
        }

        for (index in 0 until count - 1) {
            samples[index] = samples[index].copy(speed = (travelled[index + 1] - travelled[index]) / ROUTE_DT)
        }
        val lastSpeed = if (count > 1) min(samples[count - 2].speed, 1.6) else 1.6
        samples[count - 1] = samples[count - 1].copy(speed = lastSpeed)
        return samples
    }

    /**
     * Position at [elapsed] seconds, linearly interpolated between the two surrounding samples and
     * clamped to the track ends.
     */
    fun positionAt(track: List<Sample>, elapsed: Double): Sample {
        if (track.isEmpty()) throw StateException("轨迹为空")
        if (elapsed <= track[0].t) return track[0]
        val last = track[track.size - 1]
        if (elapsed >= last.t) return last
        var low = 0
        var high = track.size - 1
        while (low < high - 1) {
            val middle = (low + high) ushr 1
            if (track[middle].t <= elapsed) low = middle else high = middle
        }
        val from = track[low]
        val to = track[high]
        val span = to.t - from.t
        if (span <= 0.0) return from
        val ratio = (elapsed - from.t) / span
        return Sample(
            t = elapsed,
            lat = from.lat + (to.lat - from.lat) * ratio,
            lng = from.lng + (to.lng - from.lng) * ratio,
            arc = from.arc + (to.arc - from.arc) * ratio,
            speed = from.speed + (to.speed - from.speed) * ratio,
        )
    }

    private fun skipSeparators(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val char = text[index]
            if (char == ',' || char == ' ' || char == '\n' || char == '\r' || char == '\t') index++ else break
        }
        return index
    }

    private fun readNumber(text: String, from: Int): Pair<Double, Int> {
        var index = skipSeparators(text, from)
        val start = index
        while (index < text.length) {
            val char = text[index]
            val numeric = char.isDigit() || char == '-' || char == '+' || char == '.' ||
                char == 'e' || char == 'E'
            if (!numeric) break
            index++
        }
        if (index == start) throw StateException("走廊点位缺少坐标")
        val value = text.substring(start, index).toDoubleOrNull()
            ?: throw StateException("走廊坐标不是数字")
        return value to index
    }
}
