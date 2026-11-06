package com.lepao.companion.run

import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.COMMON_FIELDS
import com.lepao.companion.core.DeliveryUncertain
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.Identity
import com.lepao.companion.core.OssReceipt
import com.lepao.companion.core.PlannedPoint
import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.RunStage
import com.lepao.companion.core.TimeRule
import com.lepao.companion.core.WireRequest

/** Shared, Android-free fakes for the run state machine tests. */
object RunFixtures {

    const val START_EPOCH = 1_700_000_000L
    const val START_TIME = "1700000000"
    const val UID = "12345"
    const val SCHOOL_ID = "1001"
    const val GAME_ID = "game-1"
    const val TERM_ID = "term-1"
    const val FORMAL_STR = "0123456789abcdef0123456789abcdef"
    const val RECORD_ID = "259921"
    const val DISTANCE = "2.03"
    const val DURATION = 741
    const val STEPS = 1690
    const val SESSION_ID = "run-test-1"

    val RULE = TimeRule(
        startTime = 0,
        endTime = 86400,
        minLogNum = 2,
        minDistanceKm = 1.0,
        maxDistanceKm = 3.0,
        minPaceSecPerKm = 300.0,
        maxPaceSecPerKm = 420.0,
    )

    fun identity(): Identity {
        val map = LinkedHashMap<String, String>()
        for (key in COMMON_FIELDS) map[key] = "wire-$key"
        map["uid"] = UID
        map["school_id"] = SCHOOL_ID
        map["mobileModel"] = "Pixel 6"
        map["mobileDeviceId"] = "device-1"
        map["version"] = "278"
        map["version_name"] = "2.7.8"
        map["mobileOsVersion"] = "12"
        map["ostype"] = "android"
        map["student_num"] = "20240001"
        map["token"] = "access-token"
        return Identity(map, "umid-observed")
    }

    fun plan(
        points: List<PlannedPoint> = listOf(
            PlannedPoint("24560", 22.0, 30.0002, 120.0),
            PlannedPoint("2894", 242.0, 30.0024, 120.0),
        ),
        durationSec: Int = DURATION,
        gyroscope: Boolean = false,
    ): PlannedRun = PlannedRun(
        seed = 42L,
        durationSec = durationSec,
        distanceKm = DISTANCE,
        stepNum = STEPS,
        stepInterval = 60,
        passTotal = 366,
        passEffectiveNum = 0,
        areaValid = "1",
        collector = "android-app",
        points = points,
        rule = RULE,
        createdAt = START_EPOCH,
    )

    fun session(gyroscope: Boolean = false): RunSessionInfo = RunSessionInfo(
        sessionId = SESSION_ID,
        gameId = GAME_ID,
        termId = TERM_ID,
        startTime = START_TIME,
        formalStr = FORMAL_STR,
        startedEpoch = START_EPOCH,
        startedElapsedRealtime = 0L,
        gyroscopeRequired = gyroscope,
        deviceFields = mapOf("oaid" to "oaid-value", "mac" to "mac-value"),
        ipAddress = "192.168.1.2",
    )

    fun artifactBlobs(gyroscope: Boolean = false): Map<String, ArtifactBlob> {
        val blobs = linkedMapOf(
            ArtifactKinds.SCREENSHOT.name to ArtifactBlob(
                ArtifactKinds.SCREENSHOT,
                "png-body".toByteArray(),
                1,
            ),
            ArtifactKinds.ROUTE.name to ArtifactBlob(
                ArtifactKinds.ROUTE,
                "route-body".toByteArray(),
                371,
            ),
        )
        if (gyroscope) {
            blobs[ArtifactKinds.SENSOR.name] = ArtifactBlob(
                ArtifactKinds.SENSOR,
                "sensor-body".toByteArray(),
                742,
            )
        }
        return blobs
    }
}

class FakeJournal(initialStage: RunStage = RunStage.RUNNING) : RunJournal {

