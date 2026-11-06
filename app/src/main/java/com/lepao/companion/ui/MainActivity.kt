package com.lepao.companion.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lepao.companion.R
import com.lepao.companion.capture.CaptureController
import com.lepao.companion.core.DiagLog
import com.lepao.companion.core.JsonUtil
import com.lepao.companion.core.LepaoException
import com.lepao.companion.core.LoginHealth
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.ReadinessView
import com.lepao.companion.core.RunOptions
import com.lepao.companion.core.RunStage
import com.lepao.companion.core.RunState
import com.lepao.companion.core.RunStore
import com.lepao.companion.core.SessionContext
import com.lepao.companion.core.SessionStore
import com.lepao.companion.core.TokenService
import com.lepao.companion.run.RunController
import com.lepao.companion.run.EastCampusZone
import com.lepao.companion.run.RunForegroundService
import com.lepao.companion.run.RunOptionStore
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Read-only dashboard: login state, fetched run configuration, the current/latest run and the
 * local diagnostics log. Every network or disk read runs on a background executor; the 500 ms
 * Handler tick only re-renders already loaded data.
 */
class MainActivity : AppCompatActivity() {

    private data class Dashboard(
        val session: SessionContext?,
        val health: LoginHealth?,
        val latest: RunState?,
        val blocking: RunState?,
        val history: List<RunState>,
        val logText: String?,
    )

    private lateinit var mainProgress: ProgressBar

    private lateinit var loginStatus: TextView
    private lateinit var loginUid: TextView
    private lateinit var loginSchool: TextView
    private lateinit var loginCapturedAt: TextView
    private lateinit var loginRefresh: TextView
    private lateinit var loginRefreshNote: TextView
    private lateinit var loginRefreshedAt: TextView
    private lateinit var btnGetLogin: Button
    private lateinit var btnCheckRefresh: Button
    private lateinit var btnClearLogin: Button

    private lateinit var blockingBanner: TextView
    private lateinit var configHint: TextView
    private lateinit var configSchool: TextView
    private lateinit var configWindow: TextView
    private lateinit var configRule: TextView
    private lateinit var configDistanceRange: TextView
    private lateinit var configPaceRange: TextView
    private lateinit var configMinLog: TextView
    private lateinit var configPoints: TextView
    private lateinit var configGyro: TextView
    private lateinit var planSummary: TextView
    private lateinit var blockersText: TextView
    private lateinit var warningsText: TextView
    private lateinit var btnInspect: Button
    private lateinit var btnStartRun: Button
    private lateinit var btnAdvanced: Button

    private lateinit var sessionStage: TextView
    private lateinit var sessionProgress: TextView
    private lateinit var sessionDistance: TextView
    private lateinit var sessionHeartbeat: TextView
    private lateinit var sessionRecord: TextView
    private lateinit var sessionWarning: TextView
    private lateinit var checksTitle: TextView
    private lateinit var checksText: TextView
    private lateinit var recordDetailsTitle: TextView
    private lateinit var recordDetailsText: TextView
    private lateinit var sessionNote: TextView
    private lateinit var resumeHint: TextView
    private lateinit var btnVerify: Button
    private lateinit var btnOpenOfficial: Button
    private lateinit var btnResume: Button
    private lateinit var btnAbandon: Button

    private lateinit var historyEmpty: TextView
    private lateinit var historyContainer: LinearLayout

    private lateinit var btnLogToggle: Button
    private lateinit var btnCopyLog: Button
    private lateinit var logText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workExecutor: ExecutorService
    private lateinit var refreshExecutor: ExecutorService

    private var dashboard = Dashboard(null, null, null, null, emptyList(), null)
    private var dashboardLoaded = false
    private var readiness: ReadinessView? = null
    private var busy = false
    private var refreshing = false
    private var polling = false
    private var tickCount = 0
    private var historySignature = ""
    private val expandedRunIds = mutableSetOf<String>()

    @Volatile
    private var logExpanded = false

    private var planCacheKey = ""
    private var planCache: PlannedRun? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            tickCount++
            if (tickCount % 2 == 0) refreshAsync()
            if (polling) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        workExecutor = Executors.newSingleThreadExecutor()
        refreshExecutor = Executors.newSingleThreadExecutor()

        bindViews()
        setupListeners()
        render()
        requestNotificationPermission()
        refreshAsync()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        render()
        refreshAsync()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        stopPolling()
        workExecutor.shutdownNow()
        refreshExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        mainProgress = findViewById(R.id.main_progress)

