package com.lepao.companion.run

import com.lepao.companion.core.JsonUtil
import com.lepao.companion.core.OssReceipt
import com.lepao.companion.core.RunState
import com.lepao.companion.core.RunStore
import com.lepao.companion.core.RunStage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The engine's single view of persisted run state.
 *
 * Every mutation is explicit and every dispatch is preceded by [save]; the Android
 * implementation writes through `RunStore`, while tests use an in-memory journal. This is also
 * why the engine itself never touches `org.json`.
 */
interface RunJournal {
    val stage: RunStage
    fun moveTo(stage: RunStage)

    var heartbeatCount: Int
    var nextLocationElapsed: Long

    /** `location_septal` of the formal start; persisted with the session, defaulted on recovery. */
    var locationInterval: Int

    var uploadPending: String?
    var recordId: String?
    var lastError: String?
    var lastNote: String?

    fun uploads(): Map<String, OssReceipt>
    fun putUpload(receipt: OssReceipt)

    fun objectKeys(): Map<String, String>
    fun putObjectKeys(keys: Map<String, String>)

    fun manifest(): FinishManifest?
    fun setManifest(manifest: FinishManifest)

    fun stopChecks(): Map<String, Boolean>
    fun setStopResult(checks: Map<String, Boolean>, data: Map<String, Any?>)

    fun verification(): Map<String, Boolean>
    fun setVerification(checks: Map<String, Boolean>, data: Map<String, Any?>)

    fun save()
}

/** `RunJournal` over the encrypted `RunStore` document. */
class StateJournal(
    private val state: RunState,
    private val persist: (RunState) -> Unit = RunStore::save,
) : RunJournal {

    override val stage: RunStage get() = state.stage

    override fun moveTo(stage: RunStage) {
        state.stage = stage
    }

    override var heartbeatCount: Int
        get() = state.heartbeatCount
        set(value) {
            state.heartbeatCount = value
        }

    override var nextLocationElapsed: Long
        get() = state.nextLocationElapsed
        set(value) {
            state.nextLocationElapsed = value
        }

    override var locationInterval: Int
        get() {
            val stored = JsonUtil.parseObjectOrNull(state.effectiveRuleJson)
                ?.let { JsonUtil.optLongOrNull(it, "location_septal") }
            if (stored != null && stored > 0) return stored.toInt()
            if (state.startedElapsedRealtime > 0L && state.heartbeatCount == 0 && state.nextLocationElapsed > 0) {
                return state.nextLocationElapsed.toInt()
            }
            return DEFAULT_LOCATION_INTERVAL
        }
        set(value) {
            val document = JsonUtil.parseObjectOrNull(state.effectiveRuleJson) ?: JSONObject()
            document.put("location_septal", value)
            state.effectiveRuleJson = document.toString()
        }

    override var uploadPending: String?
        get() = state.uploadPending
        set(value) {
            state.uploadPending = value
        }

    override var recordId: String?
        get() = state.recordId
        set(value) {
            state.recordId = value
        }

    override var lastError: String?
        get() = state.lastError
        set(value) {
            state.lastError = value
        }

    override var lastNote: String?
        get() = state.lastNote
        set(value) {
            state.lastNote = value
        }

    override fun uploads(): Map<String, OssReceipt> {
        val source = state.uploads()
        val result = LinkedHashMap<String, OssReceipt>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val kind = keys.next()
            val item = source.optJSONObject(kind) ?: continue
            result[kind] = OssReceipt(
                kind = kind,
                key = item.optString("key"),
                sha256 = item.optString("sha256"),
                bytes = item.optInt("bytes"),
                etag = item.optString("etag"),
                requestId = JsonUtil.optStringOrNull(item, "request_id"),
            )
        }
        return result
    }

    override fun putUpload(receipt: OssReceipt) {
        val source = state.uploads()
        source.put(
            receipt.kind,
            JSONObject().apply {
                put("key", receipt.key)
                put("sha256", receipt.sha256)
                put("bytes", receipt.bytes)
                put("etag", receipt.etag)
                put("request_id", receipt.requestId ?: JSONObject.NULL)
            },
        )
        state.uploadsJson = source.toString()
    }

    override fun objectKeys(): Map<String, String> = state.objectKeys()

    override fun putObjectKeys(keys: Map<String, String>) {
        val object_ = JSONObject()
        keys.forEach { (kind, key) -> object_.put(kind, key) }
        state.objectKeysJson = object_.toString()
    }

    override fun manifest(): FinishManifest? {
        val text = state.finishManifestJson ?: return null
        return ManifestCodec.fromJson(JsonUtil.parseObjectOrNull(text))
    }

    override fun setManifest(manifest: FinishManifest) {
        state.finishManifestJson = ManifestCodec.toJson(manifest).toString()
    }

    override fun stopChecks(): Map<String, Boolean> = state.stopChecks()

    override fun setStopResult(checks: Map<String, Boolean>, data: Map<String, Any?>) {
        val object_ = JSONObject()
        data.forEach { (key, value) -> object_.put(key, value ?: JSONObject.NULL) }
        state.stopResultJson = object_.toString()
        val checksObject = JSONObject()
        checks.forEach { (key, value) -> checksObject.put(key, value) }
        state.stopChecksJson = checksObject.toString()
    }

    override fun verification(): Map<String, Boolean> = state.verification()

    override fun setVerification(checks: Map<String, Boolean>, data: Map<String, Any?>) {
        val object_ = JSONObject()
        data.forEach { (key, value) -> object_.put(key, value ?: JSONObject.NULL) }
        state.detailJson = object_.toString()
        val checksObject = JSONObject()
        checks.forEach { (key, value) -> checksObject.put(key, value) }
        state.verificationJson = checksObject.toString()
    }

    override fun save() = persist(state)

    companion object {
        const val DEFAULT_LOCATION_INTERVAL = 60
    }
}

