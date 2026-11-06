package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.Protocol
import com.lepao.companion.core.StateException
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.cos

/** One uploadable artifact body, already in the exact wire representation. */
data class ArtifactBlob(
    val kind: com.lepao.companion.core.ArtifactKind,
    val body: ByteArray,
    val samples: Int,
) {
    val sha256: String
        get() = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Route / screenshot / sensor generation (`docs/run-engine.md` §6.3).
 *
 * - `route`: compact JSON array + `"\n"`, encrypted with the `NEW_GAME` profile; the body is the
 *   ASCII bytes of the Base64 envelope.
 * - `sensor`: 1 Hz plain `y9.c` array (`durationSec + 1` rows), compact JSON + `"\n"`.
 * - `screenshot`: 720x1080 PNG rendered by [PngCanvas] without Android graphics.
 *
 * The `Context` overload reads `assets/corridor.json`; the corridor overload is pure Kotlin so the
 * same generation path is covered by JVM unit tests.
 */
object RunArtifacts {

    private const val ROUTE_PROFILE = "NEW_GAME"
    private const val SENSOR_MIN_ROWS = 51
    private const val B_MIN = 0.995
    private const val B_MAX = 1.012
    private val C_OPTIONS = intArrayOf(338, 352, 366, 407)

    fun build(context: Context, plan: PlannedRun, startEpoch: Long): Map<String, ArtifactBlob> =
        build(RouteEngine.corridor(context), plan, startEpoch)

    /**
     * Build all three artifacts from an explicit corridor polyline (`[lat, lng]` pairs).
     *
     * The map is keyed by `ArtifactKind.name` in upload order: `screenshot`, `route`, `sensor`.
     */
    fun build(corridor: List<DoubleArray>, plan: PlannedRun, startEpoch: Long): Map<String, ArtifactBlob> {
        if (startEpoch <= 0L) throw StateException("起跑时间戳无效")
        if (plan.durationSec <= 0) throw StateException("跑步时长必须为正")
        if (plan.durationSec + 1 < SENSOR_MIN_ROWS) throw StateException("传感器样本不足 51 条")
        val track = RouteEngine.buildTrack(
            corridor,
            plan.durationSec,
            plan.seed,
            RouteEngine.waypoints(plan.via),
        )
        return buildFromTrack(track, plan, startEpoch)
    }

    private fun buildFromTrack(
        track: List<RouteEngine.Sample>,
        plan: PlannedRun,
        startEpoch: Long,
    ): Map<String, ArtifactBlob> {
        val routePlain = routeJson(track, plan.seed) + "\n"
        val routeBody = Protocol.encryptText(routePlain, ROUTE_PROFILE).toByteArray(Charsets.US_ASCII)
        val sensorText = sensorJson(track, plan.durationSec, startEpoch, plan.seed) + "\n"
        val sensorBody = sensorText.toByteArray(Charsets.UTF_8)
        val screenshot = PngCanvas.screenshot(track)

        val result = LinkedHashMap<String, ArtifactBlob>(3)
        result[ArtifactKinds.SCREENSHOT.name] = ArtifactBlob(ArtifactKinds.SCREENSHOT, screenshot, 0)
        result[ArtifactKinds.ROUTE.name] = ArtifactBlob(ArtifactKinds.ROUTE, routeBody, track.size)
        result[ArtifactKinds.SENSOR.name] =
            ArtifactBlob(ArtifactKinds.SENSOR, sensorBody, plan.durationSec + 1)
        return result
    }

    fun kindsFor(gyroscopeRequired: Boolean): List<com.lepao.companion.core.ArtifactKind> {
        val kinds = mutableListOf(ArtifactKinds.SCREENSHOT, ArtifactKinds.ROUTE)
        if (gyroscopeRequired) kinds.add(ArtifactKinds.SENSOR)
        return kinds
    }

    /**
     * Writer schema of the encrypted route body: `{o,a,s,l}` everywhere, three random indices also
     * carry `{b,c}` and the second-to-last sample carries `{p:1}`.
     */
    internal fun routeJson(track: List<RouteEngine.Sample>, seed: Long): String {
        if (track.isEmpty()) throw StateException("轨迹为空")
        val count = track.size
        val rng = Random(seed + 7)
        val extra = if (count - 4 >= 3) {
            val pool = (2..(count - 3)).toMutableList()
            val picked = ArrayList<Int>(3)
            repeat(3) {
                picked.add(pool.removeAt(rng.nextInt(pool.size)))
            }
            picked.sort()
            picked
        } else {
            emptyList()
        }
        val marked = if (count >= 3) count - 2 else -1

        val builder = StringBuilder(count * 96)
        builder.append('[')
        for (index in 0 until count) {
            if (index > 0) builder.append(',')
            val sample = track[index]
            builder.append("{\"o\":").append(RouteEngine.f32(sample.lng))
                .append(",\"a\":").append(RouteEngine.f32(sample.lat))
                .append(",\"s\":").append(RouteEngine.f32(round2(max(0.1, sample.speed))))
                .append(",\"l\":1")
            if (index in extra) {
                val b = round6(B_MIN + rng.nextDouble() * (B_MAX - B_MIN))
                builder.append(",\"b\":").append(RouteEngine.f32(b))
                    .append(",\"c\":").append(C_OPTIONS[rng.nextInt(C_OPTIONS.size)])
            }
            if (index == marked) builder.append(",\"p\":1")
            builder.append('}')
        }
        builder.append(']')
        return builder.toString()
    }

    /** 1 Hz `y9.c` writer schema: `{n,a,t,ax,ay,az,gx,gy,gz}` per second, plaintext. */
    internal fun sensorJson(
        track: List<RouteEngine.Sample>,
        durationSec: Int,
        startEpoch: Long,
        seed: Long,
    ): String {
        if (track.isEmpty()) throw StateException("轨迹为空")
        val rng = Random(seed + 11)
        val phase = rng.nextDouble() * 2.0 * Math.PI
        val builder = StringBuilder((durationSec + 1) * 130)
        builder.append('[')
        for (second in 0..durationSec) {
            if (second > 0) builder.append(',')
            val index = minOf(second / 2, track.size - 1)
            val sample = track[index]
            val beat = 2.0 * Math.PI * 2.27 * second + phase
            val ax = 0.92 * sin(beat + 0.6) + rng.nextGaussian() * 0.08
            val ay = 0.71 * cos(beat) + rng.nextGaussian() * 0.08
            val az = 9.46 + 1.05 * sin(beat) + rng.nextGaussian() * 0.10
            val gx = 0.34 * sin(beat + 1.2) + rng.nextGaussian() * 0.02
            val gy = 0.29 * cos(beat * 0.5) + rng.nextGaussian() * 0.02
            val gz = 0.55 * sin(beat * 0.5 + 0.4) + rng.nextGaussian() * 0.02
            builder.append("{\"n\":\"").append(fixed(sample.lng, 6))
                .append("\",\"a\":\"").append(fixed(sample.lat, 6))
                .append("\",\"t\":").append(startEpoch * 1000L + second * 1000L)
                .append(",\"ax\":\"").append(fixed(ax, 2))
                .append("\",\"ay\":\"").append(fixed(ay, 2))
                .append("\",\"az\":\"").append(fixed(az, 2))
                .append("\",\"gx\":\"").append(fixed(gx, 2))
                .append("\",\"gy\":\"").append(fixed(gy, 2))
                .append("\",\"gz\":\"").append(fixed(gz, 2))
                .append("\"}")
        }
        builder.append(']')
        return builder.toString()
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    private fun round6(value: Double): Double = round(value * 1_000_000.0) / 1_000_000.0

    private fun fixed(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)
}
