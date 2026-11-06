package com.lepao.companion

import android.app.Application
import com.lepao.companion.core.CaptureStore
import com.lepao.companion.core.DiagLog
import com.lepao.companion.core.Notifications
import com.lepao.companion.core.RunStore
import com.lepao.companion.core.SessionStore

class LepaoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        SessionStore.init(this)
        RunStore.init(this)
        CaptureStore.init(this)
        CaptureStore.restore()
        Notifications.ensureChannels(this)
        DiagLog.info("app started version=${BuildConfig.VERSION_NAME}")
    }
}
