package com.lepao.companion.run

import com.lepao.companion.core.PlannedPoint
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.PlannedVia
import com.lepao.companion.core.RunClock
import com.lepao.companion.core.RunEntryWindow
import com.lepao.companion.core.TimeRule
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** One generated track sample. Deliberately independent from the route slice so planning is pure. */
data class TrackPoint(
    val t: Double,
    val lat: Double,
    val lng: Double,
    val arc: Double = 0.0,
)

/** A checkpoint candidate from `beforeRun` (`run_line_info.point_list`). */
data class GeoPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    /** `type == "1"`: a 必经点. Skipping one makes the server reject the record ("打卡点异常"). */
    val mandatory: Boolean = false,
)

/**
 * Everything the planner reads out of `beforeRun` plus the moments that must be deterministic.
 *
 * The whole planning decision is a pure function of this value, which is why the planner can be
 * unit tested on the JVM without Android or `org.json`.
 */
data class PlanRequest(
    val nowEpoch: Long,
    val schoolId: String,
    val identitySchoolId: String,
    val configBlockers: List<String>,
    val unsupportedFlags: List<String>,
    val weekTime: Set<String>,
    val runStartTime: Int,
    val runEndTime: Int,
    val timeRules: List<TimeRule>,
    val checkpoints: List<GeoPoint>,
    val pointNumOnline: Int,
    val seed: Long,
)

data class PlanResult(
    val ok: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val rule: TimeRule?,
    val plan: PlannedRun?,
)

/** Fixed profile arithmetic: distance clamp, pace clamp, duration and step derivation. */
object PlanPolicy {

    const val TARGET_DISTANCE_KM = 2.03
    const val TARGET_PACE_SEC_PER_KM = 365.0
    const val STEPS_PER_KM = 832.5
    const val STEP_INTERVAL_SEC = 60
    const val MIN_RULE_DISTANCE_KM = 0.2
    const val MIN_DURATION_SEC = 60
    const val EDGE_DISTANCE_STEP_KM = 0.01

    data class Distance(val km: Double, val text: String, val adjusted: Boolean, val error: String?)

    data class Pace(val seconds: Double, val adjusted: Boolean, val error: String?)

    /**
     * `clamp(2.03, min+0.01, max-0.01)`, two decimals HALF_UP.
     *
     * The epsilon keeps the effective run strictly inside the school interval, exactly like the
     * PC client, so a distance exactly on the boundary can never be rejected server side.
     */
    fun distance(minKm: Double, maxKm: Double): Distance {
        if (!minKm.isFinite() || !maxKm.isFinite()) {
            return Distance(0.0, "0.00", false, "学校里程区间不是有效数值")
        }
        if (maxKm <= minKm) {
            return Distance(0.0, "0.00", false, "学校里程区间无效（min_distance >= max_distance）")
        }
        if (minKm < MIN_RULE_DISTANCE_KM) {
            return Distance(0.0, "0.00", false, "学校最小里程低于可用下限 0.2 km")
        }
        val low = minKm + EDGE_DISTANCE_STEP_KM
        val high = maxKm - EDGE_DISTANCE_STEP_KM
        if (high < low) {
            return Distance(0.0, "0.00", false, "学校里程区间过窄，无法自动夹取")
        }
        val clamped = TARGET_DISTANCE_KM.coerceIn(low, high)
        val text = BigDecimal(clamped).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val km = text.toDouble()
        if (km < MIN_RULE_DISTANCE_KM) {
            return Distance(km, text, true, "夹取后的里程低于可用下限 0.2 km")
        }
        return Distance(km, text, km != TARGET_DISTANCE_KM, null)
    }

    /** Target 365 s/km, clamped into `[lower+1, upper-1]`; a window narrower than 2 s is invalid. */
    fun pace(minPace: Double, maxPace: Double): Pace {
        if (!minPace.isFinite() || !maxPace.isFinite()) {
            return Pace(0.0, false, "学校配速区间不是有效数值")
        }
        val lower = min(minPace, maxPace)
        val upper = max(minPace, maxPace)
        val low = lower + 1.0
        val high = upper - 1.0
        if (high < low) {
            return Pace(0.0, false, "学校配速区间过窄，无法自动夹取")
        }
        val target = TARGET_PACE_SEC_PER_KM.coerceIn(low, high)
        return Pace(target, target != TARGET_PACE_SEC_PER_KM, null)
    }

    fun durationSeconds(distanceKm: Double, paceSecPerKm: Double): Int =
        round(distanceKm * paceSecPerKm).toInt()

