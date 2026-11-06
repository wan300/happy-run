package com.lepao.companion.ui

import android.content.Context
import com.lepao.companion.R
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.RunClock
import com.lepao.companion.core.RunProfile
import com.lepao.companion.core.RunStage
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Text mapping shared by the two screens: stage names, clocks and the fixed plan summary. */
internal object UiText {

    private val SHORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
    private val FULL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    private val CHECK_ENTRIES: List<Pair<String, Int>> = listOf(
        "uid_matches" to R.string.check_uid_matches,
        "start_time_matches" to R.string.check_start_time_matches,
        "game_id_matches" to R.string.check_game_id_matches,
        "record_valid" to R.string.check_record_valid,
        "distance_matches" to R.string.check_distance_matches,
        "duration_matches" to R.string.check_duration_matches,
        "steps_match" to R.string.check_steps_match,
        "route_key_matches" to R.string.check_route_key_matches,
    )

    fun stageLabel(context: Context, stage: RunStage): String = context.getString(
        when (stage) {
            RunStage.NEW -> R.string.stage_new
            RunStage.PREPARED -> R.string.stage_prepared
            RunStage.START_PENDING -> R.string.stage_start_pending
            RunStage.RUNNING -> R.string.stage_running
            RunStage.LOCATION_PENDING -> R.string.stage_location_pending
            RunStage.UPLOADING -> R.string.stage_uploading
            RunStage.STOP_PENDING -> R.string.stage_stop_pending
            RunStage.STOP_ACKNOWLEDGED -> R.string.stage_stop_acknowledged
            RunStage.SENSOR_PENDING -> R.string.stage_sensor_pending
            RunStage.SUBMITTED -> R.string.stage_submitted
            RunStage.VERIFIED -> R.string.stage_verified
            RunStage.UNCERTAIN -> R.string.stage_uncertain
            RunStage.FAILED -> R.string.stage_failed
        },
    )

    fun stageColor(context: Context, stage: RunStage): Int = context.getColor(
        when (stage) {
            RunStage.VERIFIED -> R.color.ok
            RunStage.SUBMITTED, RunStage.RUNNING -> R.color.brand
            RunStage.UNCERTAIN, RunStage.FAILED, RunStage.STOP_PENDING -> R.color.danger
            else -> R.color.text_primary
        },
    )

    fun checkEntries(): List<Pair<String, Int>> = CHECK_ENTRIES

    /** "mm:ss", or "h:mm:ss" after one hour. */
    fun clock(totalSeconds: Int): String {
        val safe = if (totalSeconds < 0) 0 else totalSeconds
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun timeOfDay(seconds: Int): String = String.format(
        Locale.ROOT,
        "%02d:%02d",
        (if (seconds < 0) 0 else seconds) / 3600,
        ((if (seconds < 0) 0 else seconds) % 3600) / 60,
    )

    fun shortTime(epoch: Long): String = if (epoch <= 0L) "" else RunClock.local(epoch).format(SHORT_TIME)

    fun fullTime(epoch: Long): String = if (epoch <= 0L) "" else RunClock.local(epoch).format(FULL_TIME)

    fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    fun number(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "—"
        value == Math.floor(value) -> value.toLong().toString()
        else -> String.format(Locale.ROOT, "%.1f", value)
    }

    fun bullets(items: List<String>): String = items.joinToString("\n") { "• $it" }

    /**
     * Fixed-profile plan summary. Falls back to the compiled-in profile values while the run slice
     * is still a placeholder, so the card never shows an empty plan line.
     */
    fun planSummary(context: Context, plan: PlannedRun?): String {
        val distanceText = plan?.distanceKm?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.ROOT, "%.2f", RunProfile.TARGET_DISTANCE_KM)
        val duration = plan?.durationSec?.takeIf { it > 0 }
            ?: (RunProfile.TARGET_DISTANCE_KM * RunProfile.TARGET_PACE_SEC_PER_KM).roundToInt()
        val steps = plan?.stepNum?.takeIf { it > 0 }
            ?: (RunProfile.TARGET_DISTANCE_KM * RunProfile.STEPS_PER_KM).roundToInt()
        val points = plan?.passTotal?.takeIf { it > 0 } ?: DEFAULT_CHECKPOINTS
        val pace = RunProfile.TARGET_PACE_SEC_PER_KM.roundToInt()
        return context.getString(R.string.config_plan, distanceText, clock(duration), pace, steps, points)
    }

    private const val DEFAULT_CHECKPOINTS = 2
}
