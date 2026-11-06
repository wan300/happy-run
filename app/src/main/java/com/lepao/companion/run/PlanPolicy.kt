package com.lepao.companion.run

import com.lepao.companion.core.PlannedPoint
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.PlannedVia
import com.lepao.companion.core.RunClock
import com.lepao.companion.core.RunEntryWindow
import com.lepao.companion.core.RouteTemplate
import com.lepao.companion.core.RunOptions
import com.lepao.companion.core.RunProfile
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
data class GeoPoint(val id: String, val latitude: Double, val longitude: Double, val mandatory: Boolean = false)

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
    val routeTemplate: RouteTemplate = RouteTemplate.LEGACY_CORRIDOR,
    /** 高级选项: custom distance in km, already validated by the UI (null = fixed profile). */
    val customDistanceKm: Double? = null,
    /** 高级选项: custom pace in seconds per km, already validated by the UI (null = fixed profile). */
    val customPaceSecPerKm: Double? = null,
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
     * Clamp the profile's target inside the school bounds, then round to two decimals.
     *
     * The epsilon keeps the effective run strictly inside the school interval, exactly like the
     * PC client, so a distance exactly on the boundary can never be rejected server side.
     */
    fun distance(minKm: Double, maxKm: Double, targetKm: Double = TARGET_DISTANCE_KM): Distance {
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
        val clamped = targetKm.coerceIn(low, high)
        val text = BigDecimal(clamped).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val km = text.toDouble()
        if (km < MIN_RULE_DISTANCE_KM) {
            return Distance(km, text, true, "夹取后的里程低于可用下限 0.2 km")
        }
        return Distance(km, text, km != targetKm, null)
    }

    /** Target pace (default 365 s/km), clamped into `[lower+1, upper-1]`. */
    fun pace(
        minPace: Double,
        maxPace: Double,
        targetSecPerKm: Double = TARGET_PACE_SEC_PER_KM,
    ): Pace {
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
        val target = targetSecPerKm.coerceIn(low, high)
        return Pace(target, target != targetSecPerKm, null)
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

    /**
     * Choose the checkpoints worth steering past.
     *
     * The closest [needed] checkpoints to the base track win, subject to a [MIN_SPACING_SEC] gap in
     * check-in time, so the detours stay small and the greedy check-in order stays satisfiable. The
     * generator only bends towards these points; the others stay on the corridor and the 20 m rule
     * discards them.
     */
    fun targets(
        checkpoints: List<GeoPoint>,
        track: List<TrackPoint>,
        durationSec: Int,
        needed: Int,
    ): List<GeoPoint> {
        if (needed <= 0 || track.size < 3) return emptyList()
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
            if (bestGap > REACH_M) continue
            if (bestT < earliest || bestT > latest) continue
            candidates.add(Target(checkpoint, bestT, bestGap))
        }
        candidates.sortBy { it.gapM }
        val chosen = ArrayList<Target>(needed)
        for (candidate in candidates) {
            if (chosen.all { abs(it.t - candidate.t) >= MIN_SPACING_SEC }) chosen.add(candidate)
            if (chosen.size == needed) break
        }
        return chosen.map { it.point }
    }

    fun select(
        checkpoints: List<GeoPoint>,
        track: List<TrackPoint>,
        durationSec: Int,
        needed: Int,
        repeated: Boolean = false,
    ): SelectionResult {
        if (needed <= 0) return SelectionResult(emptyList(), emptyList(), null)
        if (track.size < 3) {
            return SelectionResult(emptyList(), emptyList(), "轨迹样本不足，无法计算打卡点")
        }
        val earliest = EDGE_MARGIN_SEC
        val latest = durationSec - EDGE_MARGIN_SEC
        val candidates = ArrayList<SelectedPoint>()
        fun addVisit(id: String, sample: TrackPoint?, gap: Double) {
            if (sample != null && gap <= MAX_GAP_M && sample.t in earliest..latest) {
                candidates.add(SelectedPoint(id, sample.t, sample.lat, sample.lng, sample.arc, gap))
            }
        }
        for (checkpoint in checkpoints) {
            var best: TrackPoint? = null
            var bestGap = Double.MAX_VALUE
            for (sample in track) {
                val gap = Geo.distanceM(checkpoint.latitude, checkpoint.longitude, sample.lat, sample.lng)
                if (repeated && (gap > MAX_GAP_M || sample.t !in earliest..latest)) {
                    addVisit(checkpoint.id, best, bestGap)
                    best = null
                    bestGap = Double.MAX_VALUE
                    continue
                }
                if (gap < bestGap) {
                    bestGap = gap
                    best = sample
                }
            }
            addVisit(checkpoint.id, best, bestGap)
        }
        candidates.sortBy { it.t }
        val chosen = ArrayList<SelectedPoint>()
        if (repeated) {
            val mandatory = checkpoints.filter { it.mandatory }.map { it.id }.toSet()
            for (candidate in candidates) {
                if (candidate.id in mandatory && compatible(candidate, chosen)) chosen.add(candidate)
            }
            if (chosen.size < mandatory.size) {
                val missing = mandatory - chosen.map { it.id }.toSet()
                return SelectionResult(chosen, emptyList(), "田径场轨迹无法命中必经打卡点：${missing.joinToString("、")}")
            }
        }
        for (candidate in candidates) {
            if (chosen.size >= needed) break
            if (compatible(candidate, chosen)) chosen.add(candidate)
        }
        if (chosen.size < needed) {
            return SelectionResult(
                chosen,
                emptyList(),
                "点位与轨迹不匹配：需要 $needed 个 20 m 内的打卡点，匹配到 ${chosen.size} 个",
            )
        }
        val warnings = chosen
            .filter { it.gapM > WARN_GAP_M }
            .map { "打卡点 ${it.id} 与轨迹最近点偏离 ${round(it.gapM)} m（仍在上限 ${MAX_GAP_M.toInt()} m 内）" }
        return SelectionResult(chosen.sortedBy { it.t }, warnings, null)
    }

    private fun compatible(candidate: SelectedPoint, chosen: List<SelectedPoint>): Boolean =
        chosen.none { it.id == candidate.id || abs(it.t - candidate.t) < MIN_SPACING_SEC }
}

