package com.leads365.apk.recordings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.calls.CallStateReceiver
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.auth.InMemoryTokenProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31]) // Android 12 (vivo V2111 target OS)
class VivoRecordingDiscoveryFixTest {

    private lateinit var context: Context
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var matcher: TargetedRecordingMatcher

    private val vivoCall = CallEvent(
        id = "vivo_call_01",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        startTimeEpochMs = 1700000030000L, // Call connected after 30s ringing
        endTimeEpochMs = 1700000090000L,   // 60s call duration
        durationSeconds = 60L,
        direction = CallDirection.OUTGOING
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        normalizer = PhoneNumberNormalizer("IN")
        matcher = TargetedRecordingMatcher(normalizer, minAcceptanceScore = 0.50, ambiguityDeltaThreshold = 0.15)
    }

    @Test
    fun testVivoDialerRecordingWithRingingOffsetMatchesSuccessfully() {
        // Vivo native dialer stamps recording file at dialing start (30s before call connected)
        val dialingStartTime = vivoCall.startTimeEpochMs - 25_000L
        val vivoCandidate = RecordingCandidate(
            uriString = "content://media/external/audio/media/98765",
            displayName = "9876543210_20261026164500.m4a",
            sizeBytes = 240_000L,
            lastModifiedEpochMs = dialingStartTime,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE
        )

        val result = matcher.match(listOf(vivoCandidate), vivoCall)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals("9876543210_20261026164500.m4a", result.matchedCandidate?.displayName)
        assertTrue("Score must reach minimum threshold", result.confidenceScore >= 0.50)
    }

    @Test
    fun testVivoRecordingWithoutDurationMetadataMatches() {
        // In SAF, DocumentFile length is known (~240KB) but audio duration metadata may be null
        val dialingStartTime = vivoCall.startTimeEpochMs - 15_000L
        val safCandidate = RecordingCandidate(
            uriString = "content://com.android.externalstorage.documents/document/primary%3ARecordings%2FCall%2Frec.m4a",
            displayName = "Call_Recording_+919876543210.m4a",
            sizeBytes = 250_000L,
            lastModifiedEpochMs = dialingStartTime,
            mimeType = "audio/m4a",
            durationSeconds = null, // null duration from SAF
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(safCandidate), vivoCall)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertTrue("Candidate without duration should match above 0.50", result.confidenceScore >= 0.50)
    }