        loginStatus = findViewById(R.id.login_status)
        loginUid = findViewById(R.id.login_uid)
        loginSchool = findViewById(R.id.login_school)
        loginCapturedAt = findViewById(R.id.login_captured_at)
        loginRefresh = findViewById(R.id.login_refresh)
        loginRefreshNote = findViewById(R.id.login_refresh_note)
        loginRefreshedAt = findViewById(R.id.login_refreshed_at)
        btnGetLogin = findViewById(R.id.btn_get_login)
        btnCheckRefresh = findViewById(R.id.btn_check_refresh)
        btnClearLogin = findViewById(R.id.btn_clear_login)

        blockingBanner = findViewById(R.id.blocking_banner)
        configHint = findViewById(R.id.config_hint)
        configSchool = findViewById(R.id.config_school)
        configWindow = findViewById(R.id.config_window)
        configRule = findViewById(R.id.config_rule)
        configDistanceRange = findViewById(R.id.config_distance_range)
        configPaceRange = findViewById(R.id.config_pace_range)
        configMinLog = findViewById(R.id.config_min_log)
        configPoints = findViewById(R.id.config_points)
        configGyro = findViewById(R.id.config_gyro)
        planSummary = findViewById(R.id.plan_summary)
        blockersText = findViewById(R.id.blockers_text)
        warningsText = findViewById(R.id.warnings_text)
        btnInspect = findViewById(R.id.btn_inspect)
        btnStartRun = findViewById(R.id.btn_start_run)
        btnAdvanced = findViewById(R.id.btn_advanced)

        sessionStage = findViewById(R.id.session_stage)
        sessionProgress = findViewById(R.id.session_progress)
        sessionDistance = findViewById(R.id.session_distance)
        sessionHeartbeat = findViewById(R.id.session_heartbeat)
        sessionRecord = findViewById(R.id.session_record)
        sessionWarning = findViewById(R.id.session_warning)
        checksTitle = findViewById(R.id.checks_title)
        checksText = findViewById(R.id.checks_text)
        recordDetailsTitle = findViewById(R.id.record_details_title)
        recordDetailsText = findViewById(R.id.record_details_text)
        sessionNote = findViewById(R.id.session_note)
        resumeHint = findViewById(R.id.resume_hint)
        btnVerify = findViewById(R.id.btn_verify)
        btnOpenOfficial = findViewById(R.id.btn_open_official)
        btnResume = findViewById(R.id.btn_resume)
        btnAbandon = findViewById(R.id.btn_abandon)

        historyEmpty = findViewById(R.id.history_empty)
        historyContainer = findViewById(R.id.history_container)

