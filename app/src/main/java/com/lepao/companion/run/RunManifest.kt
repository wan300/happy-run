package com.lepao.companion.run

import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.TimeRule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.round

/**
 * One check-in record inside `measurements.log_data`.
 *
 * `longtitude` keeps the client's historical misspelling: the server schema is what matters.
 */
data class CheckinLog(
    val distance: Double,
    val latitude: Double,
    val longtitude: Double,
    val pointId: Int,
    val time: Long,
)

/**
 * The completion manifest, exactly the field set `validate_finish` in the PC client accepts.
 *
 * Kept as a plain immutable value so validation is a pure JVM function; only the persistence
 * adapter knows about `org.json`.
 */
data class FinishManifest(
    val sessionId: String,
    val sourceKind: String = "device_measurements",
    val endTime: Long,
    val usedTime: Int,
    val distance: String,
    val stepNum: Int,
    val stepInterval: Int,
    val stepBuckets: List<Int>,
    val logData: List<CheckinLog>,
    val passTotal: Int,
    val passEffectiveNum: Int,
    val areaValid: String,
    val ip: String,
    val runCheckRes: List<String> = emptyList(),
) {

    fun logDataJson(): String = CompactJson.array(logData.size) { index ->
        val log = logData[index]
        CompactJson.obj(
            "distance" to CompactJson.number(log.distance),
            "latitude" to CompactJson.number(log.latitude),
            "longtitude" to CompactJson.number(log.longtitude),
            "point_id" to log.pointId.toString(),
            "time" to log.time.toString(),
        )
    }

    fun stepInfoJson(): String = CompactJson.obj(
        "interval" to stepInterval.toString(),
        "list" to CompactJson.array(stepBuckets.size) { stepBuckets[it].toString() },
    )
}

/** `java.util.Random` step buckets shaped like the reference client (60 s interval, sum ≈ 0.94·steps). */
object StepBuckets {

    fun of(durationSec: Int, stepNum: Int, seed: Long): List<Int> {
        val minutes = durationSec / 60
        if (minutes <= 0) return emptyList()
        val random = Random(seed + 3)
        val weights = DoubleArray(minutes) { 0.86 + random.nextDouble() * (1.05 - 0.86) }
        val total = weights.sum()
        if (total <= 0.0) return List(minutes) { 0 }
        val scale = (stepNum * 0.94) / total
        return weights.map { round(it * scale).toInt().coerceAtLeast(0) }
    }
}

/** Builds the manifest the moment the finish flow starts; no I/O, no clock access. */
object FinishManifestBuilder {

    fun build(
        sessionId: String,
        plan: PlannedRun,
        startEpoch: Long,
        endEpoch: Long,
        ip: String,
    ): FinishManifest {
        val logs = plan.points.map { point ->
            CheckinLog(
                distance = point.distance,
                latitude = f32(point.latitude),
                longtitude = f32(point.longitude),
                pointId = point.id.toIntOrNull()
                    ?: throw IllegalStateException("打卡点 ID 必须是数字：${point.id}"),
                time = startEpoch + round(point.checkinT).toLong(),
            )
        }
        return FinishManifest(
            sessionId = sessionId,
            endTime = endEpoch,
            usedTime = plan.durationSec,
            distance = plan.distanceKm,
            stepNum = plan.stepNum,
            stepInterval = plan.stepInterval,
            stepBuckets = StepBuckets.of(plan.durationSec, plan.stepNum, plan.seed),
            logData = logs,
            passTotal = plan.passTotal,
            passEffectiveNum = plan.passEffectiveNum,
            areaValid = plan.areaValid,
            ip = ip,
        )
    }

    /** Match the Android writer: measured coordinates travel as 32-bit floats widened to double. */
    fun f32(value: Double): Double =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value.toFloat()).getFloat(0).toDouble()
}

data class ValidationResult(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()

    companion object {
        val VALID = ValidationResult(emptyList())
    }
}

/**
 * Local mirror of the PC `validate_finish` gate, run before the first upload is dispatched.
 *
 * A rejected manifest can never reach the server: the run fails locally instead of creating a
 * record the school would refuse.
 */
object ManifestValidator {

    val REQUIRED_FIELDS: List<String> = listOf(
        "end_time", "used_time", "distance", "step_num", "step_info", "log_data",
        "pass_total", "pass_effective_num", "is_running_area_valid", "ip", "run_check_res",
    )

    private val DISTANCE_PATTERN = Regex("\\d+\\.\\d{2}")

