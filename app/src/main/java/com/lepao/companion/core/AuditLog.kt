package com.lepao.companion.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Append-only encrypted audit trail for every request, response and upload receipt. */
class AuditLog(context: Context, private val file: File) {

    private val secure = SecureStore(context)

    fun recordRequest(endpoint: String, fields: Map<String, Any?>, body: ByteArray) {
        val payload = JSONObject().apply {
            put("stage", "request")
            put("endpoint", endpoint)
            put("epoch", RunClock.nowEpoch())
            put("fields", JSONObject(fields as Map<*, *>))
            put("body_b64", Base64.getEncoder().encodeToString(body))
        }
        append(payload)
    }

    fun recordResponse(endpoint: String, statusCode: Int, headers: Map<String, List<String>>, body: ByteArray) {
        val headerObject = JSONObject()
        headers.forEach { (name, values) ->
            headerObject.put(name, org.json.JSONArray().apply { values.forEach(::put) })
        }
        val payload = JSONObject().apply {
            put("stage", "response")
            put("endpoint", endpoint)
            put("http_status", statusCode)
            put("epoch", RunClock.nowEpoch())
            put("headers", headerObject)
            put("body_b64", Base64.getEncoder().encodeToString(body))
        }
        append(payload)
    }

    fun recordEvent(stage: String, endpoint: String?, detail: String? = null) {
        val payload = JSONObject().apply {
            put("stage", stage)
            if (endpoint != null) put("endpoint", endpoint) else put("endpoint", JSONObject.NULL)
            put("epoch", RunClock.nowEpoch())
            if (detail != null) put("detail", detail)
        }
        append(payload)
    }

    fun recordOss(stage: String, key: String, bytes: Int, sha256: String, headers: Map<String, String>) {
        val payload = JSONObject().apply {
            put("stage", stage)
            put("object_key", key)
            put("bytes", bytes)
            put("sha256", sha256)
            put("headers", JSONObject(headers as Map<*, *>))
            put("epoch", RunClock.nowEpoch())
        }
        append(payload)
    }

    private fun append(payload: JSONObject) {
        try {
            secure.appendLine(file, payload.toString())
        } catch (_: Exception) {
            // Diagnostics must not break the run.
        }
    }
}
