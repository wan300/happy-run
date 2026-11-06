package com.lepao.companion.run

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.lepao.companion.R
import com.lepao.companion.core.DiagLog
import com.lepao.companion.core.Notifications
import com.lepao.companion.core.RunStage
import com.lepao.companion.core.RunState
import com.lepao.companion.core.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground owner of a run in progress.
 *
 * One coroutine drives the engine for the whole session, the partial wake lock keeps the process
 * alive while the screen is off, and a 15 s notification tick shows elapsed time, distance and
 * check-ins. Start is idempotent: repeated `onStartCommand` calls never launch a second loop.
 * After a process death `START_STICKY` restarts the service and the engine applies the recovery
 * matrix from `docs/run-engine.md` §5.
 */
class RunForegroundService : Service() {

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean(false)
    private val notificationLock = Any()

    @Volatile
    private var lastNotificationAt = 0L

    @Volatile
    private var sessionStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即进入前台并在状态栏常驻通知，系统按前台服务优先级对待本进程。
        val initial = notification("准备跑步会话…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }
        // Idempotent: a second start command must not open a second engine loop.
        if (job?.isActive == true) return START_STICKY
        val state = RunStore.latest()
        if (state == null) {
            finishService("没有可恢复的跑步会话")
            return START_STICKY
        }
        if (state.stage in TERMINAL_STAGES) {
            finishService("会话已结束：${state.stage.wire}")
            return START_STICKY
        }
        stopping.set(false)
        if (sessionStartedAt == 0L) sessionStartedAt = System.currentTimeMillis()
        val running = state
        job = scope?.launch { runSession(running) }
        return START_STICKY
    }

    private suspend fun runSession(state: RunState) {
        val engine = try {
            RunSessions.create(
                context = this,
                state = state,
                onProgress = { progress -> publishProgress(progress) },
                log = { line -> DiagLog.info("run ${state.id}: $line") },
                stopping = { stopping.get() },
            )
        } catch (error: Exception) {
            DiagLog.error("run ${state.id}: engine bootstrap failed ${error.javaClass.simpleName}")
            null
        }
        if (engine == null) {
            finishService("无法恢复：缺少会话材料")
            return
        }
        val stage = try {
            engine.execute()
        } catch (error: Exception) {
            DiagLog.error("run ${state.id}: engine crashed ${error.javaClass.simpleName}")
            state.stage
        }
        DiagLog.info("run ${state.id}: engine stopped at ${stage.wire}")
        finishService(describe(stage, state))
    }

    private fun describe(stage: RunStage, state: RunState): String = when (stage) {
        RunStage.VERIFIED -> "本次记录已确认有效"
        RunStage.SUBMITTED -> "记录已提交，回查未全部通过"
        RunStage.UNCERTAIN -> "写请求结果未知，已停止并保留现场"
        RunStage.FAILED -> "会话失败：${state.lastError ?: "本地校验未通过"}"
        else -> "会话已停止：${stage.wire}"
    }

    private fun publishProgress(progress: RunProgress) {
        val now = SystemClock.elapsedRealtime()
        val update = synchronized(notificationLock) {
            if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) {
                false
            } else {
                lastNotificationAt = now
                true
            }
        }
        if (!update) return
        armWakeLock()
        val text = "已跑 ${clockText(progress.elapsedSec)} / ${progress.distanceKm} km / 打卡 ${progress.checkins} 个"
        synchronized(notificationLock) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun finishService(text: String) {
        synchronized(notificationLock) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
        stopSelf()
    }

    private fun armWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            try {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            } catch (_: Exception) {
                // A missing wake lock only affects battery optimisation, never correctness.
            }
        }
    }

    private fun notification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = if (launch != null) {
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            null
        }
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_RUN)
            .setContentTitle("步道乐飞 · 跑步会话")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(if (sessionStartedAt > 0L) sessionStartedAt else System.currentTimeMillis())
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun clockText(elapsedSec: Int): String {
        val safe = elapsedSec.coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
    }

    override fun onDestroy() {
        stopping.set(true)
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                try {
                    lock.release()
                } catch (_: Exception) {
                    // Releasing twice is harmless for a non-reference-counted lock.
                }
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_TAG = "lepao:run"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val NOTIFICATION_INTERVAL_MS = 15_000L

        private val TERMINAL_STAGES = setOf(
            RunStage.VERIFIED,
            RunStage.SUBMITTED,
            RunStage.UNCERTAIN,
            RunStage.FAILED,
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RunForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunForegroundService::class.java))
        }
    }
}