    fun validate(
        manifest: FinishManifest,
        sessionId: String,
        startTime: Long,
        nowEpoch: Long,
        plan: PlannedRun,
        rule: TimeRule,
        artifactKinds: Set<String>,
        gyroscopeRequired: Boolean,
    ): ValidationResult {
        val errors = ArrayList<String>()
        if (manifest.sessionId != sessionId || manifest.sourceKind != "device_measurements") {
            errors.add("结束材料没有指向本次真实测量会话")
        }
        if (!DISTANCE_PATTERN.matches(manifest.distance)) {
            errors.add("距离必须保留两位小数的测量值")
        }
        val distance = manifest.distance.toDoubleOrNull()
        if (distance == null || distance < 0.2) {
            errors.add("距离低于可用下限 0.2 km")
        }
        if (manifest.endTime < 0 || manifest.usedTime < 0 || manifest.stepNum < 0 ||
            manifest.passTotal < 0 || manifest.passEffectiveNum < 0
        ) {
            errors.add("测量字段必须是非负整数")
        }
        val measuredSpan = manifest.endTime - startTime
        if (manifest.usedTime <= 0 || manifest.usedTime > measuredSpan) {
            errors.add("用时与起止时间不一致")
        }
        if (manifest.endTime > nowEpoch + 5) {
            errors.add("结束时间晚于本机当前时刻")
        }
        if (manifest.stepInterval != PlanPolicy.STEP_INTERVAL_SEC) {
            errors.add("步数分桶间隔必须是 ${PlanPolicy.STEP_INTERVAL_SEC} s")
        }
        if (manifest.stepBuckets.any { it < 0 }) {
            errors.add("步数分桶必须是非负整数")
        }
        if (manifest.passEffectiveNum > manifest.passTotal) {
            errors.add("有效打卡数不能超过总打卡数")
        }
        if (manifest.areaValid != "0" && manifest.areaValid != "1") {
            errors.add("跑区有效标记非法")
        }
        if (!IpLiteral.isValid(manifest.ip)) {
            errors.add("缺少可解析的网络 IP 观测值")
        }
        validateLogData(manifest, plan, startTime, errors)
        if (distance != null) {
            validateRules(manifest, distance, rule, errors)
        }
        val required = LinkedHashSet<String>()
        required.add(com.lepao.companion.core.ArtifactKinds.SCREENSHOT.name)
        required.add(com.lepao.companion.core.ArtifactKinds.ROUTE.name)
        if (gyroscopeRequired) required.add(com.lepao.companion.core.ArtifactKinds.SENSOR.name)
        if (artifactKinds != required) {
            errors.add("材料集合与本次配置不一致：需要 $required，实际 $artifactKinds")
        }
        return ValidationResult(errors)
    }

    private fun validateLogData(
        manifest: FinishManifest,
        plan: PlannedRun,
        startTime: Long,
        errors: MutableList<String>,
    ) {
        if (manifest.logData.size != plan.points.size) {
            errors.add("打卡记录数与本次点位不一致：${manifest.logData.size} != ${plan.points.size}")
        }
        val expectedIds = plan.points.mapNotNull { it.id.toIntOrNull() }.toSet()
        val actualIds = manifest.logData.map { it.pointId }.toSet()
        if (expectedIds != actualIds) {
            errors.add("打卡点位集合与正式起跑不一致")
        }
        for (log in manifest.logData) {
            if (!log.distance.isFinite() || !log.latitude.isFinite() || !log.longtitude.isFinite()) {
                errors.add("打卡记录包含非有限数值")
                continue
            }
            if (log.latitude < -90.0 || log.latitude > 90.0) {
                errors.add("打卡点纬度越界")
            }
            if (log.longtitude < -180.0 || log.longtitude > 180.0) {
                errors.add("打卡点经度越界")
            }
            if (log.time > 0 && (log.time < startTime || log.time > manifest.endTime)) {
                errors.add("打卡时间落在本次测量区间之外")
            }
            if (log.time <= 0 && (log.latitude != 0.0 || log.longtitude != 0.0)) {
                errors.add("未打卡点的坐标必须为零")
            }
        }
    }

    private fun validateRules(
        manifest: FinishManifest,
        distance: Double,
        rule: TimeRule,
        errors: MutableList<String>,
    ) {
        if (distance < rule.minDistanceKm || distance > rule.maxDistanceKm) {
            errors.add("测量里程不在学校有效跑步区间内")
        }
        val pace = manifest.usedTime / distance
        if (pace < rule.lowerPace || pace > rule.upperPace) {
            errors.add("测量配速不在学校有效跑步区间内")
        }
        val visited = manifest.logData.count { it.time > 0 }
        if (visited < rule.minLogNum) {
            errors.add("有效打卡数 $visited 未达到学校要求的 ${rule.minLogNum}")
        }
    }
}

/** IPv4/IPv6 literal recognition without touching the network stack. */
object IpLiteral {

    fun isValid(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        return if (text.contains(':')) isIpv6(text) else isIpv4(text)
    }

    private fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            part.isNotEmpty() && part.all { it.isDigit() } && number in 0..255
        }
    }

    private fun isIpv6(text: String): Boolean {
        val body = text.substringBefore('%')
        if (body.length < 2 || body.length > 45) return false
        if (!body.all { it == ':' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
        if (body.count { it == ':' } > 7) return false
        if (body.contains(":::")) return false
        val groups = body.split(':')
        if (groups.count { it.isNotEmpty() } > 8) return false
        if (groups.any { it.length > 4 }) return false
        return groups.any { it.isNotEmpty() }
    }
}

/** Minimal compact JSON writer: stable, org.json-free and identical to the client's separators. */
object CompactJson {

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { "${quote(it.first)}:${it.second}" }

    fun array(size: Int, element: (Int) -> String): String =
        (0 until size).joinToString(",", "[", "]") { element(it) }

    fun number(value: Double): String {
        if (!value.isFinite()) throw IllegalArgumentException("JSON numbers must be finite")
        return value.toString()
    }

    fun quote(text: String): String {
        val builder = StringBuilder(text.length + 2)
        builder.append('"')
        for (char in text) {
            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char < ' ' -> builder.append(String.format(java.util.Locale.ROOT, "\\u%04x", char.code))
                else -> builder.append(char)
            }
        }
        return builder.append('"').toString()
    }
}
