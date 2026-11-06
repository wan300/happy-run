package com.lepao.companion.run

import com.lepao.companion.core.ArtifactKinds
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.RunStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State machine tests: normal path, no-resend semantics and every recovery-matrix branch.
 *
 * The engine is driven by fakes only, so this whole class runs as a plain JVM test.
 */
class RunEngineFsmTest {

    private class Harness(initialStage: RunStage, gyroscope: Boolean = false) {
        val journal = FakeJournal(initialStage)
        val ticker = FakeTicker()
        val plan = RunFixtures.plan(gyroscope = gyroscope)
        val transport = FakeTransport(ticker, { journal })
        val uploader = FakeUploader { journal }
        val artifacts = FakeArtifacts(gyroscope)
        val engine = RunEngine(
            journal = journal,
            plan = plan,
            rule = RunFixtures.RULE,
            identity = RunFixtures.identity(),
            session = RunFixtures.session(gyroscope),
            deps = RunDeps(
                clock = ticker,
                transport = transport,
                uploader = uploader,
                track = FakeTrack(),
                artifacts = artifacts,
            ),
        )

        fun run(): RunStage = engine.execute()

        fun manifest(): FinishManifest = FinishManifestBuilder.build(
            sessionId = RunFixtures.SESSION_ID,
            plan = plan,
            startEpoch = RunFixtures.START_EPOCH,
            endEpoch = RunFixtures.START_EPOCH + RunFixtures.DURATION + 2,
            ip = "192.168.1.2",
        )

        fun objectKeys(): Map<String, String> = mapOf(
            ArtifactKinds.SCREENSHOT.name to
                "Public/Upload/pic/run_photo_screenshotImage/2026-11-04/41/stub.png",
            ArtifactKinds.ROUTE.name to
                "Public/Upload/file/run_record/2026-11-04/992/stub.txt",
        )

        fun callsOf(endpoint: String): Int = transport.countOf(endpoint)
    }

    @Test
    fun normalPathRunsHeartbeatsUploadsStopSensorAndVerify() {
        val harness = Harness(RunStage.RUNNING)
        val stage = harness.run()

        assertEquals(RunStage.VERIFIED, stage)
        assertEquals(RunStage.VERIFIED, harness.journal.stage)

        val locationElapsed = harness.transport.elapsedAtCall.filterIndexed { index, _ ->
            harness.transport.calls[index].endpoint == Endpoints.LOCATION
        }
        // Reference record 259921: 60/120/240/480 s, i.e. the threshold doubles, not a fixed 60 s.
        assertEquals(listOf(60L, 120L, 240L, 480L), locationElapsed)
        assertEquals(0, harness.callsOf(Endpoints.GYROSCOPE))

        // Every location write saw LOCATION_PENDING persisted, every stop write STOP_PENDING.
        harness.transport.calls.forEachIndexed { index, request ->
            when (request.endpoint) {
                Endpoints.LOCATION ->
                    assertEquals(RunStage.LOCATION_PENDING, harness.transport.stageAtCall[index])
                Endpoints.STOP ->
                    assertEquals(RunStage.STOP_PENDING, harness.transport.stageAtCall[index])
                else -> Unit
            }
        }
        // upload_pending is persisted before each PUT.
        assertEquals(listOf("screenshot", "route"), harness.uploader.calls)
        assertEquals(listOf("screenshot", "route"), harness.uploader.pendingAtCall)
        assertNull(harness.journal.uploadPending)
        assertEquals(setOf("screenshot", "route"), harness.journal.uploads().keys)
        assertEquals(RunFixtures.RECORD_ID, harness.journal.recordId)
        assertTrue(harness.journal.stopChecks().values.all { it })
        assertTrue(harness.journal.verification().values.all { it })
        assertEquals(1, harness.callsOf(Endpoints.RECORD_DETAIL))
    }

    @Test
    fun gyroscopeConfigurationUploadsSensorAndRegistersIt() {
        val harness = Harness(RunStage.RUNNING, gyroscope = true)
        val stage = harness.run()

        assertEquals(RunStage.VERIFIED, stage)
        assertEquals(listOf("screenshot", "route", "sensor"), harness.uploader.calls)
        assertEquals(1, harness.callsOf(Endpoints.GYROSCOPE))
        assertTrue(harness.journal.stopChecks().values.all { it })
    }

