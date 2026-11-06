package com.lepao.companion.run

import android.content.Context
import android.os.SystemClock
import com.lepao.companion.core.ApiTransport
import com.lepao.companion.core.ArtifactKind
import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.OssReceipt
import com.lepao.companion.core.OssUploader
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.RunClock
import com.lepao.companion.core.WireRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The flat part of a decrypted API envelope the run engine needs.
 *
 * The engine never sees `org.json`, so its whole state machine is testable on a plain JVM.
 */
class ApiResponse(
    val data: Map<String, Any?>,
    /** `null` means the field was absent; an empty list means the server reported no offline points. */
    val offlinePoints: List<Any?>?,
) {
    fun text(key: String): String? = when (val value = data[key]) {
        null -> null
        is Number -> JsonText.canonical(value)
        else -> value.toString()
    }

    fun anyMap(): Map<String, Any?> = data

    companion object {
        private object JsonText {
            fun canonical(value: Number): String {
                val asDouble = value.toDouble()
                return if (asDouble.isFinite() && asDouble == Math.floor(asDouble) &&
                    kotlin.math.abs(asDouble) < 1e15
                ) {
                    asDouble.toLong().toString()
                } else {
                    value.toString()
                }
            }
        }
    }
}

/** One POST against the acceptance API. Implementations must not retry. */
fun interface RunTransport {
    fun api(request: WireRequest): ApiResponse
}

/** OSS credentials extracted from `report/getOssSts`. */
data class OssCredentials(
    val bucket: String,
    val expiration: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String,
) {
    fun isComplete(): Boolean =
        bucket.isNotBlank() && expiration.isNotBlank() && accessKeyId.isNotBlank() &&
            accessKeySecret.isNotBlank() && securityToken.isNotBlank()

    companion object {
        fun from(response: ApiResponse): OssCredentials? {
            val credentials = OssCredentials(
                bucket = response.text("bucket") ?: "",
                expiration = response.text("Expiration") ?: "",
                accessKeyId = response.text("AccessKeyId") ?: "",
                accessKeySecret = response.text("AccessKeySecret") ?: "",
                securityToken = response.text("SecurityToken") ?: "",
            )
            return if (credentials.isComplete()) credentials else null
        }
    }
}

/** One header-V1 OSS PUT. Any exception means the upload outcome is uncertain. */
fun interface RunUploader {
    fun upload(credentials: OssCredentials, kind: ArtifactKind, key: String, body: ByteArray): OssReceipt
}

/** Rebuilds the deterministic track for a plan; identical for the same seed and duration. */
interface TrackProvider {
    fun build(plan: PlannedRun): List<TrackPoint>
    fun position(track: List<TrackPoint>, elapsedSeconds: Double): TrackPoint
}

/** Builds the uploadable artifact bodies for a plan. */
interface ArtifactProvider {
    fun build(plan: PlannedRun, startEpoch: Long): Map<String, ArtifactBlob>
}

/** Monotonic + wall clock + sleep, so the heartbeat loop can be driven by a virtual clock in tests. */
interface RunTicker {
    fun elapsedRealtimeMs(): Long
    fun nowEpoch(): Long
    fun sleep(millis: Long)
}

class AndroidRunTicker : RunTicker {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun nowEpoch(): Long = RunClock.nowEpoch()
    override fun sleep(millis: Long) = Thread.sleep(millis)
}

/** `ApiTransport` -> engine seam. Converts the decrypted envelope into plain Kotlin values. */
class CoreRunTransport(private val transport: ApiTransport) : RunTransport {

    override fun api(request: WireRequest): ApiResponse {
        val envelope = transport.api(request)
        val data = LinkedHashMap<String, Any?>()
        val dataObject = envelope.optJSONObject("data")
        if (dataObject != null) {
            val keys = dataObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                data[key] = plain(dataObject.opt(key))
            }
        }
        val offline = envelope.opt("offlinePoints")
        val offlinePoints = if (offline is JSONArray) {
            (0 until offline.length()).map { plain(offline.opt(it)) }
        } else {
            null
        }
        return ApiResponse(data, offlinePoints)
    }

    private fun plain(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject, is JSONArray -> value.toString()
        else -> value
    }
}

/** `OssUploader` -> engine seam. */
class CoreRunUploader(private val uploader: OssUploader) : RunUploader {

    override fun upload(
        credentials: OssCredentials,
        kind: ArtifactKind,
        key: String,
        body: ByteArray,
    ): OssReceipt {
        val sts = JSONObject().apply {
            put("bucket", credentials.bucket)
            put("Expiration", credentials.expiration)
            put("AccessKeyId", credentials.accessKeyId)
            put("AccessKeySecret", credentials.accessKeySecret)
            put("SecurityToken", credentials.securityToken)
        }
        return uploader.upload(sts, kind, key, body)
    }
}

/** `RouteEngine` -> engine seam. */
class AndroidTrackProvider(private val context: Context) : TrackProvider {

    override fun build(plan: PlannedRun): List<TrackPoint> =
        RouteEngine.buildTrack(context, plan)
            .map { sample -> TrackPoint(sample.t, sample.lat, sample.lng, sample.arc) }

    override fun position(track: List<TrackPoint>, elapsedSeconds: Double): TrackPoint {
        if (track.isEmpty()) throw IllegalStateException("轨迹为空")
        if (elapsedSeconds <= track.first().t) return track.first()
        if (elapsedSeconds >= track.last().t) return track.last()
        return interpolate(track, elapsedSeconds)
    }

    private fun interpolate(track: List<TrackPoint>, elapsed: Double): TrackPoint {
        var low = 0
        var high = track.size - 1
        while (low < high - 1) {
            val middle = (low + high) / 2
            if (track[middle].t <= elapsed) low = middle else high = middle
        }
        val a = track[low]
        val b = track[high]
        val span = b.t - a.t
        val ratio = if (span <= 0.0) 0.0 else (elapsed - a.t) / span
        return TrackPoint(
            t = elapsed,
            lat = a.lat + (b.lat - a.lat) * ratio,
            lng = a.lng + (b.lng - a.lng) * ratio,
            arc = a.arc + (b.arc - a.arc) * ratio,
        )
    }
}

/** `RunArtifacts` -> engine seam. */
class AndroidArtifactProvider(private val context: Context) : ArtifactProvider {

    override fun build(plan: PlannedRun, startEpoch: Long): Map<String, ArtifactBlob> =
        RunArtifacts.build(context, plan, startEpoch)

    companion object {
        /** Artifact kinds required by the current configuration, in upload order. */
        fun kindsFor(gyroscopeRequired: Boolean): List<ArtifactKind> = listOf(
            ArtifactKinds.SCREENSHOT,
            ArtifactKinds.ROUTE,
        ) + if (gyroscopeRequired) listOf(ArtifactKinds.SENSOR) else emptyList()
    }
}