    fun stepCount(distanceKm: Double): Int = round(distanceKm * STEPS_PER_KM).toInt()

    /** `max(2, min(min_log_num, 4), min(point_num, 3))` from the verified client. */
    fun requiredPoints(minLogNum: Int, pointNumOnline: Int): Int =
        max(2, max(min(minLogNum, 4), min(pointNumOnline, 3)))

    fun passTotal(trackSize: Int): Int = max(60, trackSize - 5)
}

/** Great-circle distance used for checkpoint/track matching (pure, no Android). */
object Geo {
    private const val EARTH_M = 6371000.0

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dPhi = p2 - p1
        val dLambda = Math.toRadians(lng2 - lng1)
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(p1) * cos(p2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_M * asin(sqrt(h))
    }
}

/** A checkpoint that was matched onto the generated track. */
data class SelectedPoint(
    val id: String,
    val t: Double,
    val lat: Double,
    val lng: Double,
    val arc: Double,
    val gapM: Double,
    val mandatory: Boolean = false,
)

data class SelectionResult(
    val points: List<SelectedPoint>,
    val warnings: List<String>,
    val error: String?,
)

/**
 * Matches `beforeRun` checkpoints onto the generated track and picks the run's check-in moments.
 *
 * A candidate survives only when its nearest sample is within 20 m and inside the usable time
 * window; survivors are sorted by time and picked greedily with a 30 s minimum spacing.
 *
 * 20 m is the required check-in radius for this build: the planner steers the track past a subset
 * of the checkpoints (see [CheckpointSelector.targets]), so a matched point is normally 4-8 m away
 * and the limit only rejects points the detour could not reach.
 */
object CheckpointSelector {

    const val MAX_GAP_M = 20.0
    const val WARN_GAP_M = 15.0
    const val MIN_SPACING_SEC = 30.0
    const val EDGE_MARGIN_SEC = 5.0

    /** Farthest a checkpoint may be from the base track for the generator to bend towards it. */
    const val REACH_M = RouteEngine.VIA_MAX_DETOUR_M + RouteEngine.VIA_TARGET_M

    /** A checkpoint the track should be steered past, with its nearest base-track moment. */
    data class Target(val point: GeoPoint, val t: Double, val gapM: Double)

    /** Steering targets plus a blocker when a 必经点 cannot be used. */
    data class TargetPlan(val points: List<GeoPoint>, val error: String?)

    /**
     * Choose the checkpoints worth steering past.
     *
     * 必经点 (`type == "1"`, [GeoPoint.mandatory]) always take a slot first: the school rejects a
     * record that skips one ("打卡点异常"). The remaining slots go to the closest ordinary points,
     * subject to a [MIN_SPACING_SEC] gap in check-in time. The generator only bends towards these
     * points; the others stay on the corridor and the 20 m rule discards them.
     */
    fun targets(
        checkpoints: List<GeoPoint>,
        track: List<TrackPoint>,
        durationSec: Int,
        needed: Int,
    ): TargetPlan {
        if (needed <= 0 || track.size < 3) return TargetPlan(emptyList(), null)
        val earliest = EDGE_MARGIN_SEC
        val latest = durationSec - EDGE_MARGIN_SEC
        val candidates = ArrayList<Target>(checkpoints.size)
        for (checkpoint in checkpoints) {
            var bestT = 0.0
            var bestGap = Double.MAX_VALUE
            for (sample in track) {
                val gap = Geo.distanceM(checkpoint.latitude, checkpoint.longitude, sample.lat, sample.lng)
                if (gap < bestGap) {
                    bestGap = gap
                    bestT = sample.t
                }
            }
            candidates.add(Target(checkpoint, bestT, bestGap))
        }
        val wanted = max(needed, candidates.count { it.point.mandatory })
        val chosen = ArrayList<Target>(wanted)
        for (candidate in candidates.filter { it.point.mandatory }.sortedBy { it.t }) {
            if (candidate.gapM > REACH_M) {
                return TargetPlan(
                    emptyList(),
                    "必经打卡点 ${candidate.point.id} 距轨迹 ${round(candidate.gapM)} m，" +
                        "超过绕行上限 ${REACH_M.toInt()} m",
                )
            }
            if (candidate.t < earliest || candidate.t > latest) {
                return TargetPlan(emptyList(), "必经打卡点 ${candidate.point.id} 不在可打卡时间窗内")
            }
            if (chosen.any { abs(it.t - candidate.t) < MIN_SPACING_SEC }) {
                return TargetPlan(
                    emptyList(),
                    "必经打卡点 ${candidate.point.id} 与其它必经点的打卡时刻间隔不足 ${MIN_SPACING_SEC.toInt()} s",
                )
            }
            chosen.add(candidate)
        }
        val ordinary = candidates
            .filter { !it.point.mandatory && it.gapM <= REACH_M && it.t >= earliest && it.t <= latest }
            .sortedBy { it.gapM }
        for (candidate in ordinary) {
            if (chosen.size >= wanted) break
            if (chosen.all { abs(it.t - candidate.t) >= MIN_SPACING_SEC }) chosen.add(candidate)
        }
        return TargetPlan(chosen.sortedBy { it.t }.map { it.point }, null)
    }