    override var stage: RunStage = initialStage
    override fun moveTo(stage: RunStage) {
        this.stage = stage
    }

    override var heartbeatCount: Int = 0
    override var nextLocationElapsed: Long = 60L
    override var locationInterval: Int = 60
    override var uploadPending: String? = null
    override var recordId: String? = null
    override var lastError: String? = null
    override var lastNote: String? = null

    private val uploadMap = LinkedHashMap<String, OssReceipt>()
    private val keys = LinkedHashMap<String, String>()
    private var manifestValue: FinishManifest? = null
    private var stopChecksValue: Map<String, Boolean> = emptyMap()
    private var verificationValue: Map<String, Boolean> = emptyMap()

    var saves: Int = 0
    val savedStages: MutableList<RunStage> = ArrayList()

    override fun uploads(): Map<String, OssReceipt> = uploadMap
    override fun putUpload(receipt: OssReceipt) {
        uploadMap[receipt.kind] = receipt
    }

    override fun objectKeys(): Map<String, String> = keys
    override fun putObjectKeys(keys: Map<String, String>) {
        this.keys.clear()
        this.keys.putAll(keys)
    }

    override fun manifest(): FinishManifest? = manifestValue
    override fun setManifest(manifest: FinishManifest) {
        manifestValue = manifest
    }

    override fun stopChecks(): Map<String, Boolean> = stopChecksValue
    override fun setStopResult(checks: Map<String, Boolean>, data: Map<String, Any?>) {
        stopChecksValue = LinkedHashMap(checks)
    }

    override fun verification(): Map<String, Boolean> = verificationValue
    override fun setVerification(checks: Map<String, Boolean>, data: Map<String, Any?>) {
        verificationValue = LinkedHashMap(checks)
    }

    override fun save() {
        saves += 1
        savedStages.add(stage)
    }
}

class FakeTicker(startEpoch: Long = RunFixtures.START_EPOCH) : RunTicker {

    var elapsedMs: Long = 0L
    private var epochMs: Long = startEpoch * 1000L

    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun nowEpoch(): Long = epochMs / 1000L
    override fun sleep(millis: Long) {
        elapsedMs += millis
        epochMs += millis
    }

    fun jumpToSeconds(seconds: Long) {
        val delta = seconds * 1000L - elapsedMs
        if (delta > 0) {
            elapsedMs += delta
            epochMs += delta
        }
    }

    /** Simulates a wall-clock change without moving the monotonic clock. */
    fun shiftEpochSeconds(delta: Long) {
        epochMs += delta * 1000L
    }
}

