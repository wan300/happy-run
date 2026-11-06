package com.lepao.companion.run

import android.content.Context
import android.os.SystemClock
import com.lepao.companion.core.ApiTransport
import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.AuditLog
import com.lepao.companion.core.DeliveryUncertain
import com.lepao.companion.core.DiagLog
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.LepaoException
import com.lepao.companion.core.OperationResult
import com.lepao.companion.core.Protocol
import com.lepao.companion.core.ReadinessView
import com.lepao.companion.core.RunClock
import com.lepao.companion.core.RunStage
import com.lepao.companion.core.RunState
import com.lepao.companion.core.RunStore
import com.lepao.companion.core.SessionContext
import com.lepao.companion.core.SessionStore
import com.lepao.companion.core.TokenService
import org.json.JSONObject

/**
 * UI-facing run lifecycle. All calls block on network I/O and must run off the main thread.
 *
 * The foreground engine ([RunForegroundService]) drives an already-started session; this object
 * performs the pre-run read, the formal start and a read-only re-verification. Signatures are the
 * frozen UI contract: `inspect/start/verify` returning `ReadinessView`/`OperationResult`.
 */
object RunController {

    /**
     * `UNCERTAIN` is included because a read-only `recordDetail` pass is the documented way to
     * reconcile an ambiguous write: it never resubmits anything, and the session stays blocked for
     * a new run until the pass resolves it to `verified`/`submitted`. `STOP_PENDING` has no
     * `record_id`, so it is never verifiable and must not be reopened here.
     */
    private val VERIFY_STAGES = setOf(
        RunStage.SUBMITTED,
        RunStage.VERIFIED,
        RunStage.STOP_ACKNOWLEDGED,
        RunStage.UNCERTAIN,
    )
    private val STR_PATTERN = Regex("[0-9a-f]{32}")

    fun inspect(context: Context): ReadinessView {
        val session = SessionStore.load() ?: return ReadinessView.blocked("尚未获取登录态")
        val blocking = RunStore.blockingRun()
        if (blocking != null) {
            return ReadinessView.blocked(
                "存在未结束的会话 ${blocking.id}（${blocking.stage.wire}），请先完成或核对",
            )
        }
        return try {
            val rules = ConfigService.fetch(context, session)
            AutoPlanner.plan(context, rules, session.identity, RunClock.nowEpoch())
        } catch (error: LepaoException) {
            ReadinessView.blocked(error.message ?: "读取跑步配置失败")
        } catch (error: Exception) {
            ReadinessView.blocked("读取跑步配置失败：${error.message}")
        }
    }

    fun selectEastCampus(context: Context): ReadinessView {
        val session = SessionStore.load() ?: return ReadinessView.blocked("尚未获取登录态")
        if (RunStore.blockingRun() != null) {
            return ReadinessView.blocked("请先完成或核对当前会话，再切换东校区")
        }
        return try {
            val rules = ConfigService.selectEastCampus(context, session)
            AutoPlanner.plan(context, rules, session.identity, RunClock.nowEpoch())
        } catch (_: DeliveryUncertain) {
            ReadinessView.blocked("东校区切换结果待核对，请刷新配置")
        } catch (error: Exception) {
            ReadinessView.blocked("切换或核对东校区失败：${error.message}")
        }
    }

