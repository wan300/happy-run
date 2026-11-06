package com.lepao.companion.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lepao.companion.R
import com.lepao.companion.capture.CaptureController
import com.lepao.companion.core.CapturePhase
import com.lepao.companion.core.SessionStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Login capture screen: explains the steps, drives the VPN consent flow and saves the captured
 * session once the proxy reports a complete account context.
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var captureProgress: ProgressBar
    private lateinit var captureState: TextView
    private lateinit var checkChannelIcon: TextView
    private lateinit var checkChannelLabel: TextView
    private lateinit var checkLoginIcon: TextView
    private lateinit var checkLoginLabel: TextView
    private lateinit var checkIdentityIcon: TextView
    private lateinit var checkIdentityLabel: TextView
    private lateinit var checkReadyIcon: TextView
    private lateinit var checkReadyLabel: TextView
    private lateinit var endpointsText: TextView
    private lateinit var messageCard: View
    private lateinit var errorText: TextView
    private lateinit var hintsText: TextView
    private lateinit var btnStartCapture: Button
    private lateinit var btnStopCapture: Button
    private lateinit var btnSaveCapture: Button
    private lateinit var btnOpenOfficial: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workExecutor: ExecutorService
    private var busy = false
    private var polling = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            if (polling) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startCaptureService()
        } else {
            toast(getString(R.string.toast_vpn_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        workExecutor = Executors.newSingleThreadExecutor()

        bindViews()
        setupListeners()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmBack()
                }
            },
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        render()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        stopPolling()
        workExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        captureProgress = findViewById(R.id.capture_progress)
        captureState = findViewById(R.id.capture_state)
        checkChannelIcon = findViewById(R.id.check_channel_icon)
        checkChannelLabel = findViewById(R.id.check_channel_label)
        checkLoginIcon = findViewById(R.id.check_login_icon)
        checkLoginLabel = findViewById(R.id.check_login_label)
        checkIdentityIcon = findViewById(R.id.check_identity_icon)
        checkIdentityLabel = findViewById(R.id.check_identity_label)
        checkReadyIcon = findViewById(R.id.check_ready_icon)
        checkReadyLabel = findViewById(R.id.check_ready_label)
        endpointsText = findViewById(R.id.endpoints_text)
        messageCard = findViewById(R.id.capture_message_card)
        errorText = findViewById(R.id.error_text)
        hintsText = findViewById(R.id.hints_text)
        btnStartCapture = findViewById(R.id.btn_start_capture)
        btnStopCapture = findViewById(R.id.btn_stop_capture)
        btnSaveCapture = findViewById(R.id.btn_save_capture)
        btnOpenOfficial = findViewById(R.id.btn_open_official)
    }

    private fun setupListeners() {
        btnStartCapture.setOnClickListener { beginCapture() }
        btnStopCapture.setOnClickListener { stopCapture() }
        btnSaveCapture.setOnClickListener { saveSession() }
        btnOpenOfficial.setOnClickListener { openOfficialApp() }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        mainHandler.postDelayed(tickRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        polling = false
        mainHandler.removeCallbacks(tickRunnable)
    }

    private fun render() {
        val status = CaptureController.status()
        captureState.setTextIfChanged(getString(R.string.capture_status, phaseLabel(status.phase)))
        captureState.setTextColor(
            getColor(if (status.phase == CapturePhase.ERROR) R.color.danger else R.color.text_primary),
        )

        updateCheck(checkChannelIcon, checkChannelLabel, status.active && status.vpnActive)
        updateCheck(checkLoginIcon, checkLoginLabel, status.loginSeen)
        updateCheck(checkIdentityIcon, checkIdentityLabel, status.identitySeen)
        updateCheck(checkReadyIcon, checkReadyLabel, status.contextReady)

        val endpoints = status.endpoints.filter { it.isNotBlank() }.takeLast(ENDPOINT_LIMIT).reversed()
        endpointsText.setTextIfChanged(
            if (endpoints.isEmpty()) getString(R.string.capture_endpoints_empty) else endpoints.joinToString("\n"),
        )

        val error = status.error
        if (error.isNullOrBlank()) {
            errorText.visibility = View.GONE
        } else {
            errorText.visibility = View.VISIBLE
            errorText.setTextIfChanged(getString(R.string.capture_error, error))
        }
        val hints = status.hints.filter { it.isNotBlank() }
        if (hints.isEmpty()) {
            hintsText.visibility = View.GONE
        } else {
            hintsText.visibility = View.VISIBLE
            hintsText.setTextIfChanged(UiText.bullets(hints))
        }
        messageCard.visibility =
            if (errorText.visibility == View.VISIBLE || hintsText.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }

        captureProgress.visibility = if (busy) View.VISIBLE else View.GONE
        btnStartCapture.isEnabled =
            !busy && !status.active && status.phase != CapturePhase.STARTING
        btnStopCapture.isEnabled = !busy && status.active
        btnSaveCapture.isEnabled = !busy && status.contextReady
        btnOpenOfficial.isEnabled = !busy
    }

    private fun updateCheck(icon: TextView, label: TextView, done: Boolean) {
        icon.text = if (done) "✅" else "○"
        icon.setTextColor(getColor(if (done) R.color.ok else R.color.text_secondary))
        label.setTextColor(getColor(if (done) R.color.text_primary else R.color.text_secondary))
    }

    private fun phaseLabel(phase: CapturePhase): String = getString(
        when (phase) {
            CapturePhase.IDLE -> R.string.capture_state_idle
            CapturePhase.STARTING -> R.string.capture_state_starting
            CapturePhase.RUNNING -> R.string.capture_state_running
            CapturePhase.LOGIN_SEEN -> R.string.capture_state_login_seen
            CapturePhase.CONTEXT_READY -> R.string.capture_state_context_ready
            CapturePhase.ERROR -> R.string.capture_state_error
        },
    )

    private fun beginCapture() {
        if (busy) return
        try {
            val consent = CaptureController.prepareConsent(this)
            if (consent != null) {
                vpnLauncher.launch(consent)
            } else {
                startCaptureService()
            }
        } catch (error: Exception) {
            toast(getString(R.string.toast_capture_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun startCaptureService() {
        try {
            CaptureController.startService(this)
            toast(getString(R.string.toast_capture_started))
        } catch (error: Exception) {
            toast(getString(R.string.toast_capture_failed, error.message ?: error.javaClass.simpleName))
        }
        render()
    }

    private fun stopCapture() {
        try {
            CaptureController.stop(this)
            toast(getString(R.string.toast_capture_stopped))
        } catch (error: Exception) {
            toast(getString(R.string.toast_capture_failed, error.message ?: error.javaClass.simpleName))
        }
        render()
    }

    private fun openOfficialApp() {
        if (!CaptureController.openOfficialApp(this)) {
            toast(getString(R.string.toast_no_official_app))
        }
    }

    private fun saveSession() {
        if (busy) return
        setBusy(true)
        try {
            workExecutor.execute {
                val outcome = runCatching {
                    val session = CaptureController.assembleSession()
                    if (session != null) SessionStore.save(session)
                    session
                }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    setBusy(false)
                    outcome.fold(
                        onSuccess = { session ->
                            if (session == null) {
                                toast(getString(R.string.toast_save_incomplete))
                            } else {
                                toast(getString(R.string.toast_save_success))
                                finish()
                            }
                        },
                        onFailure = { error ->
                            toast(
                                getString(
                                    R.string.toast_save_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            setBusy(false)
        }
    }

    private fun confirmBack() {
        if (!CaptureController.status().active) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_back_title)
            .setMessage(R.string.dialog_back_message)
            .setNegativeButton(R.string.dialog_back_cancel, null)
            .setPositiveButton(R.string.dialog_back_confirm) { _, _ ->
                CaptureController.stop(this)
                finish()
            }
            .show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        render()
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
        const val ENDPOINT_LIMIT = 5
    }
}
