package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.ArtifactKind
import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.AuditLog
import com.lepao.companion.core.COMMON_FIELDS
import com.lepao.companion.core.DeliveryUncertain
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.Identity
import com.lepao.companion.core.Net
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.Protocol
import com.lepao.companion.core.RunStage
import com.lepao.companion.core.RunState
import com.lepao.companion.core.RunStore
import com.lepao.companion.core.SessionStore
import com.lepao.companion.core.TimeRule
import java.util.UUID
import kotlin.math.abs

/** Immutable facts of a started session, captured when the formal start was acknowledged. */
data class RunSessionInfo(
    val sessionId: String,
    val gameId: String,
    val termId: String,
    val startTime: String,
    val formalStr: String,
    val startedEpoch: Long,
    val startedElapsedRealtime: Long,
    val gyroscopeRequired: Boolean,
    val deviceFields: Map<String, String>,
    val ipAddress: String,
)

data class RunProgress(
    val elapsedSec: Int,
    val heartbeatCount: Int,
    val checkins: Int,
    val distanceKm: String,
)

/** Every outside dependency of the engine, so the state machine can be driven by fakes. */
data class RunDeps(
    val clock: RunTicker,
    val transport: RunTransport,
    val uploader: RunUploader,
    val track: TrackProvider,
    val artifacts: ArtifactProvider,
    val log: (String) -> Unit = {},
    val onProgress: (RunProgress) -> Unit = {},
    val stopping: () -> Boolean = { false },
)

/**
 * The persisted run state machine: heartbeats, completion upload, stop, sensor registration and
 * the read-only verification pass.
 *
 * Invariants enforced here (see `docs/run-engine.md` §3–§5):
 * - every write request is preceded by a `save()` of the pending stage;
 * - no write request is ever retried: any ambiguous outcome collapses the session to `uncertain`
 *   (or to the recoverable pending stage) and returns;
 * - a finished or blocked session never dispatches anything.
 */
