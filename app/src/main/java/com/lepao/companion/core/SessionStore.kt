package com.lepao.companion.core

import android.content.Context
import java.io.File
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Filesystem layout and encrypted persistence for the account session. */
object SessionStore {

    private const val SESSION_FILE = "session.enc.json"
    private lateinit var appContext: Context
    private lateinit var secure: SecureStore

    fun init(context: Context) {
        appContext = context.applicationContext
        secure = SecureStore(appContext)
    }

    private fun file(): File = File(appContext.filesDir, SESSION_FILE)

    fun load(): SessionContext? {
        val json = secure.readJson(file()) ?: return null
        return try {
            SessionContext.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    fun save(session: SessionContext) {
        secure.writeJson(file(), session.toJson())
    }

    fun clear() {
        file().delete()
    }

    fun exists(): Boolean = file().isFile
}

/** The campus-run day is always evaluated in China Standard Time, independent of the phone zone. */
object RunClock {
    val ZONE: ZoneOffset = ZoneOffset.ofHours(8)

    fun nowEpoch(): Long = System.currentTimeMillis() / 1000

    fun local(epoch: Long): ZonedDateTime = ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochSecond(epoch),
        ZONE,
    )

    fun secondsOfDay(epoch: Long): Int {
        val time: LocalTime = local(epoch).toLocalTime()
        return time.hour * 3600 + time.minute * 60 + time.second
    }

    fun isoDate(epoch: Long): String = local(epoch).toLocalDate().toString()
}
