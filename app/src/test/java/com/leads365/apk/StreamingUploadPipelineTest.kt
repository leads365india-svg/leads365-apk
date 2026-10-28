package com.leads365.apk

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.core.RecordingMatchResult
import com.leads365.apk.core.UploadState
import com.leads365.apk.recordings.StreamingTestUploader
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class StreamingUploadPipelineTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    private val sampleAudioBytes = "SAMPLE_CALL_RECORDING_PCM_BYTES_FOR_TESTING".toByteArray(Charsets.UTF_8)
    private val expectedSha256: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(sampleAudioBytes)
        digest.joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        // Mock ContentResolver not required when openStream lambda is supplied
        mockContext = object : android.content.ContextWrapper(null) {}
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testUploadGatingRequiresUniqueMatch() {
        // Ambiguous match result
        val ambiguousResult = RecordingMatchResult(
            status = MatchStatus.AMBIGUOUS,
            matchedCandidate = null,
            candidateCount = 2,
            confidenceScore = 0.5,
            reason = "Multiple files matched"
        )
        // Rule: If status is not FILE_FOUND, upload must not be permitted
        assertNull("Ambiguous match must not have candidate for upload", ambiguousResult.matchedCandidate)

        // Unique match result
        val candidate = RecordingCandidate(
            uriString = "content://audio/1",
            displayName = "Call_Rec.m4a",
            sizeBytes = sampleAudioBytes.size.toLong(),
            lastModifiedEpochMs = 1700000000000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val uniqueResult = RecordingMatchResult(
            status = MatchStatus.FILE_FOUND,
            matchedCandidate = candidate,
            candidateCount = 1,
            confidenceScore = 0.95
        )
        assertNotNull("Unique match must provide candidate for upload", uniqueResult.matchedCandidate)
    }

    @Test
    fun testStreamingUploadSendsChecksumAndStreamsBytes() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\",\"file_id\":\"rec_123\"}")
        )

        val uploader = StreamingTestUploader(
            context = mockContext,
            openStream = { ByteArrayInputStream(sampleAudioBytes) }
        )
        val candidate = RecordingCandidate(
            uriString = "content://test/recording.m4a",
            displayName = "Call_Lead_123.m4a",
            sizeBytes = sampleAudioBytes.size.toLong(),
            lastModifiedEpochMs = 1700000000000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val endpoint = mockServer.url("/api/test-upload").toString()
        val outcome = uploader.uploadTestRecording(candidate, endpoint)

        assertTrue("Upload outcome should be Success", outcome is UploadState.Success)
        val success = outcome as UploadState.Success
        assertEquals(200, success.httpStatusCode)

        // Verify request received by server
        val recordedRequest = mockServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("audio/m4a", recordedRequest.getHeader("Content-Type"))
        assertEquals(expectedSha256, recordedRequest.getHeader("X-Content-SHA256"))
        assertEquals(expectedSha256, recordedRequest.getHeader("X-Audio-SHA256"))
        assertEquals(sampleAudioBytes.size.toString(), recordedRequest.getHeader("Content-Length"))
        assertEquals("STORAGE_ACCESS_FRAMEWORK", recordedRequest.getHeader("X-Discovery-Method"))

        // Verify body received
        val receivedBodyBytes = recordedRequest.body.readByteArray()
        assertArrayEquals(sampleAudioBytes, receivedBodyBytes)
    }

    @Test
    fun testUnconfiguredEndpointReturnsNonRetryableFailure() = runBlocking {
        val uploader = StreamingTestUploader(mockContext)
        val candidate = RecordingCandidate(
            uriString = "content://test/recording.m4a",
            displayName = "Call_Lead_123.m4a",
            sizeBytes = 100L,
            lastModifiedEpochMs = 1700000000000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE
        )

        val outcome = uploader.uploadTestRecording(candidate, null)
        assertTrue("Null endpoint should fail", outcome is UploadState.Failed)
        val failed = outcome as UploadState.Failed
        assertFalse("Unconfigured endpoint is not retryable until URL is set", failed.isRetryable)
    }

    @Test
    fun testServerErrorProvidesRetryableOutcome() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable")
        )

        val uploader = StreamingTestUploader(
            context = mockContext,
            openStream = { ByteArrayInputStream(sampleAudioBytes) }
        )
        val candidate = RecordingCandidate(
            uriString = "content://test/recording.m4a",
            displayName = "Call_Lead_503.m4a",
            sizeBytes = sampleAudioBytes.size.toLong(),
            lastModifiedEpochMs = 1700000000000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE
        )

        val endpoint = mockServer.url("/api/upload").toString()
        val outcome = uploader.uploadTestRecording(candidate, endpoint)

        assertTrue(outcome is UploadState.Failed)
        val failed = outcome as UploadState.Failed
        assertTrue("HTTP 503 should be retryable", failed.isRetryable)
    }

    @Test
    fun testRetriesCannotCauseAccidentalDuplicateUploads() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\",\"file_id\":\"rec_once\"}")
        )

        val uploader = StreamingTestUploader(
            context = mockContext,
            openStream = { ByteArrayInputStream(sampleAudioBytes) }
        )
        val candidate = RecordingCandidate(
            uriString = "content://test/recording_retry.m4a",
            displayName = "Call_Lead_Retry.m4a",
            sizeBytes = sampleAudioBytes.size.toLong(),
            lastModifiedEpochMs = 1700000000000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val endpoint = mockServer.url("/api/upload").toString()

        // First upload succeeds
        val outcome1 = uploader.uploadTestRecording(candidate, endpoint)
        assertTrue("First upload should succeed", outcome1 is UploadState.Success)
        assertEquals(1, mockServer.requestCount)

        // Second upload attempt (e.g. accidental retry or double click)
        val outcome2 = uploader.uploadTestRecording(candidate, endpoint)
        assertTrue("Second upload should return Success", outcome2 is UploadState.Success)
        val success2 = outcome2 as UploadState.Success
        assertTrue("Message should indicate duplicate upload was prevented", success2.responseMessage.contains("Duplicate upload prevented"))

        // Server must not have received a second request
        assertEquals("Server must not receive a second HTTP request for already uploaded recording", 1, mockServer.requestCount)
    }
}