class RunEngine(
    private val journal: RunJournal,
    private val plan: PlannedRun,
    private val rule: TimeRule,
    private val identity: Identity,
    private val session: RunSessionInfo,
    private val deps: RunDeps,
) {

    /** Entry point for both a fresh service start and a process-restart recovery. */
    fun execute(): RunStage {
        deps.log("engine execute stage=${journal.stage.wire} id=${session.sessionId}")
        return when (journal.stage) {
            RunStage.RUNNING -> runHeartbeats()
            RunStage.UPLOADING -> {
                if (journal.uploadPending != null) {
                    uncertain("上传结果未知，保留 upload_pending 等待人工核对")
                } else {
                    resumeUploads()
                }
            }
            RunStage.STOP_ACKNOWLEDGED -> afterStop()
            RunStage.SUBMITTED -> verifyOnly()
            RunStage.VERIFIED, RunStage.FAILED -> journal.stage
            RunStage.PREPARED, RunStage.START_PENDING -> uncertain("起跑结果未知，禁止自动重发")
            RunStage.LOCATION_PENDING -> uncertain("位置上报结果未知，禁止自动重发")
            RunStage.STOP_PENDING -> uncertain("结束请求结果未知，禁止自动重发")
            RunStage.SENSOR_PENDING -> uncertain("传感器登记结果未知，禁止自动重发")
            RunStage.NEW -> uncertain("会话尚未进入可恢复的运行阶段")
            RunStage.UNCERTAIN -> journal.stage
        }
    }

    // ---------------------------------------------------------------- heartbeats

    private fun runHeartbeats(): RunStage {
        val elapsedNow = elapsedSeconds()
        if (elapsedNow < 0) return uncertain("设备重启后计时归零，无法继续本次会话")
        val drift = abs((deps.clock.nowEpoch() - session.startedEpoch) - elapsedNow)
        if (drift > CLOCK_DRIFT_LIMIT_SEC) return uncertain("设备时钟变化超出恢复容差")

        val track = try {
            deps.track.build(plan)
        } catch (error: Exception) {
            return uncertain("轨迹重建失败：${error.message}")
        }
        while (true) {
            if (deps.stopping()) {
                journal.lastNote = "服务被系统停止，会话保持 ${journal.stage.wire}"
                journal.save()
                return journal.stage
            }
            val elapsed = elapsedSeconds()
            if (elapsed >= plan.durationSec + COMPLETION_GRACE_SEC) break
            // Every tick feeds the notification throttle (15 s in the service), not just heartbeats.
            emitProgress(elapsed)
            if (elapsed >= journal.nextLocationElapsed) {
                val failure = dispatchHeartbeat(elapsed, track)
                if (failure != null) return failure
            }
            deps.clock.sleep(POLL_INTERVAL_MS)
        }
        return finishRun()
    }

    private fun dispatchHeartbeat(elapsed: Double, track: List<TrackPoint>): RunStage? {
        // Persist the pending stage first: a crash here must never look like a clean RUNNING state.
        journal.moveTo(RunStage.LOCATION_PENDING)
        journal.save()
        val position = deps.track.position(track, elapsed)
        val extra = linkedMapOf<String, Any?>(
            "latitude" to position.lat.toString(),
            "longitude" to position.lng.toString(),
            "game_id" to session.gameId,
            "point_ids" to plan.pointIds.joinToString(","),
        )
        val response = try {
            deps.transport.api(
                Protocol.buildRequest(Endpoints.LOCATION, identity, extra, deps.clock.nowEpoch()),
            )
        } catch (error: DeliveryUncertain) {
            return uncertain("位置上报结果未知：${error.message}")
        } catch (error: Exception) {
            return uncertain("位置上报失败：${error.message}")
        }
        if (response.offlinePoints == null || response.offlinePoints.isNotEmpty()) {
            return uncertain("服务端返回了无法自动处理的 offlinePoints")
        }
        journal.moveTo(RunStage.RUNNING)
        journal.heartbeatCount += 1
        // Reference client behaviour (record 259921): 60/120/240/480 s, i.e. next threshold adds
        // the previous threshold, not the constant interval.
        journal.nextLocationElapsed = elapsed.toInt().toLong() + journal.nextLocationElapsed
        journal.save()
        emitProgress(elapsed)
        deps.log("heartbeat #${journal.heartbeatCount} at ${elapsed.toInt()}s")
        return null
    }

    // ---------------------------------------------------------------- completion

    private fun finishRun(): RunStage {
        val startEpoch = session.startTime.toLongOrNull() ?: return failed("缺少正式起跑时间")
        val endEpoch = deps.clock.nowEpoch()
        val manifest = try {
            FinishManifestBuilder.build(session.sessionId, plan, startEpoch, endEpoch, session.ipAddress)
        } catch (error: Exception) {
            return failed("结束材料生成失败：${error.message}")
        }
        val built = try {
            deps.artifacts.build(plan, startEpoch)
        } catch (error: Exception) {
            return failed("结束材料生成失败：${error.message}")
        }
        return beginUpload(manifest, built)
    }

    private fun beginUpload(manifest: FinishManifest, built: Map<String, ArtifactBlob>): RunStage {
        val kinds = requiredKinds()
        val missing = kinds.filter { built[it.name] == null }
        if (missing.isNotEmpty()) return failed("缺少材料：${missing.joinToString(",") { it.name }}")
        val startEpoch = session.startTime.toLongOrNull() ?: return failed("缺少正式起跑时间")
        val validation = ManifestValidator.validate(
            manifest = manifest,
            sessionId = session.sessionId,
            startTime = startEpoch,
            nowEpoch = deps.clock.nowEpoch(),
            plan = plan,
            rule = rule,
            artifactKinds = kinds.map { it.name }.toSet(),
            gyroscopeRequired = session.gyroscopeRequired,
        )
        if (!validation.ok) {
            return failed("结束材料本地校验失败：${validation.errors.joinToString("；")}")
        }
        var keys = journal.objectKeys()
        if (keys.isEmpty()) {
            val generated = LinkedHashMap<String, String>()
            kinds.forEach { generated[it.name] = ArtifactKinds.objectKey(it, deps.clock.nowEpoch()) }
            journal.putObjectKeys(generated)
            keys = generated
        }
        if (kinds.any { keys[it.name].isNullOrBlank() }) {
            return failed("已保存的对象键不完整")
        }
        journal.setManifest(manifest)
        journal.moveTo(RunStage.UPLOADING)
        journal.save()
        return uploadSequence(manifest, built)
    }

    private fun resumeUploads(): RunStage {
        val manifest = journal.manifest() ?: return uncertain("缺少结束材料，无法恢复上传")
        if (journal.objectKeys().isEmpty()) return uncertain("缺少对象键，无法恢复上传")
        val startEpoch = session.startTime.toLongOrNull() ?: return failed("缺少正式起跑时间")
        val built = try {
            deps.artifacts.build(plan, startEpoch)
        } catch (error: Exception) {
            return failed("结束材料重建失败：${error.message}")
        }
        return uploadSequence(manifest, built)
    }

    private fun uploadSequence(
        manifest: FinishManifest,
        built: Map<String, ArtifactBlob>,
    ): RunStage {
        for (kind in requiredKinds()) {
            val blob = built[kind.name] ?: return failed("缺少材料：${kind.name}")
            val failure = uploadOne(kind, blob)
            if (failure != null) return failure
        }
        return stopRun(manifest, built)
    }

    /** Uploads one artifact. Returns `null` on success, or the terminal stage to stop at. */
    private fun uploadOne(kind: ArtifactKind, blob: ArtifactBlob): RunStage? {
        val existing = journal.uploads()[kind.name]
        if (existing != null) {
            if (existing.sha256 != blob.sha256) {
                return uncertain("已上传材料 ${kind.name} 的内容发生变化，禁止覆盖")
            }
            return null
        }
        if (journal.uploadPending != null) {
            return uncertain("上一项上传结果未知，禁止自动重发")
        }
        val sts = fetchSts() ?: return journal.stage
        val credentials = OssCredentials.from(sts) ?: return failed("上传凭据不完整")
        val key = journal.objectKeys()[kind.name] ?: return failed("对象键缺失：${kind.name}")
        journal.uploadPending = kind.name
        journal.save()
        val receipt = try {
            deps.uploader.upload(credentials, kind, key, blob.body)
        } catch (error: Exception) {
            journal.lastError = "上传 ${kind.name} 结果未知：${error.message}"
            journal.save()
            return uncertain("上传 ${kind.name} 结果未知，保留 upload_pending 等待人工核对")
        }
        journal.putUpload(receipt)
        journal.uploadPending = null
        journal.save()
        deps.log("uploaded ${kind.name} ${blob.body.size}B")
        return null
    }

    private fun stopRun(manifest: FinishManifest, built: Map<String, ArtifactBlob>): RunStage {
        val keys = journal.objectKeys()
        val extra = StopFields.build(identity, session, manifest, keys)
        journal.moveTo(RunStage.STOP_PENDING)
        journal.save()
        val response = try {
            deps.transport.api(
                Protocol.buildRequest(
                    Endpoints.STOP,
                    identity,
                    extra,
                    deps.clock.nowEpoch(),
                    timeStr = session.formalStr,
                ),
            )
        } catch (error: DeliveryUncertain) {
            return uncertain("结束请求结果未知，禁止自动重发")
        } catch (error: Exception) {
            return uncertain("结束请求失败：${error.message}")
        }
        val ack = StopAck.from(response.data) ?: return uncertain("结束响应缺少有效 record_id")
        val checks = Verifier.stopChecks(
            ack = ack,
            uid = identity["uid"],
            startTime = session.startTime,
            gameId = session.gameId,
            distance = manifest.distance,
            usedTime = manifest.usedTime,
        )
        journal.recordId = ack.recordId
        journal.setStopResult(checks, response.data)
        journal.moveTo(RunStage.STOP_ACKNOWLEDGED)
        journal.save()
        if (checks.isEmpty() || checks.values.any { !it }) {
            journal.lastError = "结束响应与本地测量不一致：${checks.filterValues { !it }.keys.joinToString(",")}"
            journal.save()
            return RunStage.STOP_ACKNOWLEDGED
        }
        deps.log("stop acknowledged record_id=${ack.recordId}")
        return completeSensor(manifest, built)
    }

    private fun afterStop(): RunStage {
        if (!stopChecksAcceptable()) {
            journal.lastError = "结束响应校验未通过，禁止继续登记传感器或回查"
            journal.save()
            return journal.stage
        }
        val manifest = journal.manifest() ?: return uncertain("缺少结束材料，无法继续登记")
        if (journal.recordId == null) return uncertain("缺少 record_id，无法继续登记")
        val startEpoch = session.startTime.toLongOrNull() ?: return failed("缺少正式起跑时间")
        val built = try {
            deps.artifacts.build(plan, startEpoch)
        } catch (error: Exception) {
            return failed("结束材料重建失败：${error.message}")
        }
        return completeSensor(manifest, built)
    }

    private fun completeSensor(manifest: FinishManifest, built: Map<String, ArtifactBlob>): RunStage {
        val recordId = journal.recordId ?: return journal.stage
        if (!stopChecksAcceptable()) {
            journal.lastError = "结束响应校验未通过，禁止继续登记传感器或回查"
            journal.save()
            return journal.stage
        }
        if (!session.gyroscopeRequired) return submit()
        val sensor = ArtifactKinds.SENSOR
        val blob = built[sensor.name] ?: return failed("缺少传感器材料")
        val failure = uploadOne(sensor, blob)
        if (failure != null) return failure
        journal.moveTo(RunStage.SENSOR_PENDING)
        journal.save()
        val sensorKey = journal.objectKeys()[sensor.name] ?: return failed("传感器对象键缺失")
        try {
            deps.transport.api(
                Protocol.buildRequest(
                    Endpoints.GYROSCOPE,
                    identity,
                    linkedMapOf<String, Any?>(
                        "record_id" to recordId,
                        "gyroscope_file" to sensorKey,
                    ),
                    deps.clock.nowEpoch(),
                ),
            )
        } catch (error: DeliveryUncertain) {
            return uncertain("传感器登记结果未知，禁止自动重发")
        } catch (error: Exception) {
            return uncertain("传感器登记失败：${error.message}")
        }
        return submit()
    }

    private fun submit(): RunStage {
        journal.moveTo(RunStage.SUBMITTED)
        journal.save()
        return verifyOnly()
    }

    // ---------------------------------------------------------------- verification

    private fun verifyOnly(): RunStage {
        val recordId = journal.recordId ?: return journal.stage
        val manifest = journal.manifest()
        if (manifest == null) {
            journal.lastNote = "缺少结束材料，跳过回查"
            journal.save()
            return journal.stage
        }
        val fetched = try {
            DetailReader.fetch(deps.transport, identity, recordId, deps.clock.nowEpoch())
        } catch (error: Exception) {
            journal.lastNote = "回查失败，保留待人工复核：${error.message}"
            journal.save()
            return journal.stage
        }
        if (fetched == null) {
            journal.lastNote = "回查响应不完整，保留待人工复核"
            journal.save()
            return journal.stage
        }
        val checks = Verifier.detailChecks(
            detail = fetched.detail,
            uid = identity["uid"],
            startTime = session.startTime,
            gameId = session.gameId,
            distance = manifest.distance,
            usedTime = manifest.usedTime,
            stepNum = manifest.stepNum,
            routeKey = journal.objectKeys()[ArtifactKinds.ROUTE.name],
        )
        journal.setVerification(checks, fetched.data)
        journal.moveTo(if (checks.values.all { it }) RunStage.VERIFIED else RunStage.SUBMITTED)
        journal.save()
        deps.log("verify record=$recordId ok=${checks.values.all { it }}")
        return journal.stage
    }

    // ---------------------------------------------------------------- helpers

    /**
     * `report/getOssSts` only issues credentials: it has no server-side side effect, so a bounded
     * retry is safe (and the only write path allowed to retry). PUT/stop/location never retry.
     */
    private fun fetchSts(): ApiResponse? {
        var lastError: Exception? = null
        for (attempt in 1..STS_ATTEMPTS) {
            try {
                return deps.transport.api(
                    Protocol.buildRequest(Endpoints.OSS_STS, identity, emptyMap(), deps.clock.nowEpoch()),
                )
            } catch (error: DeliveryUncertain) {
                lastError = error
                journal.lastError = "获取上传凭据结果未知（第 $attempt 次）：${error.message}"
                journal.save()
                if (attempt < STS_ATTEMPTS) deps.clock.sleep(STS_RETRY_DELAY_MS)
            } catch (error: Exception) {
                failed("获取上传凭据失败：${error.message}")
                return null
            }
        }
        journal.lastError = "获取上传凭据连续 $STS_ATTEMPTS 次失败：${lastError?.message}"
        journal.save()
        return null
    }

    /** Cheap guard so a crash between stop and sensor registration cannot bypass the stop checks. */
    private fun stopChecksAcceptable(): Boolean {
        val checks = journal.stopChecks()
        return checks.isNotEmpty() && checks.values.all { it }
    }

    private fun requiredKinds(): List<ArtifactKind> = AndroidArtifactProvider.kindsFor(session.gyroscopeRequired)

    private fun elapsedSeconds(): Double =
        (deps.clock.elapsedRealtimeMs() - session.startedElapsedRealtime) / 1000.0

    private fun emitProgress(elapsed: Double) {
        val checkins = plan.points.count { it.checkinT <= elapsed }
        val fraction = (elapsed / plan.durationSec.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val distance = plan.distanceValue * fraction
        deps.onProgress(
            RunProgress(
                elapsedSec = elapsed.toInt(),
                heartbeatCount = journal.heartbeatCount,
                checkins = checkins,
                distanceKm = String.format(java.util.Locale.ROOT, "%.2f", distance),
            ),
        )
    }

    private fun uncertain(message: String): RunStage {
        journal.lastNote = "from=${journal.stage.wire}"
        journal.lastError = message
        journal.moveTo(RunStage.UNCERTAIN)
        journal.save()
        deps.log("uncertain: $message")
        return journal.stage
    }

    private fun failed(message: String): RunStage {
        journal.lastNote = "from=${journal.stage.wire}"
        journal.lastError = message
        journal.moveTo(RunStage.FAILED)
        journal.save()
        deps.log("failed: $message")
        return journal.stage
    }

    companion object {
        const val POLL_INTERVAL_MS = 500L
        const val STS_ATTEMPTS = 3
        const val STS_RETRY_DELAY_MS = 2_000L
        const val COMPLETION_GRACE_SEC = 1.6
        const val CLOCK_DRIFT_LIMIT_SEC = 120.0
    }
}

/**
 * The 38-field `Run/stopRunV278` body, matching the verified client field for field.
 *
 * The captured client sends `oaid`/`mac` because the user had once started a run in the official
 * app. A plain login capture never sees those, so both get a deterministic fallback: the
 * PC capture observed `02:00:00:00:00:00` as the MAC, and the OAID is derived from the captured
 * device identity. Real captured values always win.
 */
object StopFields {

    const val FALLBACK_MAC = "02:00:00:00:00:00"
    private const val OAID_SEED_PREFIX = "lepao-oaid:"

    private val RESERVED: Set<String> = COMMON_FIELDS.toSet() + setOf("timestamp", "nonce", "sign", "file")

    /** Device dynamic fields of the stop body: captured values first, then the deterministic fallback. */
    fun deviceFields(identity: Identity, captured: Map<String, String>): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        captured.forEach { (key, value) ->
            if (key !in RESERVED && value.isNotBlank()) fields[key] = value
        }
        if (fields["mac"].isNullOrBlank()) fields["mac"] = FALLBACK_MAC
        if (fields["oaid"].isNullOrBlank()) fields["oaid"] = derivedOaid(identity)
        return fields
    }

    /** Stable OAID stand-in derived from the observed device id (never random per request). */
    fun derivedOaid(identity: Identity): String =
        UUID.nameUUIDFromBytes(
            (OAID_SEED_PREFIX + identity["mobileDeviceId"]).toByteArray(Charsets.UTF_8),
        ).toString()

    fun build(
        identity: Identity,
        session: RunSessionInfo,
        manifest: FinishManifest,
        keys: Map<String, String>,
    ): LinkedHashMap<String, Any?> {
        val extra = LinkedHashMap<String, Any?>()
        deviceFields(identity, session.deviceFields).forEach { (key, value) -> extra[key] = value }
        extra["term_id"] = session.termId
        extra["game_id"] = session.gameId
        extra["start_time"] = session.startTime
        extra["end_time"] = manifest.endTime.toString()
        extra["used_time"] = manifest.usedTime.toString()
        extra["distance"] = manifest.distance
        extra["step_num"] = manifest.stepNum.toString()
        extra["file_img"] = keys[ArtifactKinds.SCREENSHOT.name] ?: ""
        extra["record_file"] = keys[ArtifactKinds.ROUTE.name] ?: ""
        extra["record_img"] = ""
        extra["log_data"] = manifest.logDataJson()
        extra["step_info"] = manifest.stepInfoJson()
        extra["run_check_res"] = "[]"
        extra["pass_total"] = manifest.passTotal
        extra["pass_effective_num"] = manifest.passEffectiveNum
        extra["is_running_area_valid"] = manifest.areaValid
        extra["ip"] = manifest.ip
        extra["record_str"] = Protocol.recordSignature(
            identity["school_id"],
            identity["uid"],
            session.formalStr,
        )
        extra["gee_token"] = ""
        extra["newMobileDeviceId"] = ""
        extra["run_face_verify_id"] = "1231231231231"
        extra["is_timeout_zfb"] = 0
        return extra
    }
}