    fun select(
        checkpoints: List<GeoPoint>,
        track: List<TrackPoint>,
        durationSec: Int,
        needed: Int,
    ): SelectionResult {
        if (needed <= 0) return SelectionResult(emptyList(), emptyList(), null)
        if (track.size < 3) {
            return SelectionResult(emptyList(), emptyList(), "轨迹样本不足，无法计算打卡点")
        }
        val earliest = EDGE_MARGIN_SEC
        val latest = durationSec - EDGE_MARGIN_SEC
        val candidates = ArrayList<SelectedPoint>()
        for (checkpoint in checkpoints) {
            var best: TrackPoint? = null
            var bestGap = Double.MAX_VALUE
            for (sample in track) {
                val gap = Geo.distanceM(checkpoint.latitude, checkpoint.longitude, sample.lat, sample.lng)
                if (gap < bestGap) {
                    bestGap = gap
                    best = sample
                }
            }
            val sample = best ?: continue
            if (bestGap > MAX_GAP_M) continue
            if (sample.t < earliest || sample.t > latest) continue
            candidates.add(
                SelectedPoint(
                    checkpoint.id,
                    sample.t,
                    sample.lat,
                    sample.lng,
                    sample.arc,
                    bestGap,
                    checkpoint.mandatory,
                ),
            )
        }
        val missing = checkpoints.filter { it.mandatory && candidates.none { c -> c.id == it.id } }
        if (missing.isNotEmpty()) {
            return SelectionResult(
                emptyList(),
                emptyList(),
                "必经打卡点 ${missing.first().id} 未能在轨迹 20 m 内匹配",
            )
        }
        val wanted = max(needed, candidates.count { it.mandatory })
        val chosen = ArrayList<SelectedPoint>(wanted)
        // 必经点 first: dropping one makes the server reject the whole record.
        for (candidate in candidates.filter { it.mandatory }.sortedBy { it.t }) {
            if (chosen.all { abs(it.t - candidate.t) >= MIN_SPACING_SEC }) chosen.add(candidate)
        }
        for (candidate in candidates.filterNot { it.mandatory }.sortedBy { it.t }) {
            if (chosen.size >= wanted) break
            if (chosen.all { abs(it.t - candidate.t) >= MIN_SPACING_SEC }) chosen.add(candidate)
        }
        chosen.sortBy { it.t }
        if (chosen.size < wanted) {
            return SelectionResult(
                chosen,
                emptyList(),
                "点位与轨迹不匹配：需要 $wanted 个 20 m 内的打卡点，匹配到 ${chosen.size} 个",
            )
        }
        val warnings = chosen
            .filter { it.gapM > WARN_GAP_M }
            .map { "打卡点 ${it.id} 与轨迹最近点偏离 ${round(it.gapM)} m（仍在上限 ${MAX_GAP_M.toInt()} m 内）" }
        return SelectionResult(chosen, warnings, null)
    }
}

/** The single decision point of the run planner: pure, deterministic and unit testable. */
object PlanEvaluator {

