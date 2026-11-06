package com.lepao.companion.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lepao.companion.core.CaptureStatus
import com.lepao.companion.core.CaptureStore
import com.lepao.companion.core.SessionContext

/**
 * UI-facing entry points for the local login-capture proxy.
 *
 * A complete implementation lives in this package (`capture/`). The signatures below are the
 * stable contract used by `ui/`.
 */
object CaptureController {

    const val OFFICIAL_PACKAGE = "com.lptiyu.tanke"

    /** VpnService consent intent, or null when consent is already granted. */
    fun prepareConsent(activity: Activity): Intent? {
        return android.net.VpnService.prepare(activity)
    }

    fun startService(context: Context) {
        CaptureVpnService.start(context)
    }

    fun stop(context: Context) {
        CaptureVpnService.stop(context)
    }

    fun status(): CaptureStatus = CaptureStore.status

    /** Build the persistent session from what the proxy captured; null until complete. */
    fun assembleSession(): SessionContext? = CaptureStore.material?.toSessionContext()

    fun openOfficialApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(OFFICIAL_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
