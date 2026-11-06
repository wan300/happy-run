package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.Identity
import com.lepao.companion.core.ReadinessView
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

    fun plan(context: Context, rules: RunRules, identity: Identity, nowEpoch: Long): ReadinessView {
        val request = PlanRequest(
            nowEpoch = nowEpoch,
            schoolId = rules.schoolId,
            identitySchoolId = identity["school_id"],
            // `RunRules.blockers()` already reports unsupported flags, so drop those here to keep
            // one message per reason; the raw flag list is evaluated below.
            configBlockers = rules.blockers().filterNot { it.startsWith(UNSUPPORTED_PREFIX) },
            unsupportedFlags = RunRules.unsupportedFlags(rules.raw),
            weekTime = rules.weekTime,
            runStartTime = rules.runStartTime,
            runEndTime = rules.runEndTime,
            timeRules = rules.timeRules,
            checkpoints = rules.checkpoints.map { GeoPoint(it.id, it.latitude, it.longitude) },
            pointNumOnline = rules.pointNumOnline,
            // A new random seed per attempt: a run must never repeat a previous track.
            seed = System.currentTimeMillis(),
        )
        val result = PlanEvaluator.evaluate(request) { durationSec, seed, via ->
            RouteEngine.buildTrack(
                context,
                durationSec,
                seed,
                via.map { RouteEngine.Waypoint(it.latitude, it.longitude) },
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