        btnLogToggle = findViewById(R.id.btn_log_toggle)
        btnCopyLog = findViewById(R.id.btn_copy_log)
        logText = findViewById(R.id.log_text)
    }

    private fun setupListeners() {
        btnGetLogin.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        btnCheckRefresh.setOnClickListener { checkAndRefreshLogin() }
        btnClearLogin.setOnClickListener { confirmClearLogin() }
        btnInspect.setOnClickListener { refreshConfig() }
        btnAdvanced.setOnClickListener { showAdvancedOptions() }
        btnStartRun.setOnClickListener {
            if (canSelectEastCampus()) confirmEastCampus() else confirmStartRun()
        }
        btnVerify.setOnClickListener { verifyRun() }
        btnOpenOfficial.setOnClickListener { openOfficialApp() }
        btnResume.setOnClickListener { resumeSession() }
        btnAbandon.setOnClickListener { confirmAbandon() }
        btnLogToggle.setOnClickListener { toggleLog() }
        btnCopyLog.setOnClickListener { copyLog() }
    }

    // ---------------------------------------------------------------------
    // Polling / background loading
    // ---------------------------------------------------------------------

    private fun startPolling() {
        if (polling) return
        polling = true
        tickCount = 0
        mainHandler.postDelayed(tickRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        polling = false
        mainHandler.removeCallbacks(tickRunnable)
    }

    private fun refreshAsync() {
        if (refreshing || isFinishing || isDestroyed) return
        refreshing = true
        try {
            refreshExecutor.execute {
                val snapshot = loadDashboard()
                mainHandler.post {
                    refreshing = false
                    if (isFinishing || isDestroyed) return@post
                    dashboard = snapshot
                    dashboardLoaded = true
                    render()
                }
            }
        } catch (_: RejectedExecutionException) {
            refreshing = false
        }
    }

    private fun loadDashboard(): Dashboard {
        val session = runCatching { SessionStore.load() }.getOrNull()
        val health = session?.let { runCatching { TokenService.health(it) }.getOrNull() }
        val latest = runCatching { RunStore.latest() }.getOrNull()
        val blocking = runCatching { RunStore.blockingRun() }.getOrNull()
        val history = runCatching { RunStore.list(5) }.getOrElse { emptyList() }
        val log = if (logExpanded) runCatching { DiagLog.recent() }.getOrNull() else null
        return Dashboard(session, health, latest, blocking, history, log)
    }

    private fun runAction(block: () -> Unit) {
        if (busy) return
        setBusy(true)
        try {
            workExecutor.execute {
                val failure = try {
                    block()
                    null
                } catch (error: Throwable) {
                    error
                }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    setBusy(false)
                    if (failure != null) showActionError(failure)
                    refreshAsync()
                }
            }
        } catch (_: RejectedExecutionException) {
            setBusy(false)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        render()
    }

    private fun showActionError(error: Throwable) {
        val message = if (error is LepaoException) {
            error.message ?: error.javaClass.simpleName
        } else {
            error.message ?: error.javaClass.simpleName
        }
        toast(getString(R.string.toast_operation_failed, message))
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun render() {
        renderLogin()
        renderConfig()
        renderSession()
        renderHistory()
        renderLog()
        updateButtons()
    }

    private fun renderLogin() {
        val unknown = getString(R.string.session_value_unknown)
        val session = dashboard.session

        if (!dashboardLoaded) {
            loginStatus.setTextIfChanged(getString(R.string.login_status_loading))
            loginStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            loginUid.setTextIfChanged(getString(R.string.login_uid, unknown))
            loginSchool.setTextIfChanged(getString(R.string.login_school, unknown))
            loginCapturedAt.setTextIfChanged(getString(R.string.login_captured_at, unknown))
            loginRefresh.visibility = View.GONE
            loginRefreshNote.visibility = View.GONE
            loginRefreshedAt.visibility = View.GONE
            return
        }

        if (session == null) {
            loginStatus.setTextIfChanged(getString(R.string.login_status_missing))
            loginStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            loginUid.setTextIfChanged(getString(R.string.login_uid, unknown))
            loginSchool.setTextIfChanged(getString(R.string.login_school, unknown))
            loginCapturedAt.setTextIfChanged(getString(R.string.login_captured_at, unknown))
            loginRefresh.visibility = View.GONE
            loginRefreshNote.visibility = View.GONE
            loginRefreshedAt.visibility = View.GONE
            return
        }

        val health = dashboard.health ?: TokenService.health(session)
        val days = health.refreshDaysLeft
        val expired = !health.uidPresent || !health.accessTokenPresent || (days != null && days <= 0.0)
        if (health.invalidated) {
            loginStatus.setTextIfChanged(getString(R.string.login_status_invalidated))
            loginStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
        } else if (expired) {
            loginStatus.setTextIfChanged(getString(R.string.login_status_expired))
            loginStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
        } else {
            loginStatus.setTextIfChanged(getString(R.string.login_status_ready))
            loginStatus.setTextColor(ContextCompat.getColor(this, R.color.ok))
        }

        val uid = session.identity.maskedUid().ifBlank { unknown }
        val school = session.identity["school_id"].ifBlank { unknown }
        loginUid.setTextIfChanged(getString(R.string.login_uid, uid))
        loginSchool.setTextIfChanged(getString(R.string.login_school, school))
        loginCapturedAt.setTextIfChanged(
            getString(R.string.login_captured_at, UiText.fullTime(session.capturedAt).ifBlank { unknown }),
        )

        loginRefresh.visibility = View.VISIBLE
        when {
            !health.canRefresh -> {
                loginRefresh.setTextIfChanged(getString(R.string.login_no_refresh))
                loginRefresh.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            days == null -> {
                loginRefresh.setTextIfChanged(getString(R.string.login_refresh_unknown))
                loginRefresh.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            days <= 0.0 -> {
                loginRefresh.setTextIfChanged(getString(R.string.login_refresh_expired))
                loginRefresh.setTextColor(ContextCompat.getColor(this, R.color.danger))
            }
            else -> {
                loginRefresh.setTextIfChanged(
                    getString(R.string.login_refresh_days, UiText.oneDecimal(days)),
                )
                loginRefresh.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        val mismatch = health.canRefresh && !health.refreshTokenMatchesAccessToken
        loginRefreshNote.visibility = if (mismatch) View.VISIBLE else View.GONE

        val refreshedAt = health.refreshedAt
        if (refreshedAt != null && refreshedAt > 0L) {
            loginRefreshedAt.visibility = View.VISIBLE
            loginRefreshedAt.setTextIfChanged(
                getString(R.string.login_refreshed_at, UiText.fullTime(refreshedAt).ifBlank { unknown }),
            )
        } else {
            loginRefreshedAt.visibility = View.GONE
        }
    }

    private fun renderConfig() {
        val unknown = getString(R.string.session_value_unknown)
        val session = dashboard.session
        val view = readiness
        val rules = view?.rules
        val rule = view?.rule ?: view?.plan?.rule

        configHint.visibility = if (session == null || view == null) View.VISIBLE else View.GONE
        when {
            session == null -> configHint.setTextIfChanged(getString(R.string.config_no_session))
            view == null -> configHint.setTextIfChanged(getString(R.string.config_not_loaded))
            else -> Unit
        }

        val schoolLabel = rules?.schoolName?.takeIf { it.isNotBlank() }
            ?: session?.identity?.get("school_id")?.takeIf { it.isNotBlank() }
            ?: rules?.schoolId?.takeIf { it.isNotBlank() }
            ?: unknown
        val zoneLabel = rules?.runZoneName?.takeIf { it.isNotBlank() }
        val schoolZone = if (zoneLabel != null) "$schoolLabel / $zoneLabel" else schoolLabel
        configSchool.setTextIfChanged(getString(R.string.config_school, schoolZone))

        if (rules != null && rules.runEndTime > 0) {
            configWindow.setTextIfChanged(
                getString(
                    R.string.config_window,
                    UiText.timeOfDay(rules.runStartTime),
                    UiText.timeOfDay(rules.runEndTime),
                ),
            )
        } else {
            configWindow.setTextIfChanged(getString(R.string.config_window, unknown, unknown))
        }

        if (rule != null) {
            configRule.setTextIfChanged(
                getString(
                    R.string.config_rule,
                    "${UiText.timeOfDay(rule.startTime)}–${UiText.timeOfDay(rule.endTime)}",
                ),
            )
            configDistanceRange.setTextIfChanged(
                getString(
                    R.string.config_distance_range,
                    UiText.number(minOf(rule.minDistanceKm, rule.maxDistanceKm)),
                    UiText.number(maxOf(rule.minDistanceKm, rule.maxDistanceKm)),
                ),
            )
            configPaceRange.setTextIfChanged(
                getString(
                    R.string.config_pace_range,
                    UiText.number(rule.lowerPace),
                    UiText.number(rule.upperPace),
                ),
            )
            configMinLog.setTextIfChanged(getString(R.string.config_min_log, rule.minLogNum.toString()))
        } else {
            configRule.setTextIfChanged(getString(R.string.config_rule, unknown))
            configDistanceRange.setTextIfChanged(getString(R.string.config_distance_range, unknown, unknown))
            configPaceRange.setTextIfChanged(getString(R.string.config_pace_range, unknown, unknown))
            configMinLog.setTextIfChanged(getString(R.string.config_min_log, unknown))
        }
        configPoints.setTextIfChanged(
            getString(R.string.config_points, rules?.checkpoints?.size?.toString() ?: unknown),
        )
        configGyro.setTextIfChanged(
            getString(R.string.config_gyro, rules?.gyroscopeType?.takeIf { it.isNotBlank() } ?: unknown),
        )
        planSummary.setTextIfChanged(UiText.planSummary(this, view?.plan))
        updateAdvancedLabel()

        val blockers = view?.blockers.orEmpty()
        if (blockers.isEmpty()) {
            blockersText.visibility = View.GONE
        } else {
            blockersText.visibility = View.VISIBLE
            blockersText.setTextIfChanged(UiText.bullets(blockers))
        }
        val warnings = view?.warnings.orEmpty()
        if (warnings.isEmpty()) {
            warningsText.visibility = View.GONE
        } else {
            warningsText.visibility = View.VISIBLE
            warningsText.setTextIfChanged(UiText.bullets(warnings))
        }

        val blocking = dashboard.blocking
        if (blocking == null) {
            blockingBanner.visibility = View.GONE
        } else {
            val plan = planOf(blocking)
            val duration = plan?.durationSec ?: 0
            val elapsed = blocking.elapsedSeconds(SystemClock.elapsedRealtime())
            val elapsedText = if (duration > 0) UiText.clock(elapsed) else unknown
            val durationText = if (duration > 0) UiText.clock(duration) else unknown
            blockingBanner.visibility = View.VISIBLE
            blockingBanner.setTextIfChanged(
                getString(R.string.blocking_title) + "\n" + getString(
                    R.string.blocking_detail,
                    UiText.stageLabel(this, blocking.stage),
                    blocking.id,
                    elapsedText,
                    durationText,
                ),
            )
        }
    }

    private fun renderSession() {
        val unknown = getString(R.string.session_value_unknown)
        val state = dashboard.latest

        if (!dashboardLoaded) {
            sessionStage.setTextIfChanged(getString(R.string.login_status_loading))
            sessionStage.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            sessionProgress.setTextIfChanged(getString(R.string.session_progress, unknown, unknown))
            sessionDistance.setTextIfChanged(getString(R.string.session_distance, unknown))
            sessionHeartbeat.setTextIfChanged(getString(R.string.session_heartbeat, 0))
            sessionRecord.setTextIfChanged(getString(R.string.session_record_id, unknown))
            hideSessionDetails()
            return
        }

        if (state == null) {
            sessionStage.setTextIfChanged(getString(R.string.session_none))
            sessionStage.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            sessionProgress.setTextIfChanged(getString(R.string.session_progress, unknown, unknown))
            sessionDistance.setTextIfChanged(getString(R.string.session_distance, unknown))
            sessionHeartbeat.setTextIfChanged(getString(R.string.session_heartbeat, 0))
            sessionRecord.setTextIfChanged(getString(R.string.session_record_id, unknown))
            hideSessionDetails()
            return
        }

        sessionStage.setTextIfChanged(getString(R.string.session_stage, UiText.stageLabel(this, state.stage)))
        sessionStage.setTextColor(UiText.stageColor(this, state.stage))

        val plan = planOf(state)
        val duration = plan?.durationSec ?: 0
        val elapsed = state.elapsedSeconds(SystemClock.elapsedRealtime())
        sessionProgress.setTextIfChanged(
            getString(
                R.string.session_progress,
                if (duration > 0) UiText.clock(elapsed) else unknown,
                if (duration > 0) UiText.clock(duration) else unknown,
            ),
        )
        sessionDistance.setTextIfChanged(
            getString(R.string.session_distance, plan?.distanceKm?.takeIf { it.isNotBlank() } ?: unknown),
        )
        sessionHeartbeat.setTextIfChanged(getString(R.string.session_heartbeat, state.heartbeatCount))
        sessionRecord.setTextIfChanged(
            getString(R.string.session_record_id, state.recordId?.takeIf { it.isNotBlank() } ?: unknown),
        )

        val finished = state.stage == RunStage.SUBMITTED || state.stage == RunStage.VERIFIED
        if (!finished) {
            hideSessionDetails()
        } else {
            checksTitle.visibility = View.VISIBLE
            checksText.visibility = View.VISIBLE
            recordDetailsTitle.visibility = View.VISIBLE
            recordDetailsText.visibility = View.VISIBLE
            sessionNote.visibility = View.VISIBLE

            val verification = state.verification()
            val checks = if (verification.isNotEmpty()) verification else state.stopChecks()
            if (checks.isEmpty()) {
                checksText.setTextIfChanged(getString(R.string.session_checks_pending))
            } else {
                val builder = StringBuilder()
                for ((key, labelRes) in UiText.checkEntries()) {
                    val result = checks[key]
                    val symbol = when (result) {
                        true -> "✅"
                        false -> "❌"
                        null -> "❔"
                    }
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(symbol).append(' ').append(getString(labelRes))
                }
                checksText.setTextIfChanged(builder.toString())
            }
            recordDetailsText.setTextIfChanged(
                describeRecordDetails(state) ?: getString(R.string.session_record_detail_missing),
            )
        }
        renderSessionWarning(state)

        // The service applies the recovery matrix on start: RUNNING continues heartbeats,
        // UPLOADING (no ambiguous PUT) completes the remaining uploads, STOP_ACKNOWLEDGED finishes
        // sensor registration + readback. Stages with an ambiguous write are left to read-only
        // reconciliation and are intentionally not resumed here.
        val resumeVisible = when (state.stage) {
            RunStage.RUNNING -> true
            RunStage.UPLOADING -> state.uploadPending.isNullOrBlank()
            RunStage.STOP_ACKNOWLEDGED -> true
            else -> false
        }
        btnResume.visibility = if (resumeVisible) View.VISIBLE else View.GONE
        resumeHint.visibility = if (resumeVisible) View.VISIBLE else View.GONE
        btnAbandon.visibility = if (RunController.canAbandon(state)) View.VISIBLE else View.GONE
    }

    private fun renderSessionWarning(state: RunState) {
        val pendingUpload = !state.uploadPending.isNullOrBlank()
        val warning = state.stage == RunStage.UNCERTAIN ||
            state.stage == RunStage.STOP_PENDING ||
            pendingUpload
        val failedWithError = state.stage == RunStage.FAILED && !state.lastError.isNullOrBlank()
        if (!warning && !failedWithError) {
            sessionWarning.visibility = View.GONE
            return
        }
        sessionWarning.visibility = View.VISIBLE
        val builder = StringBuilder()
        if (warning) builder.append(getString(R.string.session_warning_stop))
        val error = state.lastError
        if (!error.isNullOrBlank()) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(getString(R.string.session_last_error, error))
        }
        sessionWarning.setTextIfChanged(builder.toString())
    }

    private fun hideSessionDetails() {
        checksTitle.visibility = View.GONE
        checksText.visibility = View.GONE
        recordDetailsTitle.visibility = View.GONE
        recordDetailsText.visibility = View.GONE
        sessionNote.visibility = View.GONE
        sessionWarning.visibility = View.GONE
        btnResume.visibility = View.GONE
        resumeHint.visibility = View.GONE
        btnAbandon.visibility = View.GONE
    }

    private fun describeRecordDetails(state: RunState): String? {
        val raw = state.detailJson?.takeIf { it.isNotBlank() } ?: return null
        val root = JsonUtil.parseObjectOrNull(raw) ?: return null
        val detail = root.optJSONObject("data") ?: root
        val unknown = getString(R.string.session_value_unknown)
        val zone = detail.optString("run_zone_name")
            .ifBlank { detail.optString("run_zone_id") }
            .ifBlank { unknown }
        val lines = listOf(
            getString(R.string.session_record_status, detail.optString("record_status").ifBlank { unknown }),
            getString(R.string.session_record_distance, detail.optString("distance").ifBlank { unknown }),
            getString(R.string.session_record_time, detail.optString("time").ifBlank { unknown }),
            getString(R.string.session_record_steps, detail.optString("step_num").ifBlank { unknown }),
            getString(R.string.session_record_zone, zone),
        )
        return lines.joinToString("\n")
    }

    private fun renderHistory() {
        if (!dashboardLoaded) return
        val history = dashboard.history
        historyEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        historyContainer.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        val signature = history.joinToString("|") { state ->
            "${state.id}:${state.stage.wire}:${state.updatedAt}:${state.recordId.orEmpty()}:${state.planJson.hashCode()}"
        }
        if (signature == historySignature) return
        historySignature = signature
        rebuildHistory(history)
    }

    private fun rebuildHistory(history: List<RunState>) {
        historyContainer.removeAllViews()
        val inflater = layoutInflater
        for (state in history) {
            val row = inflater.inflate(R.layout.item_history, historyContainer, false)
            val header = row.findViewById<TextView>(R.id.history_header)
            val detail = row.findViewById<TextView>(R.id.history_detail)
            val unknown = getString(R.string.session_value_unknown)
            header.text = getString(
                R.string.history_header,
                UiText.shortTime(state.updatedAt).ifBlank { unknown },
                UiText.stageLabel(this, state.stage),
                planOf(state)?.distanceKm?.takeIf { it.isNotBlank() } ?: unknown,
                state.recordId?.takeIf { it.isNotBlank() } ?: getString(R.string.history_no_record),
            )
            detail.text = runCatching { state.toJson().toString(2) }.getOrDefault("")
            var expanded = expandedRunIds.contains(state.id)
            detail.visibility = if (expanded) View.VISIBLE else View.GONE
            header.setOnClickListener {
                expanded = !expanded
                if (expanded) expandedRunIds.add(state.id) else expandedRunIds.remove(state.id)
                detail.visibility = if (expanded) View.VISIBLE else View.GONE
            }
            historyContainer.addView(row)
        }
    }

    private fun renderLog() {
        btnLogToggle.setTextIfChanged(
            getString(if (logExpanded) R.string.btn_log_collapse else R.string.btn_log_expand),
        )
        if (!logExpanded) {
            logText.visibility = View.GONE
            return
        }
        logText.visibility = View.VISIBLE
        val text = dashboard.logText
        logText.setTextIfChanged(
            when {
                text == null -> getString(R.string.login_status_loading)
                text.isBlank() -> getString(R.string.log_empty)
                else -> text
            },
        )
    }

    private fun updateButtons() {
        mainProgress.visibility = if (busy) View.VISIBLE else View.GONE

        val session = dashboard.session
        val state = dashboard.latest
        val noBlockingRun = dashboard.blocking == null
        btnGetLogin.isEnabled = !busy
        // Rotating or clearing the token while a run is active would break the engine mid-session.
        btnCheckRefresh.isEnabled = !busy && dashboardLoaded && session != null && noBlockingRun
        btnClearLogin.isEnabled = !busy && dashboardLoaded && session != null && noBlockingRun
        btnInspect.isEnabled = !busy && dashboardLoaded && session != null
        btnAdvanced.isEnabled = !busy && dashboardLoaded && noBlockingRun
        val canSelectEastCampus = canSelectEastCampus()
        btnStartRun.setText(if (canSelectEastCampus) R.string.btn_select_east_campus else R.string.btn_start_run)
        btnStartRun.isEnabled = !busy &&
            dashboardLoaded &&
            session != null &&
            (canSelectEastCampus || readiness?.ok == true) &&
            noBlockingRun
        btnVerify.isEnabled = !busy && canVerify(state)
        btnOpenOfficial.isEnabled = !busy
        btnResume.isEnabled = !busy
        btnAbandon.isEnabled = !busy
        btnCopyLog.isEnabled = !busy && logExpanded
        btnLogToggle.isEnabled = !busy
    }

    private fun canVerify(state: RunState?): Boolean {
        if (state == null) return false
        if (state.stage == RunStage.SUBMITTED || state.stage == RunStage.VERIFIED) return true
        if (state.recordId.isNullOrBlank()) return false
        return state.stage == RunStage.UNCERTAIN || state.stage == RunStage.STOP_PENDING
    }

    private fun planOf(state: RunState?): PlannedRun? {
        if (state == null) return null
        val key = "${state.id}:${state.planJson.hashCode()}"
        if (key != planCacheKey) {
            planCacheKey = key
            planCache = runCatching { state.plan }.getOrNull()
        }
        return planCache
    }

    // ---------------------------------------------------------------------
    // User actions
    // ---------------------------------------------------------------------

    private fun checkAndRefreshLogin() {
        val session = dashboard.session ?: return
        runAction {
            val outcome = TokenService.maybeAutoRefresh(this@MainActivity, session, 3.0)
            val message = refreshMessage(outcome)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                toast(message)
                refreshAsync()
            }
        }
    }

    private fun refreshMessage(outcome: TokenService.AutoRefreshOutcome): String = when {
        outcome.attempted && outcome.refreshed -> getString(R.string.toast_login_refreshed)
        !outcome.attempted && outcome.reason.contains("no stored refresh_token") ->
            getString(R.string.toast_login_no_refresh)
        !outcome.attempted && outcome.reason.contains("another access token") ->
            getString(R.string.toast_login_mismatch)
        !outcome.attempted -> getString(R.string.toast_login_no_need)
        else -> getString(R.string.toast_login_refresh_failed, outcome.reason)
    }

    private fun confirmClearLogin() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_login_title)
            .setMessage(R.string.dialog_clear_login_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> clearLogin() }
            .show()
    }

    private fun clearLogin() {
        runAction {
            SessionStore.clear()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                readiness = null
                toast(getString(R.string.toast_login_cleared))
            }
        }
    }

    private fun refreshConfig(selectEastCampus: Boolean = false) {
        runAction {
            val view = if (selectEastCampus) RunController.selectEastCampus(this@MainActivity)
                else RunController.inspect(this@MainActivity)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                readiness = view
                if (view.ok) {
                    toast(getString(R.string.toast_config_refreshed))
                } else {
                    toast(view.blockers.firstOrNull())
                }
                render()
            }
        }
    }

    private fun canSelectEastCampus(): Boolean {
        val rules = readiness?.rules ?: return false
        val session = dashboard.session ?: return false
        return EastCampusZone.supports(rules, session.identity["school_id"]) &&
            !EastCampusZone.isSelected(rules) && EastCampusZone.targetId(rules) != null
    }

    private fun confirmEastCampus() {
        if (!canSelectEastCampus() || dashboard.blocking != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_select_east_campus)
            .setMessage(R.string.dialog_select_east_campus)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> refreshConfig(selectEastCampus = true) }
            .show()
    }

    /** Reflects the stored 高级选项 on the button, so the current override is visible at a glance. */
    private fun updateAdvancedLabel() {
        val options = RunOptionStore.load(this)
        val parts = ArrayList<String>(2)
        options.distanceKm?.let { parts += String.format(Locale.ROOT, "%.2f km", it) }
        options.paceSecPerKm?.let { parts += "${RunOptions.formatPace(it)}/km" }
        btnAdvanced.setTextIfChanged(
            if (parts.isEmpty()) {
                getString(R.string.btn_advanced_default)
            } else {
                getString(R.string.btn_advanced_custom, parts.joinToString(" · "))
            },
        )
    }

    /**
     * 高级选项: custom distance (> 1 km) and pace (3:00-9:00 /km). The school's own rule interval
     * still applies; the planner blocks with an explicit reason when the two disagree.
     */
    private fun showAdvancedOptions() {
        if (dashboard.blocking != null) {
            toast(getString(R.string.toast_options_blocked))
            return
        }
        val content = layoutInflater.inflate(R.layout.dialog_run_options, null)
        val distanceInput = content.findViewById<EditText>(R.id.advanced_distance)
        val paceInput = content.findViewById<EditText>(R.id.advanced_pace)
        val errorText = content.findViewById<TextView>(R.id.advanced_error)
        val options = RunOptionStore.load(this)
        distanceInput.setText(
            options.distanceKm?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "",
        )
        paceInput.setText(options.paceSecPerKm?.let { RunOptions.formatPace(it) } ?: "")
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_advanced_title)
            .setView(content)
            .setPositiveButton(R.string.dialog_confirm, null)
            .setNeutralButton(R.string.btn_restore_defaults, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val distanceText = distanceInput.text.toString().trim()
                val paceText = paceInput.text.toString().trim()
                val distance = if (distanceText.isEmpty()) null else RunOptions.parseDistance(distanceText)
                if (distanceText.isNotEmpty() && distance == null) {
                    errorText.setText(R.string.dialog_advanced_error_distance)
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val pace = if (paceText.isEmpty()) null else RunOptions.parsePace(paceText)
                if (paceText.isNotEmpty() && pace == null) {
                    errorText.setText(R.string.dialog_advanced_error_pace)
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                RunOptionStore.save(this, RunOptions(distance, pace))
                dialog.dismiss()
                refreshConfig()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                RunOptionStore.clear(this)
                dialog.dismiss()
                refreshConfig()
            }
        }
        dialog.show()
    }

    private fun confirmStartRun() {
        val view = readiness ?: return
        if (!view.ok || dashboard.session == null || dashboard.blocking != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_start_title)
            .setMessage(R.string.dialog_start_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> startRun() }
            .show()
    }

    private fun startRun() {
        requestBackgroundExemption()
        runAction {
            val result = RunController.start(this@MainActivity)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                toast(result.message)
            }
        }
    }

    /**
     * 跑步全程需要前台服务常驻。除了状态栏通知外，再申请电池优化白名单，
     * 避免系统在跑步中途冻结或清理进程导致上传/提交失败。
     */
    private fun requestBackgroundExemption() {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                // 系统不支持该入口时，只能在设置中手动放开后台限制。
            }
        }
    }

    private fun verifyRun() {
        runAction {
            val result = RunController.verify(this@MainActivity)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                toast(result.message)
            }
        }
    }

    private fun openOfficialApp() {
        if (!CaptureController.openOfficialApp(this)) {
            toast(getString(R.string.toast_no_official_app))
        }
    }

    private fun resumeSession() {
        try {
            RunForegroundService.start(this)
            toast(getString(R.string.toast_resume_requested))
        } catch (error: Exception) {
            toast(getString(R.string.toast_resume_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun confirmAbandon() {
        val state = dashboard.latest ?: return
        if (!RunController.canAbandon(state)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_abandon_title)
            .setMessage(R.string.dialog_abandon_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> abandonRun() }
            .show()
    }

    private fun abandonRun() {
        runAction {
            val result = RunController.abandon(this@MainActivity)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                toast(result.message)
            }
        }
    }

    private fun toggleLog() {
        logExpanded = !logExpanded
        if (logExpanded) refreshAsync()
        render()
    }

    private fun copyLog() {
        if (!logExpanded) return
        val text = dashboard.logText
        if (text.isNullOrBlank()) {
            toast(getString(R.string.log_empty))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lepao-log", text))
        toast(getString(R.string.toast_copied))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private fun toast(message: CharSequence?) {
        if (message.isNullOrBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (text?.toString() != value.toString()) text = value
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val REQUEST_NOTIFICATIONS = 1001
    }
}