class FakeTransport(
    private val ticker: FakeTicker,
    private val journalProvider: () -> RunJournal,
    private val identity: Identity = RunFixtures.identity(),
) : RunTransport {

    val calls: MutableList<WireRequest> = ArrayList()
    val stageAtCall: MutableList<RunStage> = ArrayList()
    val elapsedAtCall: MutableList<Long> = ArrayList()

    var failOn: String? = null

    /** How many matching calls fail before the endpoint starts answering (default: always). */
    var failTimes: Int = Int.MAX_VALUE
    var failure: Exception = DeliveryUncertain("simulated timeout")
    var offlinePointsForLocation: List<Any?>? = emptyList()
    var stopRecordId: String = RunFixtures.RECORD_ID

    /** Overrides the route object key echoed by the fake record detail, for mismatch tests. */
    var detailRouteKey: String? = null

    override fun api(request: WireRequest): ApiResponse {
        calls.add(request)
        stageAtCall.add(journalProvider().stage)
        elapsedAtCall.add(ticker.elapsedRealtimeMs() / 1000L)
        if (request.endpoint == failOn && failTimes > 0) {
            failTimes -= 1
            throw failure
        }
        return when (request.endpoint) {
            Endpoints.LOCATION -> ApiResponse(emptyMap(), offlinePointsForLocation)
            Endpoints.OSS_STS -> ApiResponse(
                mapOf(
                    "bucket" to "lptiyu-data",
                    "Expiration" to "2099-01-01T00:00:00Z",
                    "AccessKeyId" to "access-key",
                    "AccessKeySecret" to "access-secret",
                    "SecurityToken" to "security-token",
                ),
                emptyList(),
            )
            Endpoints.STOP -> ApiResponse(stopData(), emptyList())
            Endpoints.GYROSCOPE -> ApiResponse(mapOf("status" to 1), emptyList())
            Endpoints.RECORD_DETAIL -> ApiResponse(detailData(), emptyList())
            else -> throw IllegalStateException("unexpected endpoint ${request.endpoint}")
        }
    }

    fun countOf(endpoint: String): Int = calls.count { it.endpoint == endpoint }

    private fun stopData(): Map<String, Any?> = mapOf(
        "record_id" to stopRecordId,
        "uid" to identity["uid"],
        "start_time" to RunFixtures.START_TIME,
        "game_id" to RunFixtures.GAME_ID,
        "distance" to RunFixtures.DISTANCE,
        "time" to RunFixtures.DURATION,
    )

    private fun detailData(): Map<String, Any?> {
        val routeKey = detailRouteKey ?: journalProvider().objectKeys()[ArtifactKinds.ROUTE.name] ?: ""
        return mapOf(
            "uid" to identity["uid"],
            "start_time" to RunFixtures.START_TIME,
            "game_id" to RunFixtures.GAME_ID,
            "record_status" to "1",
            "distance" to RunFixtures.DISTANCE,
            "time" to RunFixtures.DURATION.toString(),
            "step_num" to RunFixtures.STEPS.toString(),
            "file" to "https://lptiyu-data.oss-cn-hangzhou.aliyuncs.com/$routeKey?Expires=1",
        )
    }
}

class FakeUploader(private val journalProvider: () -> RunJournal) : RunUploader {

    val calls: MutableList<String> = ArrayList()
    val pendingAtCall: MutableList<String?> = ArrayList()
    var failOn: String? = null

    override fun upload(
        credentials: OssCredentials,
        kind: com.lepao.companion.core.ArtifactKind,
        key: String,
        body: ByteArray,
    ): OssReceipt {
        calls.add(kind.name)
        pendingAtCall.add(journalProvider().uploadPending)
        if (kind.name == failOn) throw DeliveryUncertain("simulated PUT timeout")
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        return OssReceipt(kind.name, key, sha, body.size, "etag-${calls.size}", null)
    }
}

class FakeTrack : TrackProvider {

    override fun build(plan: PlannedRun): List<TrackPoint> = listOf(
        TrackPoint(0.0, 30.0, 120.0, 0.0),
        TrackPoint(plan.durationSec / 2.0, 30.001, 120.001, 1035.0),
        TrackPoint(plan.durationSec.toDouble(), 30.002, 120.002, 2070.0),
    )

    override fun position(track: List<TrackPoint>, elapsedSeconds: Double): TrackPoint {
        if (elapsedSeconds <= track.first().t) return track.first()
        if (elapsedSeconds >= track.last().t) return track.last()
        var low = 0
        var high = track.size - 1
        while (low < high - 1) {
            val middle = (low + high) / 2
            if (track[middle].t <= elapsedSeconds) low = middle else high = middle
        }
        val a = track[low]
        val b = track[high]
        val span = b.t - a.t
        val ratio = if (span <= 0.0) 0.0 else (elapsedSeconds - a.t) / span
        return TrackPoint(
            elapsedSeconds,
            a.lat + (b.lat - a.lat) * ratio,
            a.lng + (b.lng - a.lng) * ratio,
            a.arc + (b.arc - a.arc) * ratio,
        )
    }
}

class FakeArtifacts(private val gyroscope: Boolean = false) : ArtifactProvider {
    var builds: Int = 0
    override fun build(plan: PlannedRun, startEpoch: Long): Map<String, ArtifactBlob> {
        builds += 1
        return RunFixtures.artifactBlobs(gyroscope)
    }
}
