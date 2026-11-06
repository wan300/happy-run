package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.Identity
import com.lepao.companion.core.ReadinessView
import com.lepao.companion.core.RouteTemplate
import com.lepao.companion.core.RunOptions
import com.lepao.companion.core.RunRules
import com.lepao.companion.core.TimeRule

/**
 * Derives the fixed-profile run plan from the fetched school configuration.
 *
 * The UI never passes distance, pace, steps or checkpoints in: everything is a function of
 * `beforeRun` plus the current clock. The decision itself lives in [PlanEvaluator] so it can be
 * unit tested without Android; this object only adapts state and builds the corridor track.
 */
object AutoPlanner {

    private const val UNSUPPORTED_PREFIX = "配置需要尚未实现的分支"

    fun plan(
        context: Context,
        rules: RunRules,
        identity: Identity,
        nowEpoch: Long,
        options: RunOptions = RunOptions(),
    ): ReadinessView {
        fun blocked(reason: String) = ReadinessView.blocked(reason).copy(rules = rules)
        if (!EastCampusZone.supports(rules, identity["school_id"])) {
            return blocked("东校区模板仅适用于北京交通大学的跑步配置")
        }
        if (!EastCampusZone.isSelected(rules)) {
            val message = if (EastCampusZone.targetId(rules) == null) "配置中未找到唯一的东校区跑区"
                else "请先切换到东校区跑区"
            return blocked(message)
        }
        val rangeRequired = rules.raw.optString("is_limit_range") == "1"
        val fence = if (rangeRequired) RunFence.from(rules.raw)
            ?: return blocked("东校区已启用围栏，但缺少有效的跑区边界") else null
        val flags = RunRules.unsupportedFlags(rules.raw).filterNot { rangeRequired && it == "is_limit_range" }
        val template = RouteTemplate.EAST_CAMPUS_TRACK
        val request = PlanRequest(
            nowEpoch = nowEpoch,
            schoolId = rules.schoolId,
            identitySchoolId = identity["school_id"],
            // `RunRules.blockers()` already reports unsupported flags, so drop those here to keep
            // one message per reason; the raw flag list is evaluated below.
            configBlockers = rules.blockers().filterNot { it.startsWith(UNSUPPORTED_PREFIX) },
            unsupportedFlags = flags,
            weekTime = rules.weekTime,
            runStartTime = rules.runStartTime,
            runEndTime = rules.runEndTime,
            timeRules = rules.timeRules,
            checkpoints = rules.checkpoints.map { GeoPoint(it.id, it.latitude, it.longitude, it.mandatory) },
            pointNumOnline = rules.pointNumOnline,
            // A new random seed per attempt: a run must never repeat a previous track.
            seed = System.currentTimeMillis(),
            routeTemplate = template,
            customDistanceKm = options.distanceKm,
            customPaceSecPerKm = options.paceSecPerKm,
        )
        val result = PlanEvaluator.evaluate(request, validateTrack = { fence?.blocker(it) }) { durationSec, seed, _, km ->
            RouteEngine.buildTrack(
                RouteEngine.resample(RouteEngine.corridor(context, template)),
                durationSec,
                seed,
                loopDistanceM = km * 1000.0,
            ).map { sample -> TrackPoint(sample.t, sample.lat, sample.lng, sample.arc) }
        }
        return ReadinessView(
            ok = result.ok,
            blockers = result.blockers,
            warnings = result.warnings,
            rule = result.rule ?: windowRule(rules, request),
            plan = result.plan,
            rules = rules,
        )
    }

    private fun windowRule(rules: RunRules, request: PlanRequest): TimeRule? =
        rules.windowCheck(request.nowEpoch).rule
}
