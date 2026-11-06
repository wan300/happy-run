package com.lepao.companion.core

import org.json.JSONArray
import org.json.JSONObject

data class TimeRule(
    val startTime: Int,
    val endTime: Int,
    val minLogNum: Int,
    val minDistanceKm: Double,
    val maxDistanceKm: Double,
    val minPaceSecPerKm: Double,
    val maxPaceSecPerKm: Double,
) {
    val lowerPace: Double get() = minOf(minPaceSecPerKm, maxPaceSecPerKm)
    val upperPace: Double get() = maxOf(minPaceSecPerKm, maxPaceSecPerKm)

    fun toJson(): JSONObject = JSONObject().apply {
        put("start_time", startTime.toString())
        put("end_time", endTime.toString())
        put("min_log_num", minLogNum.toString())
        put("min_distance", minDistanceKm.toString())
        put("max_distance", maxDistanceKm.toString())
        put("min_pace", minPaceSecPerKm.toString())
        put("max_pace", maxPaceSecPerKm.toString())
    }

    companion object {
        fun fromJson(source: JSONObject): TimeRule = TimeRule(
            startTime = JsonUtil.optLongOrNull(source, "start_time")?.toInt() ?: 0,
            endTime = JsonUtil.optLongOrNull(source, "end_time")?.toInt() ?: 0,
            minLogNum = JsonUtil.optLongOrNull(source, "min_log_num")?.toInt() ?: 0,
            minDistanceKm = JsonUtil.optDoubleOrNull(source, "min_distance") ?: 0.0,
            maxDistanceKm = JsonUtil.optDoubleOrNull(source, "max_distance") ?: 0.0,
            minPaceSecPerKm = JsonUtil.optDoubleOrNull(source, "min_pace") ?: 0.0,
            maxPaceSecPerKm = JsonUtil.optDoubleOrNull(source, "max_pace") ?: 0.0,
        )
    }
}

data class Checkpoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val index: String,
)

/** Local entry cutoff confirmed by the user; an earlier server cutoff remains authoritative. */
object RunEntryWindow {
    const val LATEST_END_SECONDS = 22 * 3600

    fun effectiveEnd(serverEndSeconds: Int): Int = minOf(serverEndSeconds, LATEST_END_SECONDS)
}

/**
 * Parsed `Run2/beforeRunV260` payload.
 *
 * The object keeps the raw JSON so a prepared session can be revalidated and audited locally
 * without asking the server again.
 */
class RunRules(val raw: JSONObject) {

    val schoolId: String = raw.optString("school_id")
    val schoolName: String = raw.optString("school_name")
    val gameId: String = raw.optString("game_id")
    val termId: String = raw.optString("term_id")
    val runZoneId: String = raw.optString("run_zone_id")
    val runZoneName: String = raw.optString("run_zone_name")
    val gyroscopeType: String = raw.optString("gyroscope_type", "0")
    val pointNumOnline: Int = JsonUtil.optLongOrNull(raw, "point_num")?.toInt() ?: 0
    val runStartTime: Int = JsonUtil.optLongOrNull(raw, "run_start_time")?.toInt() ?: 0
    val runEndTime: Int = RunEntryWindow.effectiveEnd(
        JsonUtil.optLongOrNull(raw, "run_end_time")?.toInt() ?: 0,
    )
    val termDate: String = raw.optString("term_date")
    val runDistanceHint: String = raw.optString("run_distance")

    val weekTime: Set<String> = run {
        val array = raw.optJSONArray("week_time") ?: return@run emptySet()
        (0 until array.length()).map { array.opt(it).toString() }.toSet()
    }

    val timeRules: List<TimeRule> = run {
        val array = raw.optJSONArray("time_rule_arr") ?: return@run emptyList()
        JsonUtil.objectList(array).map { TimeRule.fromJson(it) }
    }

