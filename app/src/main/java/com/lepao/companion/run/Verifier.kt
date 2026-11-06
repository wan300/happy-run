package com.lepao.companion.run

import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.Identity
import com.lepao.companion.core.JsonUtil
import com.lepao.companion.core.Protocol
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** The `Run/stopRunV278` acknowledgement fields that must agree with the local measurement. */
data class StopAck(
    val recordId: String,
    val uid: String,
    val startTime: String,
    val gameId: String,
    val distance: Any?,
    val duration: Any?,
) {
    companion object {
        fun from(data: Map<String, Any?>): StopAck? {
            val recordId = text(data["record_id"]) ?: return null
            if (recordId.isEmpty() || !recordId.all { it.isDigit() }) return null
            return StopAck(
                recordId = recordId,
                uid = text(data["uid"]) ?: "",
                startTime = text(data["start_time"]) ?: "",
                gameId = text(data["game_id"]) ?: "",
                distance = data["distance"],
                duration = data["time"],
            )
        }

        private fun text(value: Any?): String? = when (value) {
            null -> null
            is Number -> JsonUtil.canonical(value)
            else -> value.toString()
        }
    }
}

/** The `Run/recordDetailV270` fields used by the read-only acceptance pass. */
data class RecordDetail(
    val uid: String,
    val startTime: String,
    val gameId: String,
    val recordStatus: String,
    val distance: Any?,
    val duration: Any?,
    val stepNum: Any?,
    val file: String,
) {
    companion object {
        fun from(data: Map<String, Any?>): RecordDetail? {
            if (data.isEmpty()) return null
            return RecordDetail(
                uid = text(data["uid"]) ?: "",
                startTime = text(data["start_time"]) ?: "",
                gameId = text(data["game_id"]) ?: "",
                recordStatus = text(data["record_status"]) ?: "",
                distance = data["distance"],
                duration = data["time"],
                stepNum = data["step_num"],
                file = text(data["file"]) ?: "",
            )
        }

        private fun text(value: Any?): String? = when (value) {
            null -> null
            is Number -> JsonUtil.canonical(value)
            else -> value.toString()
        }
    }
}

/**
 * Acceptance checks for a submitted run.
 *
 * Both sets mirror the PC client exactly: five identity/measurement checks on the stop
 * acknowledgement, eight on the read-only record detail. Nothing here writes or retries.
 */
object Verifier {

    val STOP_CHECK_KEYS: List<String> = listOf(
        "uid_matches", "start_time_matches", "game_id_matches", "distance_matches", "duration_matches",
    )

    val DETAIL_CHECK_KEYS: List<String> = listOf(
        "uid_matches", "start_time_matches", "game_id_matches", "record_valid",
        "distance_matches", "duration_matches", "steps_match", "route_key_matches",
    )

    fun stopChecks(
        ack: StopAck,
        uid: String,
        startTime: String,
        gameId: String,
        distance: String,
        usedTime: Int,
    ): Map<String, Boolean> = linkedMapOf(
        "uid_matches" to (ack.uid == uid),
        "start_time_matches" to (ack.startTime == startTime),
        "game_id_matches" to (ack.gameId == gameId),
        "distance_matches" to JsonUtil.numericEqual(ack.distance, distance),
        "duration_matches" to JsonUtil.numericEqual(ack.duration, usedTime),
    )

    fun detailChecks(
        detail: RecordDetail,
        uid: String,
        startTime: String,
        gameId: String,
        distance: String,
        usedTime: Int,
        stepNum: Int,
        routeKey: String?,
    ): Map<String, Boolean> = linkedMapOf(
        "uid_matches" to (detail.uid == uid),
        "start_time_matches" to (detail.startTime == startTime),
        "game_id_matches" to (detail.gameId == gameId),
        "record_valid" to (detail.recordStatus == "1"),
        "distance_matches" to JsonUtil.numericEqual(detail.distance, distance),
        "duration_matches" to JsonUtil.numericEqual(detail.duration, usedTime),
        "steps_match" to JsonUtil.numericEqual(detail.stepNum, stepNum),
        "route_key_matches" to routeKeyMatches(detail.file, routeKey),
    )

    /** `unquote(urlsplit(file).path).endswith("/" + key)`, without a URL dependency. */
    fun routeKeyMatches(file: String, routeKey: String?): Boolean {
        if (routeKey.isNullOrBlank()) return false
        val path = percentDecode(pathOf(file))
        val normalized = if (path.startsWith("/")) path else "/$path"
        return normalized.endsWith("/$routeKey")
    }

    fun pathOf(file: String): String {
        var text = file.trim()
        if (text.isEmpty()) return ""
        val schemeEnd = text.indexOf("://")
        if (schemeEnd >= 0) {
            val afterScheme = text.substring(schemeEnd + 3)
            val slash = afterScheme.indexOf('/')
            text = if (slash >= 0) afterScheme.substring(slash) else "/"
        }
        val query = text.indexOfFirst { it == '?' || it == '#' }
        if (query >= 0) text = text.substring(0, query)
        return text
    }

    /**
     * `urllib.parse.unquote` semantics: decode `%XX` sequences only, never turn `+` into a space
     * (object keys are percent-encoded by OSS, so `+` is a literal character there).
     */
    fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val out = ByteArrayOutputStream(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%' && index + 2 < text.length) {
                val hex = text.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    index += 3
                    continue
                }
            }
            out.write(char.toString().toByteArray(StandardCharsets.UTF_8))
            index += 1
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}

/** Read-only detail fetch used by both the engine and `RunController.verify`. */
object DetailReader {

    data class Fetched(val detail: RecordDetail, val data: Map<String, Any?>)

    fun fetch(
        transport: RunTransport,
        identity: Identity,
        recordId: String,
        epoch: Long,
    ): Fetched? {
        val request = Protocol.buildRequest(
            Endpoints.RECORD_DETAIL,
            identity,
            mapOf("record_id" to recordId),
            epoch,
        )
        val response = transport.api(request)
        val detail = RecordDetail.from(response.data) ?: return null
        return Fetched(detail, response.data)
    }
}
