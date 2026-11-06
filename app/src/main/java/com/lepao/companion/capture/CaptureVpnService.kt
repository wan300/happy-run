package com.lepao.companion.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.lepao.companion.R
import com.lepao.companion.core.CapturePhase
import com.lepao.companion.core.CaptureStatus
import com.lepao.companion.core.CaptureStore
import com.lepao.companion.core.DiagLog
import com.lepao.companion.core.Notifications
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground VPN that gives the official application a local HTTP proxy.
 *
 * Only `com.lptiyu.tanke` is routed into the tunnel and the tunnel advertises `127.0.0.1:<port>` as
 * its HTTP proxy, so the application's own OkHttp stack sends `CONNECT` requests to
 * [LocalMitmProxy]. Everything that is not `api2.lptiyu.com` is forwarded untouched, which keeps
 * OSS/CDN uploads working. The service never touches the system certificate store.
 */
class CaptureVpnService : VpnService() {

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "capture-boot").apply { isDaemon = true }
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "capture-timer").apply { isDaemon = true }
    }
    private val publisher = StatusPublisher()
    private val tunWriteLock = Any()

    @Volatile
    private var session: CaptureSession? = null

    @Volatile
    private var proxy: LocalMitmProxy? = null

    @Volatile
    private var relay: DnsRelay? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var running = false

    @Volatile
    private var notifying = false

    private var tunThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(PHASE_STARTING_TEXT))
        notifying = true
        if (running) return START_NOT_STICKY
        running = true
        val capture = CaptureSession(
            onStatus = publisher::submit,
            onMaterial = { CaptureStore.updateMaterial(it) },
            onDiag = { DiagLog.info(it) },
        )
        session = capture
        capture.markStarting()
        worker.execute { boot(capture) }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        DiagLog.warn("capture vpn revoked by the system")
        session?.markStopped("VPN 已由系统或其他应用关闭，已保留捕获材料")
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        val capture = session
        if (capture != null && capture.status().phase != CapturePhase.IDLE &&
            capture.status().phase != CapturePhase.ERROR
        ) {
            // Publish the terminal state before the timers and executor are torn down.
            capture.markStopped()
        }
        shutdown()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // The notification may already be gone.
        }
        super.onDestroy()
    }

    private fun boot(capture: CaptureSession) {
        try {
            if (VpnService.prepare(this) != null) {
                fail(capture, "需要 VPN 授权")
                return
            }
            if (!isOfficialInstalled()) {
                fail(capture, "未安装官方乐跑，无法采集登录态")
                return
            }
            if (anotherVpnIsActive()) {
                capture.addHint("检测到其他 VPN 正在运行，若采集无流量请先关闭它再重试")
            }
            val upstreams = systemDnsServers().ifEmpty { FALLBACK_DNS }
            if (upstreams.isEmpty()) {
                DiagLog.warn("capture has no system dns servers to relay")
            }
            val authority = CertAuthority.ensure(this)
            val proxy = LocalMitmProxy(
                sslContext = authority.serverContext(),
                protectSocket = { socket -> protect(socket) },
                onTransaction = { transaction -> capture.record(transaction) },
                onDiag = ::networkDiagnostic,
            )
            val proxyPort = proxy.start()
            this.proxy = proxy
            networkDiagnostic("capture proxy listening on 127.0.0.1:$proxyPort")

            val descriptor = establishTun(proxyPort)
            if (descriptor == null) {
                fail(capture, "VPN 建立失败，请确认没有其他 VPN 占用")
                return
            }
            tun = descriptor
            startTunLoop(capture, descriptor, upstreams)
            capture.markRunning(true)
            scheduleTimeout(capture)
            scheduleProxyDiagnostic(capture)
            DiagLog.info("capture vpn established with local proxy")
        } catch (error: PackageManager.NameNotFoundException) {
            fail(capture, "未安装官方乐跑，无法采集登录态")
        } catch (error: Exception) {
            DiagLog.error("capture boot failed: ${error.javaClass.simpleName}")
            fail(capture, "本地采集通道建立失败：${error.javaClass.simpleName}")
        }
    }

    /** Network-only timings are also available via adb; no headers, cookies or bodies are logged. */
    private fun networkDiagnostic(message: String) {
        DiagLog.info(message)
        android.util.Log.i("CaptureNetwork", message)
    }

    private fun establishTun(proxyPort: Int): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(MTU)
            .addAddress(CLIENT_ADDRESS, 32)
            // 只把隧道内的 DNS 地址放进路由表：官方应用的业务流量（CDN、其它接口、广告 SDK）
            // 继续走真实网络，不会因为“开了采集”而断网。需要解密的 api2.lptiyu.com 通过
            // 系统 HTTP 代理指向 127.0.0.1 的本地代理完成，不经过 TUN。
            .addRoute(DNS_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addAllowedApplication(CaptureController.OFFICIAL_PACKAGE)
            .setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, proxyPort))
            // `setBlocking` defaults to false (non-blocking), which makes an idle tunnel report
            // zero-byte reads or EAGAIN instead of parking the reader; the loop below needs blocking
            // semantics so DNS relaying never spins or dies while the interface is idle.
            .setBlocking(true)
        return builder.establish()
    }

    private fun startTunLoop(capture: CaptureSession, descriptor: ParcelFileDescriptor, upstreams: List<InetAddress>) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val dnsRelay = DnsRelay(
            upstreams = upstreams,
            protect = { socket -> protect(socket) },
            output = { packet -> writeTun(output, packet) },
            onDiag = ::networkDiagnostic,
        )
        relay = dnsRelay
        val thread = Thread({
            val buffer = ByteArray(MAX_TUN_PACKET)
            try {
                while (running) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: IOException) {
                        break
                    } catch (_: Exception) {
                        break
                    }
                    if (read < 0) break // The descriptor reached EOF.
                    if (read == 0) continue // Blocking reads should never report zero, so this is harmless.
                    try {
                        dnsRelay.submit(buffer.copyOf(read), read)
                    } catch (error: Exception) {
                        DiagLog.warn("capture tun packet dropped: ${error.javaClass.simpleName}")
                    }
                }
            } finally {
                try {
                    input.close()
                } catch (_: IOException) {
                    // Ignore.
                }
                try {
                    output.close()
                } catch (_: IOException) {
                    // Ignore.
                }
                if (running) {
                    // The tunnel broke while the service still believed it was capturing.
                    DiagLog.error("capture tun reader stopped unexpectedly")
                    capture.markWarning("本地采集通道已中断，请停止后重试")
                }
            }
        }, "capture-tun")
        thread.isDaemon = true
        thread.start()
        tunThread = thread
        DiagLog.info("capture tun reader started with ${upstreams.size} dns upstreams")
        capture.addHint("采集通道已建立")
    }

    private fun writeTun(output: FileOutputStream, packet: ByteArray) {
        synchronized(tunWriteLock) {
            output.write(packet)
            output.flush()
        }
    }

    private fun scheduleTimeout(capture: CaptureSession) {
        try {
            timer.schedule({
                if (running) {
                    DiagLog.warn("capture reached its ten minute limit")
                    capture.markStopped("已采集 10 分钟，自动停止并保留已捕获材料")
                    stopSelf()
                }
            }, CAPTURE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (_: Exception) {
            // The service is shutting down.
        }
    }

    private fun scheduleProxyDiagnostic(capture: CaptureSession) {
        try {
            timer.schedule({
                val activeProxy = proxy ?: return@schedule
                val dnsRelay = relay
                if (!running || activeProxy.forwardedRequests > 0) return@schedule
                if (dnsRelay != null && dnsRelay.dnsQueryCount > 0) {
                    capture.markWarning("尚未捕获到接口：请确认已在乐跑里退出后重新登录（只打开已登录的乐跑不会产生登录响应）")
                }
            }, PROXY_CHECK_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The service is shutting down.
        }
    }

    private fun fail(capture: CaptureSession, message: String) {
        capture.markError(message)
        stopSelf()
    }

    private fun shutdown() {
        running = false
        notifying = false
        try {
            relay?.close()
        } catch (_: Exception) {
            // Ignore.
        }
        relay = null
        try {
            proxy?.stop()
        } catch (_: Exception) {
            // Ignore.
        }
        proxy = null
        try {
            tun?.close()
        } catch (_: IOException) {
            // Ignore.
        }
        tun = null
        tunThread?.interrupt()
        tunThread = null
        worker.shutdownNow()
        timer.shutdownNow()
    }

    private fun isOfficialInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(CaptureController.OFFICIAL_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun anotherVpnIsActive(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun systemDnsServers(): List<InetAddress> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.dnsServers.filterIsInstance<Inet4Address>()
    }

    private fun buildNotification(text: String): Notification {
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
        return NotificationCompat.Builder(this, Notifications.CHANNEL_CAPTURE)
            .setContentTitle("步道乐飞 · 登录态采集")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
    }

    private fun notificationText(status: CaptureStatus): String = when (status.phase) {
        CapturePhase.IDLE -> "采集已停止"
        CapturePhase.STARTING -> PHASE_STARTING_TEXT
        CapturePhase.RUNNING -> "等待官方乐跑登录流量"
        CapturePhase.LOGIN_SEEN -> "已捕获登录响应，进入乐跑首页或跑步页补全设备上下文"
        CapturePhase.CONTEXT_READY -> "登录态已就绪，可返回本应用完成"
        CapturePhase.ERROR -> status.error ?: "采集出错，请重试"
    }

    private fun updateNotification(text: String) {
        if (!notifying) return
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
            // A missing notification permission must not break the capture.
        }
    }

    /** Coalesces status and notification updates to at most one per second. */
    private inner class StatusPublisher {

        private val lock = Any()
        private var lastPublishedAt = 0L
        private var pending: CaptureStatus? = null
        private var flushScheduled = false
        private var lastNotificationText: String? = null

        fun submit(status: CaptureStatus) {
            synchronized(lock) {
                val elapsed = SystemClock.elapsedRealtime() - lastPublishedAt
                if (elapsed >= MIN_PUBLISH_INTERVAL_MS && pending == null) {
                    publishNow(status)
                    return
                }
                pending = status
                if (flushScheduled) return
                flushScheduled = true
                val delay = (MIN_PUBLISH_INTERVAL_MS - elapsed).coerceAtLeast(0)
                try {
                    timer.schedule({ flush() }, delay, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    // The timer is gone (shutdown path): never drop the terminal status.
                    flushScheduled = false
                    pending = null
                    publishNow(status)
                }
            }
        }

        private fun flush() {
            synchronized(lock) {
                flushScheduled = false
                val status = pending ?: return
                pending = null
                publishNow(status)
            }
        }

        private fun publishNow(status: CaptureStatus) {
            lastPublishedAt = SystemClock.elapsedRealtime()
            try {
                CaptureStore.updateStatus(status)
            } catch (_: Exception) {
                // The store is initialised by the application; never crash the capture over it.
            }
            val text = notificationText(status)
            if (text != lastNotificationText) {
                lastNotificationText = text
                updateNotification(text)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val SESSION_NAME = "lepao-capture"
        private const val MTU = 1500
        private const val CLIENT_ADDRESS = "10.66.0.2"
        private const val DNS_ADDRESS = "10.66.0.1"
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_TUN_PACKET = 32 * 1024
        private const val MIN_PUBLISH_INTERVAL_MS = 1_000L
        private const val CAPTURE_TIMEOUT_MINUTES = 10L
        private const val PROXY_CHECK_SECONDS = 30L
        private const val PHASE_STARTING_TEXT = "正在建立本地采集通道"

        /** 运营商只下发 IPv6 DNS 时，IPv4 中继会拿不到上游；退回公共解析器保证解析可用。 */
        private val FALLBACK_DNS = listOf(
            InetAddress.getByName("223.5.5.5"),
            InetAddress.getByName("119.29.29.29"),
        )

        fun start(context: Context) {
            val intent = Intent(context, CaptureVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureVpnService::class.java))
        }
    }
}