    val checkpoints: List<Checkpoint> = run {
        val lineInfo = raw.optJSONObject("run_line_info") ?: return@run emptyList()
        val array = lineInfo.optJSONArray("point_list") ?: return@run emptyList()
        val result = ArrayList<Checkpoint>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("is_del", "0") != "0") continue
            if (item.has("ctrl_status") && item.optString("ctrl_status", "1") != "1") continue
            if (item.has("is_online") && item.optString("is_online", "1") == "0") continue
            val jingwei = item.optString("jingwei")
            val comma = jingwei.indexOf(',')
            if (comma <= 0) continue
            val latitude = jingwei.substring(0, comma).trim().toDoubleOrNull() ?: continue
            val longitude = jingwei.substring(comma + 1).trim().toDoubleOrNull() ?: continue
            if (latitude.isNaN() || longitude.isNaN()) continue
            result.add(
                Checkpoint(
                    id = item.optString("id"),
                    latitude = latitude,
                    longitude = longitude,
                    address = item.optString("address"),
                    index = item.optString("index"),
                ),
            )
        }
        if (result.isEmpty()) {
            // A capture without the optional filters still carries usable points.
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val jingwei = item.optString("jingwei")
                val comma = jingwei.indexOf(',')
                if (comma <= 0) continue
                val latitude = jingwei.substring(0, comma).trim().toDoubleOrNull() ?: continue
                val longitude = jingwei.substring(comma + 1).trim().toDoubleOrNull() ?: continue
                result.add(Checkpoint(item.optString("id"), latitude, longitude, item.optString("address"), item.optString("index")))
            }
        }
        result
    }

    val rawJson: String get() = raw.toString()

    fun toJson(): JSONObject = raw

    companion object {
        private val UNSUPPORTED_FLAGS = listOf(
            "is_check_face", "is_check_geetest", "is_check_device", "is_check_deepkonw",
            "is_check_geedevice", "is_check_shumeng", "is_capture_zone", "is_limit_range",
        )

        fun parse(json: JSONObject): RunRules = RunRules(json)

        fun parse(text: String): RunRules = RunRules(JSONObject(text))

        fun unsupportedFlags(rules: JSONObject): List<String> =
            UNSUPPORTED_FLAGS.filter { rules.optString(it, "0") != "0" }
    }

    /** Everything that makes this configuration unsafe or unsupported for an automated run. */
    fun blockers(): List<String> {
        val blockers = ArrayList<String>()
        for (flag in RunRules.unsupportedFlags(raw)) {
            blockers.add("配置需要尚未实现的分支：$flag")
        }
        if (gameId.isBlank() || termId.isBlank()) blockers.add("配置缺少 game_id / term_id")
        if (checkpoints.isEmpty()) blockers.add("配置没有可用打卡点位")
        if (timeRules.isEmpty()) blockers.add("配置缺少时段规则")
        if (runStartTime >= runEndTime) blockers.add("跑步入口时间窗无效")
        if (weekTime.isEmpty()) blockers.add("配置缺少可用星期")
        return blockers
    }

    /** The single effective rule for the given moment, plus why it does not apply. */
    fun windowCheck(epoch: Long): WindowCheck {
        val blockers = ArrayList<String>()
        val seconds = RunClock.secondsOfDay(epoch)
        if (seconds < runStartTime || seconds >= runEndTime) {
            blockers.add("当前不在学校跑步入口时间窗（${clock(runStartTime)}–${clock(runEndTime)}）")
        }
        val weekday = RunClock.local(epoch).dayOfWeek.value % 7
        if (weekday.toString() !in weekTime) {
            blockers.add("今天不在学校允许跑步的星期内")
        }
        val matches = timeRules.filter { seconds >= it.startTime && seconds < it.endTime }
        if (matches.size != 1) {
            blockers.add("当前时刻没有唯一可用的时段规则")
        }
        return WindowCheck(blockers.isEmpty(), blockers, matches.singleOrNull())
    }

    fun ruleFor(epoch: Long): TimeRule? = windowCheck(epoch).rule

    private fun clock(seconds: Int): String = String.format(
        java.util.Locale.ROOT,
        "%02d:%02d",
        seconds / 3600,
        (seconds % 3600) / 60,
    )
}

data class WindowCheck(
    val ok: Boolean,
    val blockers: List<String>,
    val rule: TimeRule?,
)

data class PlannedPoint(
    val id: String,
    val checkinT: Double,
    val latitude: Double,
    val longitude: Double,
    val distance: Double = 0.65,
    val arcM: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("checkin_t", checkinT)
        put("latitude", latitude)
        put("longitude", longitude)
        put("distance", distance)
        put("arc_m", arcM)
    }

    companion object {
        fun fromJson(source: JSONObject): PlannedPoint = PlannedPoint(
            id = source.optString("id"),
            checkinT = JsonUtil.optDoubleOrNull(source, "checkin_t") ?: 0.0,
            latitude = JsonUtil.optDoubleOrNull(source, "latitude") ?: 0.0,
            longitude = JsonUtil.optDoubleOrNull(source, "longitude") ?: 0.0,
            distance = JsonUtil.optDoubleOrNull(source, "distance") ?: 0.65,
            arcM = JsonUtil.optDoubleOrNull(source, "arc_m") ?: 0.0,
        )
    }
}