/** Android wiring: turns a persisted `RunState` back into a runnable engine. */
object RunSessions {

    fun create(
        context: Context,
        state: RunState,
        onProgress: (RunProgress) -> Unit = {},
        log: (String) -> Unit = {},
        stopping: () -> Boolean = { false },
    ): RunEngine? {
        val session = SessionStore.load() ?: return null
        val plan = state.plan ?: return null
        val startTime = state.startTime ?: return null
        val formalStr = state.formalStr ?: return null
        if (state.startedElapsedRealtime <= 0L) return null
        val audit = AuditLog(context, RunStore.auditFile(state.id))
        val transport = CoreRunTransport(
            com.lepao.companion.core.ApiTransport(session, write = true, audit = audit),
        )
        val uploader = CoreRunUploader(com.lepao.companion.core.OssUploader(audit))
        val info = RunSessionInfo(
            sessionId = state.id,
            gameId = state.gameId,
            termId = state.termId,
            startTime = startTime,
            formalStr = formalStr,
            startedEpoch = state.startedEpoch,
            startedElapsedRealtime = state.startedElapsedRealtime,
            gyroscopeRequired = state.rules?.gyroscopeType == "1",
            deviceFields = session.deviceFields,
            ipAddress = Net.localIpv4(),
        )
        val deps = RunDeps(
            clock = AndroidRunTicker(),
            transport = transport,
            uploader = uploader,
            track = AndroidTrackProvider(context),
            artifacts = AndroidArtifactProvider(context),
            log = log,
            onProgress = onProgress,
            stopping = stopping,
        )
        return RunEngine(
            journal = StateJournal(state),
            plan = plan,
            rule = plan.rule,
            identity = session.identity,
            session = info,
            deps = deps,
        )
    }
}