/** The single decision point of the run planner: pure, deterministic and unit testable. */
object PlanEvaluator {

    fun evaluate(
        request: PlanRequest,
        validateTrack: (List<TrackPoint>) -> String? = { null },
        trackFactory: (durationSec: Int, seed: Long, via: List<GeoPoint>, distanceKm: Double) -> List<TrackPoint>,
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

        // 高级选项: the UI already bounds the inputs (distance > 1 km, pace 3-9 min/km); the school
        // rule interval stays authoritative and an explicit value outside it blocks instead of
        // being silently clamped.
        val customDistance = request.customDistanceKm
        if (customDistance != null &&
            (!customDistance.isFinite() || customDistance <= RunOptions.MIN_DISTANCE_KM)
        ) {
            return blocked(listOf("自定义跑步长度必须大于 1.00 km"))
        }
        val customPace = request.customPaceSecPerKm
        if (customPace != null &&
            (!customPace.isFinite() ||
                customPace < RunOptions.MIN_PACE_SEC_PER_KM ||
                customPace > RunOptions.MAX_PACE_SEC_PER_KM)
        ) {
            return blocked(listOf("自定义配速需在 3:00–9:00 /km（180–540 s/km）之间"))
        }
        val eastCampus = request.routeTemplate == RouteTemplate.EAST_CAMPUS_TRACK
        val targetDistance = customDistance
            ?: if (eastCampus) RunProfile.TARGET_DISTANCE_KM else PlanPolicy.TARGET_DISTANCE_KM
        if (customDistance != null &&
            (customDistance < rule.minDistanceKm || customDistance > rule.maxDistanceKm)
        ) {
            return blocked(
                listOf(
                    "自定义跑步长度 ${formatKm(customDistance)} km 不在学校区间 " +
                        "[${formatKm(rule.minDistanceKm)}–${formatKm(rule.maxDistanceKm)}] km 内",
                ),
            )
        }
        val distance = PlanPolicy.distance(rule.minDistanceKm, rule.maxDistanceKm, targetDistance)
        if (distance.error != null) return blocked(listOf(distance.error))
        if (eastCampus && customDistance == null && abs(distance.km - targetDistance) > 0.020001) {
            return blocked(listOf("东校区当前时段的里程规则不允许约 2.20 km"))
        }
        if (customPace != null && (customPace < rule.lowerPace || customPace > rule.upperPace)) {
            return blocked(
                listOf(
                    "自定义配速 ${RunOptions.formatPace(customPace)}/km 不在学校区间 " +
                        "[${RunOptions.formatPace(rule.lowerPace)}–${RunOptions.formatPace(rule.upperPace)}]/km 内",
                ),
            )
        }
        val pace = PlanPolicy.pace(
            rule.minPaceSecPerKm,
            rule.maxPaceSecPerKm,
            customPace ?: PlanPolicy.TARGET_PACE_SEC_PER_KM,
        )
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
        val baseTrack = trackFactory(duration, request.seed, emptyList(), distance.km)
        if (baseTrack.size < 3) return blocked(listOf("生成的轨迹样本不足，无法选择打卡点"))
        val targets = if (eastCampus) emptyList()
            else CheckpointSelector.targets(request.checkpoints, baseTrack, duration, needed)
        val track = if (targets.isEmpty()) baseTrack else trackFactory(duration, request.seed, targets, distance.km)
        validateTrack(track)?.let { return blocked(listOf(it)) }
        val selection = CheckpointSelector.select(request.checkpoints, track, duration, needed, repeated = eastCampus)
        if (selection.error != null) return blocked(listOf(selection.error))

        val warnings = ArrayList<String>()
        if (distance.adjusted) {
            warnings.add(
                if (customDistance != null) {
                    "自定义跑步长度已按学校区间边界微调为 ${distance.text} km"
                } else {
                    "目标里程 $targetDistance km 不在学校区间 " +
                        "[${rule.minDistanceKm}, ${rule.maxDistanceKm}] 内，已自动调整为 ${distance.text} km"
                },
            )
        }
        if (pace.adjusted && customPace == null) {
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
            routeTemplate = request.routeTemplate,
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

    private fun formatKm(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", value)
}