/**
 * One checkpoint the generated track was steered past.
 *
 * The plan keeps the configured coordinates (not the sampled check-in position) so a restart can
 * rebuild the exact same bend: the steering is a pure function of the seed and this list.
 */
data class PlannedVia(
    val id: String,
    val latitude: Double,
    val longitude: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("latitude", latitude)
        put("longitude", longitude)
    }

    companion object {
        fun fromJson(source: JSONObject): PlannedVia = PlannedVia(
            id = source.optString("id"),
            latitude = JsonUtil.optDoubleOrNull(source, "latitude") ?: 0.0,
            longitude = JsonUtil.optDoubleOrNull(source, "longitude") ?: 0.0,
        )
    }
}

/**
 * Immutable description of one simulated run. Everything except the corridor polyline is fixed
 * here, so the foreground service can rebuild the identical track after a process restart.
 */
data class PlannedRun(
    val seed: Long,
    val durationSec: Int,
    val distanceKm: String,
    val stepNum: Int,
    val stepInterval: Int = 60,
    val passTotal: Int,
    val passEffectiveNum: Int = 0,
    val areaValid: String = "1",
    val collector: String = "android-app",
    val points: List<PlannedPoint>,
    val rule: TimeRule,
    val createdAt: Long = RunClock.nowEpoch(),
    /** Checkpoints the track was steered past; empty for plans written before the steering slice. */
    val via: List<PlannedVia> = emptyList(),
) {
    val pointIds: List<String> get() = points.map { it.id }
    val distanceValue: Double get() = distanceKm.toDouble()

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 2)
        put("seed", seed)
        put("duration", durationSec)
        put("distance", distanceKm)
        put("step_num", stepNum)
        put("step_interval", stepInterval)
        put("pass_total", passTotal)
        put("pass_effective_num", passEffectiveNum)
        put("is_running_area_valid", areaValid)
        put("collector", collector)
        put("rule", rule.toJson())
        put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
        put("via", JSONArray().apply { via.forEach { put(it.toJson()) } })
        put("created_at", createdAt)
    }

    companion object {
        fun fromJson(source: JSONObject): PlannedRun {
            val points = ArrayList<PlannedPoint>()
            source.optJSONArray("points")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { points.add(PlannedPoint.fromJson(it)) }
                }
            }
            val via = ArrayList<PlannedVia>()
            source.optJSONArray("via")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { via.add(PlannedVia.fromJson(it)) }
                }
            }
            return PlannedRun(
                seed = JsonUtil.optLongOrNull(source, "seed") ?: 0L,
                durationSec = JsonUtil.optLongOrNull(source, "duration")?.toInt() ?: 0,
                distanceKm = source.optString("distance", "2.03"),
                stepNum = JsonUtil.optLongOrNull(source, "step_num")?.toInt() ?: 0,
                stepInterval = JsonUtil.optLongOrNull(source, "step_interval")?.toInt() ?: 60,
                passTotal = JsonUtil.optLongOrNull(source, "pass_total")?.toInt() ?: 0,
                passEffectiveNum = JsonUtil.optLongOrNull(source, "pass_effective_num")?.toInt() ?: 0,
                areaValid = source.optString("is_running_area_valid", "1"),
                collector = source.optString("collector", "android-app"),
                points = points,
                rule = TimeRule.fromJson(source.optJSONObject("rule") ?: JSONObject()),
                createdAt = JsonUtil.optLongOrNull(source, "created_at") ?: 0L,
                via = via,
            )
        }

        fun fromJson(text: String): PlannedRun = fromJson(JSONObject(text))
    }
}

data class RunReadiness(
    val ok: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val rule: TimeRule?,
    val plan: PlannedRun?,
)

/** Fixed run profile. The values are the parameters already accepted by the server. */
object RunProfile {
    const val TARGET_DISTANCE_KM = 2.03
    const val TARGET_PACE_SEC_PER_KM = 365.0
    const val STEPS_PER_KM = 832.5
    const val DEFAULT_STEP_INTERVAL = 60
    const val MIN_DISTANCE_KM = 0.2
}