    @Test
    fun stopTimeoutBecomesUncertainAndIsNeverResent() {
        val harness = Harness(RunStage.RUNNING)
        harness.transport.failOn = Endpoints.STOP
        val stage = harness.run()

        assertEquals(RunStage.UNCERTAIN, stage)
        assertEquals(1, harness.callsOf(Endpoints.STOP))
        assertEquals("from=stop_pending", harness.journal.lastNote)
        assertEquals(0, harness.callsOf(Endpoints.RECORD_DETAIL))
        assertEquals(0, harness.callsOf(Endpoints.GYROSCOPE))

        val callsAfterStop = harness.transport.calls.size
        val again = harness.run()
        assertEquals(RunStage.UNCERTAIN, again)
        assertEquals(callsAfterStop, harness.transport.calls.size)
    }

    @Test
    fun putTimeoutKeepsUploadPendingAndNeverRepeats() {
        val harness = Harness(RunStage.RUNNING)
        harness.uploader.failOn = ArtifactKinds.SCREENSHOT.name
        val stage = harness.run()

        assertEquals(RunStage.UNCERTAIN, stage)
        assertEquals(ArtifactKinds.SCREENSHOT.name, harness.journal.uploadPending)
        assertEquals(0, harness.callsOf(Endpoints.STOP))
        assertEquals(1, harness.uploader.calls.size)

        val callsBefore = harness.transport.calls.size
        harness.run()
        assertEquals(callsBefore, harness.transport.calls.size)
        assertEquals(ArtifactKinds.SCREENSHOT.name, harness.journal.uploadPending)
    }

    @Test
    fun locationTimeoutBecomesUncertainWithoutRepeating() {
        val harness = Harness(RunStage.RUNNING)
        harness.transport.failOn = Endpoints.LOCATION
        val stage = harness.run()

        assertEquals(RunStage.UNCERTAIN, stage)
        assertEquals(1, harness.callsOf(Endpoints.LOCATION))
        assertEquals(0, harness.callsOf(Endpoints.STOP))
    }

    @Test
    fun nonEmptyOfflinePointsStopsTheRun() {
        val harness = Harness(RunStage.RUNNING)
        harness.transport.offlinePointsForLocation = listOf("cached-point")
        val stage = harness.run()

        assertEquals(RunStage.UNCERTAIN, stage)
        assertEquals(1, harness.callsOf(Endpoints.LOCATION))
    }

    @Test
    fun clockDriftOnResumeBecomesUncertain() {
        val harness = Harness(RunStage.RUNNING)
        harness.ticker.jumpToSeconds(600)
        harness.ticker.shiftEpochSeconds(300)
        val stage = harness.run()

        assertEquals(RunStage.UNCERTAIN, stage)
        assertEquals(0, harness.transport.calls.size)
    }

    // -------------------------------------------------------------- recovery matrix

    @Test
    fun preparedAndStartPendingBecomeUncertainWithoutDispatch() {
        for (stage in listOf(RunStage.PREPARED, RunStage.START_PENDING)) {
            val harness = Harness(stage)
            assertEquals(RunStage.UNCERTAIN, harness.run())
            assertEquals(0, harness.transport.calls.size)
            assertEquals(0, harness.uploader.calls.size)
        }
    }

    @Test
    fun locationPendingAndStopPendingAndSensorPendingBecomeUncertain() {
        for (stage in listOf(
            RunStage.LOCATION_PENDING,
            RunStage.STOP_PENDING,
            RunStage.SENSOR_PENDING,
        )) {
            val harness = Harness(stage)
            assertEquals(RunStage.UNCERTAIN, harness.run())
            assertEquals(0, harness.transport.calls.size)
        }
    }

    @Test
    fun uploadingWithPendingUploadBecomesUncertain() {
        val harness = Harness(RunStage.UPLOADING)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())
        harness.journal.uploadPending = ArtifactKinds.ROUTE.name