    @Test
    fun testVivoFilenameHeuristicsRecognizesVivoKeywords() {
        // Vivo filename with "vivo" or "record" keyword and matching phone digits
        val candidate = RecordingCandidate(
            uriString = "content://recordings/999",
            displayName = "vivo_call_record_9876543210.m4a",
            sizeBytes = 300_000L,
            lastModifiedEpochMs = vivoCall.endTimeEpochMs + 5_000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate), vivoCall)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertTrue(result.confidenceScore >= 0.70)
    }

    @Test
    fun testCallStateReceiverMultiListenerRegistration() {
        var listener1Notified = false
        var listener2Notified = false

        val l1: (CallEvent) -> Unit = { listener1Notified = true }
        val l2: (CallEvent) -> Unit = { listener2Notified = true }

        CallStateReceiver.addCallDetectedListener(l1)
        CallStateReceiver.addCallDetectedListener(l2)

        CallStateReceiver.notifyCallDetected(vivoCall)

        assertTrue("Listener 1 should be notified", listener1Notified)
        assertTrue("Listener 2 should be notified", listener2Notified)

        CallStateReceiver.removeCallDetectedListener(l1)
        CallStateReceiver.removeCallDetectedListener(l2)
    }

    @Test
    fun testPendingCallManagerResolvesSavedWizardTestLead() {
        val prefs = context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE)
        val testLeadsJson = """[{"id":"lead_vivo_test","name":"Vivo Customer","rawNumber":"+91 98765 43210","normalizedNumber":"+919876543210","isDoNotRecord":false}]"""
        prefs.edit().putString("key_saved_test_leads_json", testLeadsJson).apply()

        val pendingCallManager = PendingCallManager(context, normalizer)
        val resolved = pendingCallManager.findTestLead("+91 98765 43210")

        assertNotNull("Wizard test lead must be resolved", resolved)
        assertEquals("lead_vivo_test", resolved?.id)
        assertEquals("+919876543210", resolved?.normalizedNumber)
        assertFalse("DNR must be false", resolved?.isDoNotRecord ?: true)
    }

    @Test
    fun testEmptyFolderHandling_ReturnsNoFileFoundAndZeroCandidates() {
        val emptyCandidates = emptyList<RecordingCandidate>()
        val result = matcher.match(emptyCandidates, vivoCall)

        assertEquals("Empty candidate list must yield NO_FILE_FOUND", MatchStatus.NO_FILE_FOUND, result.status)
        assertNull("Matched candidate must be null", result.matchedCandidate)
        assertEquals("Candidate count must be 0", 0, result.candidateCount)
        assertEquals("Score must be 0.0 for empty folder", 0.0, result.confidenceScore, 0.001)
        assertTrue("Reason must explain no recording files detected", result.reason.contains("No recording files detected"))
    }

    @Test
    fun testRealVersusSimulatedResults_NeverFabricatesAudioCandidate() {
        // Unrelated audio candidate from 2 hours earlier
        val oldCandidate = RecordingCandidate(
            uriString = "content://recordings/old_call.m4a",
            displayName = "Call_Recording_Old.m4a",
            sizeBytes = 100_000L,
            lastModifiedEpochMs = vivoCall.startTimeEpochMs - (2 * 3600 * 1000L),
            mimeType = "audio/m4a",
            durationSeconds = 30L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(oldCandidate), vivoCall)
        // Must reject unrelated candidate; must never fabricate a match
        assertEquals("Unrelated candidate must not match", MatchStatus.NO_FILE_FOUND, result.status)
        assertNull("Audio file must not be fabricated", result.matchedCandidate)
    }

    @Test
    fun testDnrLeadBlocking_PreservesPrivacySafeguard() {
        val prefs = context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE)
        val dnrLeadJson = """[{"id":"lead_dnr_01","name":"DNR Customer","rawNumber":"+91 91234 56789","normalizedNumber":"+919123456789","isDoNotRecord":true}]"""
        prefs.edit().putString("key_saved_test_leads_json", dnrLeadJson).apply()

        val pendingCallManager = PendingCallManager(context, normalizer)
        val resolved = pendingCallManager.findTestLead("+91 91234 56789")

        assertNotNull("DNR lead must be resolved", resolved)
        assertEquals("lead_dnr_01", resolved?.id)
        assertTrue("Do-Not-Record flag must be strictly preserved as TRUE", resolved?.isDoNotRecord == true)
    }

    @Test
    fun testUnauthenticatedUploadInLocalRecordingTestMode_RejectsWithoutNetworkCall() = runBlocking {
        val unauthenticatedTokenProvider = InMemoryTokenProvider(accessToken = null)
        val dummyBytes = "LEADS365_SAMPLE_AUDIO".toByteArray(Charsets.UTF_8)
        val uploader = object : RecordingUploadManager(
            context = context,
            okHttpClient = OkHttpClient.Builder().build(),
            tokenProvider = unauthenticatedTokenProvider,
            baseUrl = "http://127.0.0.1:8080/"
        ) {
            override fun openStream(uriString: String): InputStream {
                return ByteArrayInputStream(dummyBytes)
            }
        }

        val uploadTask = UploadTaskEntity(
            id = "task_local_test_01",
            callId = "call_local_01",
            localFileUri = "content://recordings/sample.m4a",
            fileName = "sample.m4a",
            fileSizeBytes = dummyBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(uploadTask)

        assertTrue("Upload must fail permanently when unauthenticated", result is UploadExecutionResult.PermanentFailure)
        val failure = result as UploadExecutionResult.PermanentFailure
        assertEquals(401, failure.statusCode)
        assertTrue(
            "Failure message must explain Local Recording Test Mode status",
            failure.reason.contains("Local Recording Test Mode — server upload unavailable until login")
        )
    }

    @Test
    fun testInaccessibleOrUnreadableCandidateCannotProduceFileFound() {
        val inaccessibleCandidate = RecordingCandidate(
            uriString = "content://media/external/audio/media/deleted_99",
            displayName = "9876543210_deleted.m4a",
            sizeBytes = 300_000L,
            lastModifiedEpochMs = vivoCall.startTimeEpochMs,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE,
            relativePath = "Recordings/Call/",
            isReadable = false,
            unreadableReason = "File does not exist on disk (stale MediaStore entry)"
        )

        val result = matcher.match(listOf(inaccessibleCandidate), vivoCall)
        // Must NEVER return FILE_FOUND for unreadable file
        assertEquals("Inaccessible candidate must return INACCESSIBLE", MatchStatus.INACCESSIBLE, result.status)
        assertNotNull("Matched candidate metadata retained for diagnostics", result.matchedCandidate)
        assertTrue("Reason must state candidate cannot be opened or read", result.reason.contains("cannot be opened or read"))
    }

    @Test
    fun testZeroByteCandidateCannotProduceFileFound() {
        val zeroByteCandidate = RecordingCandidate(
            uriString = "content://media/external/audio/media/zerobyte_1",
            displayName = "9876543210_empty.m4a",
            sizeBytes = 0L,
            lastModifiedEpochMs = vivoCall.startTimeEpochMs,
            mimeType = "audio/m4a",
            durationSeconds = 0L,
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE,
            relativePath = "Recordings/Call/",
            isReadable = false,
            unreadableReason = "File size reported as 0 bytes"
        )

        val result = matcher.match(listOf(zeroByteCandidate), vivoCall)
        assertEquals("Zero-byte candidate must return INACCESSIBLE", MatchStatus.INACCESSIBLE, result.status)
    }

    @Test
    fun testMediaStoreCandidatePreservesSourceMethodAndRelativePath() {
        val mediaStoreCandidate = RecordingCandidate(
            uriString = "content://media/external/audio/media/114604",
            displayName = "63***25 2026-10-27 11-46-04.m4a",
            sizeBytes = 342_016L,
            lastModifiedEpochMs = vivoCall.startTimeEpochMs,
            mimeType = "audio/mp4",
            durationSeconds = 41L,
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE,
            relativePath = "Recordings/Call/",
            isReadable = true
        )

        val result = matcher.match(listOf(mediaStoreCandidate), vivoCall)
        assertEquals("Readable MediaStore candidate must be matched", MatchStatus.FILE_FOUND, result.status)
        val matched = result.matchedCandidate
        assertNotNull(matched)
        assertEquals(RecordingDiscoveryMethod.MEDIA_STORE, matched?.sourceMethod)
        assertEquals("Recordings/Call/", matched?.relativePath)
        assertTrue(matched?.isReadable == true)
    }

    @Test
    fun testStreamingTestUploaderShowsInformationalTestModeMessageWhenEndpointNull() = runBlocking {
        val dummyBytes = "LEADS365_SAMPLE_STREAM".toByteArray(Charsets.UTF_8)
        val uploader = StreamingTestUploader(
            context = context,
            openStream = { ByteArrayInputStream(dummyBytes) }
        )
        val candidate = RecordingCandidate(
            uriString = "content://recordings/sample.m4a",
            displayName = "test.m4a",
            sizeBytes = dummyBytes.size.toLong(),
            lastModifiedEpochMs = System.currentTimeMillis(),
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE,
            isReadable = true
        )

        val outcome = uploader.uploadTestRecording(candidate, null)
        assertTrue("Null endpoint must produce UploadState.Failed", outcome is com.leads365.apk.core.UploadState.Failed)
        val failed = outcome as com.leads365.apk.core.UploadState.Failed
        assertEquals(
            "Recording matched locally. Server upload is unavailable in Local Recording Test Mode; sign in to upload.",
            failed.errorMessage
        )
        assertFalse("Unconfigured endpoint should not be marked retryable", failed.isRetryable)
    }
}