    fun start(context: Context): OperationResult {
        val stored = SessionStore.load()
            ?: return OperationResult(false, "尚未获取登录态", RunStore.latest())
        RunStore.blockingRun()?.let { blocking ->
            return OperationResult(
                false,
                "存在未结束的会话 ${blocking.id}（${blocking.stage.wire}），请先完成或核对",
                blocking,
            )
        }

        // Auto renewal never blocks a run: a stale pair may still be inside its usable window.
        val warnings = ArrayList<String>()
        var session: SessionContext = stored
        try {
            val renewal = TokenService.maybeAutoRefresh(context, stored, minDaysLeft = MIN_REFRESH_DAYS)
            session = renewal.session
            if (renewal.attempted && !renewal.refreshed) {
                warnings.add("登录态自动续期失败：${renewal.reason}")
            }
        } catch (error: Exception) {
            warnings.add("登录态自动续期失败：${error.message}")
        }

        val rules = try {
            ConfigService.fetch(context, session)
        } catch (error: DeliveryUncertain) {
            return OperationResult(false, "读取跑步配置结果未知，请稍后重试", RunStore.latest())
        } catch (error: Exception) {
            return OperationResult(false, "读取跑步配置失败：${error.message}", RunStore.latest())
        }

        val now = RunClock.nowEpoch()
        val readiness = try {
            AutoPlanner.plan(context, rules, session.identity, now)
        } catch (error: Exception) {
            return OperationResult(false, "规划跑步路线失败：${error.message}", RunStore.latest())
        }
        if (!readiness.ok) {
            return OperationResult(false, readiness.blockers.joinToString("；"), null)
        }
        val plan = readiness.plan
            ?: return OperationResult(false, "规划没有生成可执行的跑步参数", null)
        val rule = readiness.rule
            ?: return OperationResult(false, "当前时刻没有可用的时段规则", null)

        val state = RunState(
            id = RunStore.newId(),
            stage = RunStage.PREPARED,
            createdAt = now,
            updatedAt = now,
            schoolId = rules.schoolId,
            gameId = rules.gameId,
            termId = rules.termId,
            rulesJson = rules.rawJson,
            effectiveRuleJson = rule.toJson().toString(),
            planJson = plan.toJson().toString(),
            lastNote = warnings.joinToString("；").ifBlank { null },
        )
        RunStore.save(state)
        warnings.forEach { warn -> com.lepao.companion.core.DiagLog.warn("run start: $warn") }

        val transport = runTransport(context, session, state.id)
        val extra = linkedMapOf<String, Any?>(
            "game_id" to rules.gameId,
            "point_ids" to plan.pointIds.joinToString(","),
        )

        // Persist BEFORE dispatch: an ambiguous start must never look prepared.
        state.stage = RunStage.START_PENDING
        RunStore.save(state)
        val response = try {
            transport.api(
                Protocol.buildRequest(Endpoints.START, session.identity, extra, RunClock.nowEpoch()),
            )
        } catch (error: DeliveryUncertain) {
            return startUncertain(state, "起跑请求结果未知：${error.message}")
        } catch (error: Exception) {
            return startUncertain(state, "起跑请求失败：${error.message}")
        }

        val formalStr = response.text("str")
        val timestamp = response.text("timestamp")
        val interval = response.text("location_septal")?.toIntOrNull()
        val invalid = when {
            response.offlinePoints == null || response.offlinePoints.isNotEmpty() ->
                "正式起跑返回了非空或缺失的 offlinePoints"
            formalStr == null || !STR_PATTERN.matches(formalStr) ->
                "正式起跑返回的 str 不符合 32 位十六进制格式"
            timestamp == null || timestamp.isEmpty() || !timestamp.all { it.isDigit() } ->
                "正式起跑返回的 timestamp 不是数字"
            interval == null || interval <= 0 ->
                "正式起跑返回的 location_septal 不是正整数"
            else -> null
        }
        if (invalid != null) return startUncertain(state, invalid)

        state.stage = RunStage.RUNNING
        state.formalStr = formalStr
        state.startTime = timestamp
        state.startedEpoch = RunClock.nowEpoch()
        state.startedElapsedRealtime = SystemClock.elapsedRealtime()
        state.nextLocationElapsed = interval!!.toLong()
        state.heartbeatCount = 0
        state.effectiveRuleJson = JSONObject()
            .put("location_septal", interval)
            .put("rule", rule.toJson())
            .toString()
        state.lastError = null
        RunStore.save(state)

        return try {
            RunForegroundService.start(context)
            OperationResult(
                true,
                "已正式起跑：${plan.distanceKm} km / ${plan.durationSec} s / ${plan.pointIds.size} 个打卡点",
                state,
            )
        } catch (error: Exception) {
            OperationResult(
                false,
                "已起跑但前台服务未能启动（${error.message}），重新打开应用会继续本次会话",
                state,
            )
        }
    }