        assertEquals(RunStage.UNCERTAIN, harness.run())
        assertEquals(0, harness.transport.calls.size)
        assertEquals(ArtifactKinds.ROUTE.name, harness.journal.uploadPending)
    }

    @Test
    fun uploadingWithoutPendingResumesRemainingUploads() {
        val harness = Harness(RunStage.UPLOADING)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())

        assertEquals(RunStage.VERIFIED, harness.run())
        assertEquals(listOf("screenshot", "route"), harness.uploader.calls)
        assertEquals(1, harness.callsOf(Endpoints.STOP))
        assertEquals(1, harness.callsOf(Endpoints.RECORD_DETAIL))
    }

    @Test
    fun uploadingResumeSkipsArtifactsAlreadyUploaded() {
        val harness = Harness(RunStage.UPLOADING)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())
        val blob = RunFixtures.artifactBlobs()[ArtifactKinds.SCREENSHOT.name]!!
        harness.journal.putUpload(
            com.lepao.companion.core.OssReceipt(
                ArtifactKinds.SCREENSHOT.name,
                harness.objectKeys()[ArtifactKinds.SCREENSHOT.name]!!,
                blob.sha256,
                blob.body.size,
                "etag-existing",
                null,
            ),
        )

        assertEquals(RunStage.VERIFIED, harness.run())
        assertEquals(listOf("route"), harness.uploader.calls)
    }

    @Test
    fun stoppedRunResumesSensorlessAndVerifies() {
        val harness = Harness(RunStage.STOP_ACKNOWLEDGED)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())
        harness.journal.recordId = RunFixtures.RECORD_ID
        harness.journal.setStopResult(
            mapOf("uid_matches" to true, "start_time_matches" to true),
            emptyMap(),
        )

        assertEquals(RunStage.VERIFIED, harness.run())
        assertEquals(0, harness.uploader.calls.size)
        assertEquals(0, harness.callsOf(Endpoints.STOP))
        assertEquals(1, harness.callsOf(Endpoints.RECORD_DETAIL))
    }

    @Test
    fun stoppedRunWithFailedChecksDoesNotContinueOnRecovery() {
        val harness = Harness(RunStage.STOP_ACKNOWLEDGED)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())
        harness.journal.recordId = RunFixtures.RECORD_ID
        harness.journal.setStopResult(
            mapOf("uid_matches" to true, "distance_matches" to false),
            emptyMap(),
        )

        assertEquals(RunStage.STOP_ACKNOWLEDGED, harness.run())
        assertEquals(0, harness.transport.calls.size)
        assertEquals(0, harness.uploader.calls.size)
    }

    @Test
    fun stoppedRunWithoutPersistedChecksDoesNotContinueOnRecovery() {
        val harness = Harness(RunStage.STOP_ACKNOWLEDGED)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())
        harness.journal.recordId = RunFixtures.RECORD_ID

        assertEquals(RunStage.STOP_ACKNOWLEDGED, harness.run())
        assertEquals(0, harness.transport.calls.size)
    }

    @Test
    fun transientStsFailureIsRetriedWithinTheCredentialCallOnly() {
        val harness = Harness(RunStage.RUNNING)
        harness.transport.failOn = Endpoints.OSS_STS
        harness.transport.failTimes = 2

        assertEquals(RunStage.VERIFIED, harness.run())
        assertEquals(4, harness.callsOf(Endpoints.OSS_STS))
        assertEquals(1, harness.callsOf(Endpoints.STOP))
        assertNull(harness.journal.uploadPending)
    }

    @Test
    fun persistentStsFailureStopsInUploadingWithoutDispatch() {
        val harness = Harness(RunStage.RUNNING)
        harness.transport.failOn = Endpoints.OSS_STS

        assertEquals(RunStage.UPLOADING, harness.run())
        assertEquals(3, harness.callsOf(Endpoints.OSS_STS))
        assertEquals(0, harness.callsOf(Endpoints.STOP))
        assertNull(harness.journal.uploadPending)
    }

    @Test
    fun submittedOnlyPerformsReadOnlyVerification() {
        val harness = Harness(RunStage.SUBMITTED)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(harness.objectKeys())
        harness.journal.recordId = RunFixtures.RECORD_ID

        assertEquals(RunStage.VERIFIED, harness.run())
        assertEquals(1, harness.transport.calls.size)
        assertEquals(Endpoints.RECORD_DETAIL, harness.transport.calls.first().endpoint)
        assertEquals(0, harness.uploader.calls.size)
    }

    @Test
    fun verifiedAndFailedAreTerminal() {
        for (stage in listOf(RunStage.VERIFIED, RunStage.FAILED)) {
            val harness = Harness(stage)
            assertEquals(stage, harness.run())
            assertEquals(0, harness.transport.calls.size)
            assertEquals(0, harness.uploader.calls.size)
        }
    }

    @Test
    fun verificationFailureKeepsSubmittedAndRecordedDetail() {
        val harness = Harness(RunStage.SUBMITTED)
        harness.journal.setManifest(harness.manifest())
        harness.journal.putObjectKeys(
            harness.objectKeys() + (ArtifactKinds.ROUTE.name to "Public/Upload/file/run_record/other.txt"),
        )
        // The server echoes the originally uploaded key; the journal now holds a different key, so
        // the route-key acceptance check must fail even though every other field matches.
        harness.transport.detailRouteKey = harness.objectKeys()[ArtifactKinds.ROUTE.name]
        harness.journal.recordId = RunFixtures.RECORD_ID

        val stage = harness.run()
        assertEquals(RunStage.SUBMITTED, stage)
        assertTrue(harness.journal.verification().isNotEmpty())
        assertEquals(false, harness.journal.verification()["route_key_matches"])
        assertEquals(true, harness.journal.verification()["record_valid"])
    }
}