/** `FinishManifest` <-> persisted JSON. Android-only: unit tests use typed manifests directly. */
object ManifestCodec {

    fun toJson(manifest: FinishManifest): JSONObject = JSONObject().apply {
        put("session_id", manifest.sessionId)
        put("source_kind", manifest.sourceKind)
        put(
            "measurements",
            JSONObject().apply {
                put("end_time", manifest.endTime)
                put("used_time", manifest.usedTime)
                put("distance", manifest.distance)
                put("step_num", manifest.stepNum)
                put(
                    "step_info",
                    JSONObject().apply {
                        put("interval", manifest.stepInterval)
                        put("list", JSONArray().apply { manifest.stepBuckets.forEach { put(it) } })
                    },
                )
                put(
                    "log_data",
                    JSONArray().apply {
                        manifest.logData.forEach { log ->
                            put(
                                JSONObject().apply {
                                    put("distance", log.distance)
                                    put("latitude", log.latitude)
                                    put("longtitude", log.longtitude)
                                    put("point_id", log.pointId)
                                    put("time", log.time)
                                },
                            )
                        }
                    },
                )
                put("pass_total", manifest.passTotal)
                put("pass_effective_num", manifest.passEffectiveNum)
                put("is_running_area_valid", manifest.areaValid)
                put("ip", manifest.ip)
                put("run_check_res", JSONArray().apply { manifest.runCheckRes.forEach { put(it) } })
            },
        )
    }

    fun fromJson(source: JSONObject?): FinishManifest? {
        if (source == null) return null
        val measured = source.optJSONObject("measurements") ?: return null
        val stepInfo = measured.optJSONObject("step_info") ?: return null
        val buckets = ArrayList<Int>()
        stepInfo.optJSONArray("list")?.let { array ->
            for (index in 0 until array.length()) buckets.add(array.optInt(index))
        }
        val logs = ArrayList<CheckinLog>()
        measured.optJSONArray("log_data")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                logs.add(
                    CheckinLog(
                        distance = item.optDouble("distance"),
                        latitude = item.optDouble("latitude"),
                        longtitude = item.optDouble("longtitude"),
                        pointId = item.optInt("point_id"),
                        time = item.optLong("time"),
                    ),
                )
            }
        }
        val checkRes = ArrayList<String>()
        measured.optJSONArray("run_check_res")?.let { array ->
            for (index in 0 until array.length()) checkRes.add(array.opt(index).toString())
        }
        return FinishManifest(
            sessionId = source.optString("session_id"),
            sourceKind = source.optString("source_kind", "device_measurements"),
            endTime = measured.optLong("end_time"),
            usedTime = measured.optInt("used_time"),
            distance = measured.optString("distance"),
            stepNum = measured.optInt("step_num"),
            stepInterval = stepInfo.optInt("interval", 60),
            stepBuckets = buckets,
            logData = logs,
            passTotal = measured.optInt("pass_total"),
            passEffectiveNum = measured.optInt("pass_effective_num"),
            areaValid = measured.optString("is_running_area_valid", "1"),
            ip = measured.optString("ip"),
            runCheckRes = checkRes,
        )
    }
}
