package com.lepao.companion.core

import org.json.JSONObject

enum class RunStage(val wire: String) {
    NEW("new"),
    PREPARED("prepared"),
    START_PENDING("start_pending"),
    RUNNING("running"),
    LOCATION_PENDING("location_pending"),
    UPLOADING("uploading"),
    STOP_PENDING("stop_pending"),
    STOP_ACKNOWLEDGED("stop_acknowledged"),
    SENSOR_PENDING("sensor_pending"),
    SUBMITTED("submitted"),
    VERIFIED("verified"),
    UNCERTAIN("uncertain"),
    FAILED("failed"),
    ;

    /** A stage where a new run must not be started because a write may still be in flight. */
    val blocksNewRun: Boolean
        get() = this in setOf(
            NEW, PREPARED, START_PENDING, RUNNING, LOCATION_PENDING, UPLOADING,
            STOP_PENDING, STOP_ACKNOWLEDGED, SENSOR_PENDING, UNCERTAIN,
        )

    val finished: Boolean get() = this == VERIFIED || this == FAILED || this == SUBMITTED

    companion object {
        fun fromWire(value: String?): RunStage = entries.firstOrNull { it.wire == value } ?: NEW
    }
}

/**
 * One run session. Written before every dispatch: a crash or connectivity loss must leave enough
 * information to stop automatic progress and never resubmit an ambiguous stop/upload.
 */
class RunState(
    var id: String,
    var stage: RunStage = RunStage.NEW,
    var createdAt: Long = RunClock.nowEpoch(),
    var updatedAt: Long = RunClock.nowEpoch(),
    var schoolId: String = "",
    var gameId: String = "",
    var termId: String = "",
    var rulesJson: String = "",
    var effectiveRuleJson: String = "",
    var planJson: String = "",
    var startTime: String? = null,
    var formalStr: String? = null,
    var startedElapsedRealtime: Long = 0L,
    var startedEpoch: Long = 0L,
    var nextLocationElapsed: Long = 0L,
    var heartbeatCount: Int = 0,
    var uploadsJson: String = "{}",
    var objectKeysJson: String = "{}",
    var uploadPending: String? = null,
    var finishManifestJson: String? = null,
    var recordId: String? = null,
    var stopResultJson: String? = null,
    var stopChecksJson: String? = null,
    var detailJson: String? = null,
    var verificationJson: String? = null,
    var lastError: String? = null,
    var lastNote: String? = null,
) {
    val plan: PlannedRun?
        get() = if (planJson.isBlank()) null else runCatching { PlannedRun.fromJson(planJson) }.getOrNull()

    val rules: RunRules?
        get() = if (rulesJson.isBlank()) null else runCatching { RunRules.parse(rulesJson) }.getOrNull()

    fun uploads(): JSONObject = JsonUtil.parseObjectOrNull(uploadsJson) ?: JSONObject()

    fun objectKeys(): Map<String, String> {
        val source = JsonUtil.parseObjectOrNull(objectKeysJson) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = source.optString(key)
        }
        return result
    }

    fun stopChecks(): Map<String, Boolean> = booleanMap(stopChecksJson)

    fun verification(): Map<String, Boolean> = booleanMap(verificationJson)

    val stopAcknowledged: Boolean get() = recordId != null

    fun elapsedSeconds(nowElapsedRealtime: Long): Int {
        if (startedElapsedRealtime <= 0L) return 0
        val delta = (nowElapsedRealtime - startedElapsedRealtime) / 1000L
        return if (delta < 0) 0 else delta.toInt()
    }

    fun touch() {
        updatedAt = RunClock.nowEpoch()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("stage", stage.wire)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("school_id", schoolId)
        put("game_id", gameId)
        put("term_id", termId)
        put("rules_json", rulesJson)
        put("effective_rule_json", effectiveRuleJson)
        put("plan_json", planJson)
        put("start_time", startTime ?: JSONObject.NULL)
        put("formal_str", formalStr ?: JSONObject.NULL)
        put("started_elapsed_realtime", startedElapsedRealtime)
        put("started_epoch", startedEpoch)
        put("next_location_elapsed", nextLocationElapsed)
        put("heartbeat_count", heartbeatCount)
        put("uploads_json", uploadsJson)
        put("object_keys_json", objectKeysJson)
        put("upload_pending", uploadPending ?: JSONObject.NULL)
        put("finish_manifest_json", finishManifestJson ?: JSONObject.NULL)
        put("record_id", recordId ?: JSONObject.NULL)
        put("stop_result_json", stopResultJson ?: JSONObject.NULL)
        put("stop_checks_json", stopChecksJson ?: JSONObject.NULL)
        put("detail_json", detailJson ?: JSONObject.NULL)
        put("verification_json", verificationJson ?: JSONObject.NULL)
        put("last_error", lastError ?: JSONObject.NULL)
        put("last_note", lastNote ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(source: JSONObject): RunState = RunState(
            id = source.optString("id"),
            stage = RunStage.fromWire(source.optString("stage")),
            createdAt = JsonUtil.optLongOrNull(source, "created_at") ?: 0L,
            updatedAt = JsonUtil.optLongOrNull(source, "updated_at") ?: 0L,
            schoolId = source.optString("school_id"),
            gameId = source.optString("game_id"),
            termId = source.optString("term_id"),
            rulesJson = source.optString("rules_json"),
            effectiveRuleJson = source.optString("effective_rule_json"),
            planJson = source.optString("plan_json"),
            startTime = JsonUtil.optStringOrNull(source, "start_time"),
            formalStr = JsonUtil.optStringOrNull(source, "formal_str"),
            startedElapsedRealtime = JsonUtil.optLongOrNull(source, "started_elapsed_realtime") ?: 0L,
            startedEpoch = JsonUtil.optLongOrNull(source, "started_epoch") ?: 0L,
            nextLocationElapsed = JsonUtil.optLongOrNull(source, "next_location_elapsed") ?: 0L,
            heartbeatCount = JsonUtil.optLongOrNull(source, "heartbeat_count")?.toInt() ?: 0,
            uploadsJson = source.optString("uploads_json", "{}"),
            objectKeysJson = source.optString("object_keys_json", "{}"),
            uploadPending = JsonUtil.optStringOrNull(source, "upload_pending"),
            finishManifestJson = JsonUtil.optStringOrNull(source, "finish_manifest_json"),
            recordId = JsonUtil.optStringOrNull(source, "record_id"),
            stopResultJson = JsonUtil.optStringOrNull(source, "stop_result_json"),
            stopChecksJson = JsonUtil.optStringOrNull(source, "stop_checks_json"),
            detailJson = JsonUtil.optStringOrNull(source, "detail_json"),
            verificationJson = JsonUtil.optStringOrNull(source, "verification_json"),
            lastError = JsonUtil.optStringOrNull(source, "last_error"),
            lastNote = JsonUtil.optStringOrNull(source, "last_note"),
        )

        private fun booleanMap(text: String?): Map<String, Boolean> {
            val source = JsonUtil.parseObjectOrNull(text) ?: return emptyMap()
            val result = LinkedHashMap<String, Boolean>()
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = source.optBoolean(key)
            }
            return result
        }
    }
}