    fun evaluate(
        request: PlanRequest,
        trackFactory: (durationSec: Int, seed: Long, via: List<GeoPoint>) -> List<TrackPoint>,
    ): PlanResult {
        val blockers = ArrayList<String>()
        for (flag in request.unsupportedFlags) {
            blockers.add("配置需要尚未实现的分支：$flag")
        }
        blockers.addAll(request.configBlockers)
        if (blockers.isNotEmpty()) return blocked(blockers)
        if (request.schoolId != request.identitySchoolId) {
            return blocked(listOf("beforeRun 返回的学校与当前登录态不一致"))
        }

        val seconds = RunClock.secondsOfDay(request.nowEpoch)
        val entryEnd = RunEntryWindow.effectiveEnd(request.runEndTime)
        if (seconds < request.runStartTime || seconds >= entryEnd) {
            blockers.add(
                "当前不在学校跑步入口时间窗（${clock(request.runStartTime)}–${clock(entryEnd)}）",
            )
        }
        val weekday = RunClock.local(request.nowEpoch).dayOfWeek.value % 7
        if (weekday.toString() !in request.weekTime) {
            blockers.add("今天不在学校允许跑步的星期内")
        }
        val matches = request.timeRules.filter { seconds >= it.startTime && seconds < it.endTime }
        if (matches.size != 1) {
            blockers.add("当前时刻没有唯一可用的时段规则")
        }
        if (blockers.isNotEmpty()) {
            return PlanResult(false, blockers, emptyList(), matches.singleOrNull(), null)
        }
        val rule = matches.single()

        val distance = PlanPolicy.distance(rule.minDistanceKm, rule.maxDistanceKm)
        if (distance.error != null) return blocked(listOf(distance.error))
        val pace = PlanPolicy.pace(rule.minPaceSecPerKm, rule.maxPaceSecPerKm)
        if (pace.error != null) return blocked(listOf(pace.error))
        val duration = PlanPolicy.durationSeconds(distance.km, pace.seconds)
        if (duration < PlanPolicy.MIN_DURATION_SEC) {
            return blocked(listOf("计算出的运行时长不足 ${PlanPolicy.MIN_DURATION_SEC} s"))
        }
        val stepNum = PlanPolicy.stepCount(distance.km)
        val needed = PlanPolicy.requiredPoints(rule.minLogNum, request.pointNumOnline)
        if (request.checkpoints.size < needed) {
            return blocked(
                listOf("可用打卡点位不足：规则需要 $needed 个，配置只有 ${request.checkpoints.size} 个"),
            )
        }

        // Two passes: the plain corridor track tells us which checkpoints are worth a detour and
        // when they would be reached; the second build bends the path past exactly those points,
        // so the 20 m rule can be met without dragging the whole route across the campus.
        val baseTrack = trackFactory(duration, request.seed, emptyList())
        if (baseTrack.size < 3) return blocked(listOf("生成的轨迹样本不足，无法选择打卡点"))
        val targetPlan = CheckpointSelector.targets(request.checkpoints, baseTrack, duration, needed)
        if (targetPlan.error != null) return blocked(listOf(targetPlan.error))
        val targets = targetPlan.points
        val track = if (targets.isEmpty()) baseTrack else trackFactory(duration, request.seed, targets)
        val selection = CheckpointSelector.select(request.checkpoints, track, duration, needed)
        if (selection.error != null) return blocked(listOf(selection.error))

        val warnings = ArrayList<String>()
        if (distance.adjusted) {
            warnings.add(
                "目标里程 ${PlanPolicy.TARGET_DISTANCE_KM} km 不在学校区间 " +
                    "[${rule.minDistanceKm}, ${rule.maxDistanceKm}] 内，已自动调整为 ${distance.text} km",
            )
        }
        if (pace.adjusted) {
            warnings.add(
                "目标配速 ${PlanPolicy.TARGET_PACE_SEC_PER_KM.toInt()} s/km 不在学校区间 " +
                    "[${rule.lowerPace}, ${rule.upperPace}] 内，已自动调整为 ${pace.seconds.toInt()} s/km",
            )
        }
        warnings.addAll(selection.warnings)

        val points = selection.points.map {
            PlannedPoint(
                id = it.id,
                checkinT = it.t,
                latitude = it.lat,
                longitude = it.lng,
                distance = 0.65,
                arcM = it.arc,
            )
        }
        val plan = PlannedRun(
            seed = request.seed,
            durationSec = duration,
            distanceKm = distance.text,
            stepNum = stepNum,
            stepInterval = PlanPolicy.STEP_INTERVAL_SEC,
            passTotal = PlanPolicy.passTotal(track.size),
            passEffectiveNum = 0,
            areaValid = "1",
            collector = "android-app",
            points = points,
            // Persisted so the foreground service and the artifact writer can rebuild the exact
            // steered track after a process restart.
            via = targets.map { PlannedVia(it.id, it.latitude, it.longitude) },
            rule = rule,
            createdAt = request.nowEpoch,
        )
        return PlanResult(true, emptyList(), warnings, rule, plan)
    }

    private fun blocked(blockers: List<String>): PlanResult =
        PlanResult(false, blockers, emptyList(), null, null)

    private fun clock(seconds: Int): String = String.format(
        java.util.Locale.ROOT,
        "%02d:%02d",
        seconds / 3600,
        (seconds % 3600) / 60,
    )
}
