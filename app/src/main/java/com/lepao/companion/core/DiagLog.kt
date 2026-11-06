package com.lepao.companion.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic ring log. It deliberately records only stages, endpoints and outcomes; request
 * bodies and credentials belong in the encrypted audit file.
 */
object DiagLog {

    private const val MAX_BYTES = 256 * 1024L
    private lateinit var file: File
    private val lock = Any()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, "logs/app.log")
        file.parentFile?.mkdirs()
    }

    fun info(message: String) = append("I", message)

    fun warn(message: String) = append("W", message)

    fun error(message: String) = append("E", message)

    fun recent(limitBytes: Int = 24 * 1024): String {
        if (!::file.isInitialized || !file.isFile) return ""
        return try {
            val text = file.readText()
            if (text.length <= limitBytes) text else text.takeLast(limitBytes)
        } catch (_: Exception) {
            ""
        }
    }

    private fun append(level: String, message: String) {
        if (!::file.isInitialized) return
        val line = "${format.format(Date())} $level $message\n"
        synchronized(lock) {
            try {
                file.appendText(line)
                if (file.length() > MAX_BYTES) {
                    val tail = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText(tail)
                }
            } catch (_: Exception) {
                // Logging must never break a run.
            }
        }
    }
}