    fun verify(context: Context): OperationResult {
        val state = RunStore.latest() ?: return OperationResult(false, "没有可回查的跑步会话", null)
        if (state.stage !in VERIFY_STAGES) {
            return OperationResult(
                false,
                "当前阶段 ${state.stage.wire} 不允许回查，只读回查仅用于已提交或结果未知的会话",
                state,
            )
        }
        val recordId = state.recordId ?: return OperationResult(false, "会话缺少 record_id，无法回查", state)
        val session = SessionStore.load() ?: return OperationResult(false, "尚未获取登录态", state)
        val manifest = ManifestCodec.fromJson(
            state.finishManifestJson?.let { com.lepao.companion.core.JsonUtil.parseObjectOrNull(it) },
        ) ?: return OperationResult(false, "会话缺少结束材料，无法回查", state)

        val transport = readOnlyTransport(context, session, state.id)
        val fetched = try {
            DetailReader.fetch(transport, session.identity, recordId, RunClock.nowEpoch())
        } catch (error: Exception) {
            return OperationResult(false, "回查失败，记录保持原状态：${error.message}", state)
        } ?: return OperationResult(false, "回查响应不完整，记录保持原状态", state)

        val checks = Verifier.detailChecks(
            detail = fetched.detail,
            uid = session.identity["uid"],
            startTime = state.startTime.orEmpty(),
            gameId = state.gameId,
            distance = manifest.distance,
            usedTime = manifest.usedTime,
            stepNum = manifest.stepNum,
            routeKey = state.objectKeys()[ArtifactKinds.ROUTE.name],
        )
        val journal = StateJournal(state)
        journal.setVerification(checks, fetched.data)
        journal.moveTo(if (checks.values.all { it }) RunStage.VERIFIED else RunStage.SUBMITTED)
        journal.save()

        val failed = checks.filterValues { !it }.keys.joinToString(",")
        return OperationResult(
            ok = checks.values.all { it },
            message = if (checks.values.all { it }) {
                "回查通过：记录 $recordId 全部验收项一致"
            } else {
                "回查未全部通过：$failed"
            },
            state = state,
        )
    }

    private fun startUncertain(state: RunState, message: String): OperationResult {
        state.stage = RunStage.UNCERTAIN
        state.lastNote = "from=${RunStage.START_PENDING.wire}"
        state.lastError = message
        RunStore.save(state)
        return OperationResult(false, "$message（已停止，不会自动重发）", state)
    }

    /**
     * Stages whose automatic progress can no longer be resumed safely. A user who has checked the
     * official app can clear the local blocker without sending anything; the server-side record is
     * never touched, which is why the UI warns about a possible duplicate before doing it.
     */
    private val ABANDON_STAGES = setOf(
        RunStage.PREPARED,
        RunStage.START_PENDING,
        RunStage.LOCATION_PENDING,
        RunStage.STOP_PENDING,
        RunStage.SENSOR_PENDING,
        RunStage.UNCERTAIN,
    )

    fun canAbandon(state: RunState?): Boolean = state != null && state.stage in ABANDON_STAGES

    /** Local-only escape hatch for a session that can never be resumed automatically. */
    fun abandon(context: Context): OperationResult {
        val state = RunStore.blockingRun()
            ?: return OperationResult(false, "没有需要放弃的本地会话", RunStore.latest())
        if (state.stage !in ABANDON_STAGES) {
            return OperationResult(
                false,
                "当前阶段 ${state.stage.wire} 不允许直接放弃；请先尝试恢复或只读回查",
                state,
            )
        }
        val previous = state.stage
        RunForegroundService.stop(context)
        state.stage = RunStage.FAILED
        state.lastNote = "abandoned_by_user from=${previous.wire}"
        state.lastError = "用户手动放弃：未发送任何请求；服务端是否已有记录需在官方乐跑核对"
        RunStore.save(state)
        DiagLog.warn("run ${state.id} abandoned by user at ${previous.wire}")
        return OperationResult(
            true,
            "本地会话已放弃；未发送任何请求。请到官方乐跑核对本次是否已有记录",
            state,
        )
    }

    private fun runTransport(context: Context, session: SessionContext, runId: String): RunTransport =
        CoreRunTransport(
            ApiTransport(
                session,
                write = true,
                audit = AuditLog(context, RunStore.auditFile(runId)),
            ),
        )

    private fun readOnlyTransport(context: Context, session: SessionContext, runId: String): RunTransport =
        CoreRunTransport(
            ApiTransport(
                session,
                write = false,
                audit = AuditLog(context, RunStore.auditFile(runId)),
            ),
        )

    private const val MIN_REFRESH_DAYS = 3.0
}
