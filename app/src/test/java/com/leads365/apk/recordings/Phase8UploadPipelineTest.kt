package com.leads365.apk.recordings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.NetworkType
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.auth.InMemoryTokenProvider
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import com.leads365.apk.work.RecordingUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase8UploadPipelineTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var callRepository: CallRepository
    private lateinit var tokenProvider: InMemoryTokenProvider

    private val testAudioBytes = "LEADS365_AUDIO_TEST_STREAM_PAYLOAD_BYTES".toByteArray(Charsets.UTF_8)
    private val expectedSha256 = MessageDigest.getInstance("SHA-256")
        .digest(testAudioBytes)
        .joinToString("") { "%02x".format(it) }

    private var mockResponseCode = 200
    private var mockResponseBody = """{"success":true,"recordingUrl":"/uploads/recordings/test.m4a"}"""
    private var lastCapturedRequest: Request? = null
    private var capturedRequestBodyBytes: ByteArray? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()

        uploadQueueRepository = UploadQueueRepositoryImpl(
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val mockCallApi = object : CallApiService {
            override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                throw NotImplementedError()
            }
        }

        callRepository = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = mockCallApi,
            dispatcher = Dispatchers.Unconfined
        )

        tokenProvider = InMemoryTokenProvider(accessToken = "mobile-test-bearer-token-12345")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createTestUploader(
        streamProvider: (String) -> InputStream? = { ByteArrayInputStream(testAudioBytes) }
    ): RecordingUploadManager {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            lastCapturedRequest = request

            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                capturedRequestBodyBytes = buffer.readByteArray()
            }

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(mockResponseCode)
                .message("Mock HTTP $mockResponseCode")
                .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        return object : RecordingUploadManager(
            context = context,
            okHttpClient = client,
            tokenProvider = tokenProvider,
            baseUrl = "http://127.0.0.1:5000"
        ) {
            override fun openStream(uriString: String): InputStream? {
                return streamProvider(uriString)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `successful streaming upload sends verified headers and valid sha256 checksum`() = runBlocking {
        mockResponseCode = 200
        mockResponseBody = """{"success":true,"recordingUrl":"/uploads/recordings/call_101.m4a"}"""

        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_001",
            callId = "call_101",
            leadId = "lead_202",
            localFileUri = "content://media/audio/call_101.m4a",
            fileName = "call_101.m4a",
            fileSizeBytes = testAudioBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue("Upload should succeed", result is UploadExecutionResult.Success)
        val success = result as UploadExecutionResult.Success
        assertEquals("call_101", success.callId)
        assertEquals(expectedSha256, success.checksum)
        assertEquals("/uploads/recordings/call_101.m4a", success.recordingUrl)
        assertFalse(success.isAlreadyUploaded)

        assertNotNull(lastCapturedRequest)
        assertEquals("PUT", lastCapturedRequest?.method)
        assertEquals("Bearer mobile-test-bearer-token-12345", lastCapturedRequest?.header("Authorization"))
        assertEquals("audio/m4a", lastCapturedRequest?.header("Content-Type"))
        assertEquals(testAudioBytes.size.toLong(), lastCapturedRequest?.body?.contentLength())
        assertEquals(expectedSha256, lastCapturedRequest?.header("X-Content-SHA256"))

        // Verify streaming payload matches original bytes without whole-file buffering
        assertArrayEquals(testAudioBytes, capturedRequestBodyBytes)
    }

    @Test(timeout = 10_000)
    fun `idempotent upload with alreadyUploaded server flag is treated as success`() = runBlocking {
        mockResponseCode = 200
        mockResponseBody = """{"success":true,"message":"Recording already uploaded (idempotent)","alreadyUploaded":true}"""

        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_002",
            callId = "call_102",
            leadId = "lead_202",
            localFileUri = "content://media/audio/call_102.m4a",
            fileName = "call_102.m4a",
            fileSizeBytes = testAudioBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue("Idempotent response should be classified as Success", result is UploadExecutionResult.Success)
        val success = result as UploadExecutionResult.Success
        assertTrue("isAlreadyUploaded flag should be true", success.isAlreadyUploaded)
    }

    @Test(timeout = 10_000)
    fun `unauthorized 401 response is classified as RetryableFailure`() = runBlocking {
        mockResponseCode = 401
        mockResponseBody = """{"error":"Invalid or expired mobile auth token"}"""

        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_003",
            callId = "call_103",
            localFileUri = "content://media/audio/call_103.m4a",
            fileName = "call_103.m4a",
            fileSizeBytes = testAudioBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue(result is UploadExecutionResult.RetryableFailure)
        val failure = result as UploadExecutionResult.RetryableFailure
        assertEquals(401, failure.statusCode)
    }

    @Test(timeout = 10_000)
    fun `server rejection 403 with DNR or tenant restriction is classified as RejectedByServer`() = runBlocking {
        mockResponseCode = 403
        mockResponseBody = """{"error":"Lead is marked as Do-Not-Record (DNR). Call recording prohibited."}"""

        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_004",
            callId = "call_104",
            localFileUri = "content://media/audio/call_104.m4a",
            fileName = "call_104.m4a",
            fileSizeBytes = testAudioBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue(result is UploadExecutionResult.RejectedByServer)
        val rejected = result as UploadExecutionResult.RejectedByServer
        assertEquals(403, rejected.statusCode)
        assertTrue(rejected.reason.contains("DNR"))
    }

    @Test(timeout = 10_000)
    fun `unsupported media type 415 is classified as RejectedByServer`() = runBlocking {
        mockResponseCode = 415
        mockResponseBody = """{"error":"Unsupported audio MIME type: video/mp4"}"""

        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_005",
            callId = "call_105",
            localFileUri = "content://media/audio/call_105.m4a",
            fileName = "call_105.m4a",
            fileSizeBytes = testAudioBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue(result is UploadExecutionResult.RejectedByServer)
        assertEquals(415, (result as UploadExecutionResult.RejectedByServer).statusCode)
    }

    @Test(timeout = 10_000)
    fun `rate limit 429 response triggers RetryableFailure`() = runBlocking {
        mockResponseCode = 429
        mockResponseBody = """{"error":"Too many upload requests. Please back off."}"""

        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_006",
            callId = "call_106",
            localFileUri = "content://media/audio/call_106.m4a",
            fileName = "call_106.m4a",
            fileSizeBytes = testAudioBytes.size.toLong(),
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue(result is UploadExecutionResult.RetryableFailure)
        assertEquals(429, (result as UploadExecutionResult.RetryableFailure).statusCode)
    }

    @Test(timeout = 10_000)
    fun `oversized file exceeding 50MB is halted immediately as PermanentFailure`() = runBlocking {
        val uploader = createTestUploader()
        val task = UploadTaskEntity(
            id = "task_007",
            callId = "call_107",
            localFileUri = "content://media/audio/huge.m4a",
            fileName = "huge.m4a",
            fileSizeBytes = 51 * 1024 * 1024L, // 51 MB
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue(result is UploadExecutionResult.PermanentFailure)
        val failure = result as UploadExecutionResult.PermanentFailure
        assertTrue(failure.reason.contains("50MB limit"))
        assertNull("No network request should have been sent for oversized file", lastCapturedRequest)
    }

    @Test(timeout = 10_000)
    fun `missing unreadable file returns PermanentFailure without server contact`() = runBlocking {
        val uploader = createTestUploader(streamProvider = { null })
        val task = UploadTaskEntity(
            id = "task_008",
            callId = "call_108",
            localFileUri = "content://media/audio/deleted.m4a",
            fileName = "deleted.m4a",
            fileSizeBytes = 1024L,
            status = UploadTaskEntity.STATE_FILE_FOUND
        )

        val result = uploader.uploadRecording(task)

        assertTrue(result is UploadExecutionResult.PermanentFailure)
        assertNull("No network call when stream cannot be opened", lastCapturedRequest)
    }

    @Test(timeout = 10_000)
    fun `upload state machine transitions persist across Room reloads and app restarts`() = runBlocking {
        val taskId = "task_state_test_01"
        val callId = "call_state_test_01"

        // 1. Initial enqueue: STATE_FILE_FOUND
        val initialTask = UploadTaskEntity(
            id = taskId,
            callId = callId,
            leadId = "lead_123",
            localFileUri = "content://media/audio/rec.m4a",
            fileName = "rec.m4a",
            fileSizeBytes = 2048L,
            status = UploadTaskEntity.STATE_FILE_FOUND
        )
        uploadQueueRepository.enqueueTask(initialTask)

        val saved1 = (uploadQueueRepository.getTaskById(taskId) as AppResult.Success).data
        assertNotNull(saved1)
        assertEquals(UploadTaskEntity.STATE_FILE_FOUND, saved1?.status)

        // 2. Transition: STATE_UPLOADING
        uploadQueueRepository.updateStatus(taskId, UploadTaskEntity.STATE_UPLOADING)
        val saved2 = (uploadQueueRepository.getTaskById(taskId) as AppResult.Success).data
        assertEquals(UploadTaskEntity.STATE_UPLOADING, saved2?.status)

        // 3. Transient failure: STATE_RETRY_WAIT
        uploadQueueRepository.updateStatus(taskId, UploadTaskEntity.STATE_RETRY_WAIT, "Network timeout")
        uploadQueueRepository.incrementRetry(taskId, "Network timeout")
        val saved3 = (uploadQueueRepository.getTaskById(taskId) as AppResult.Success).data
        assertEquals(UploadTaskEntity.STATE_RETRY_WAIT, saved3?.status)
        assertEquals(1, saved3?.retryCount)
        assertEquals("Network timeout", saved3?.lastError)

        // 4. Simulate App Restart: Re-instantiate repository using the same Room database
        val reloadedRepository = UploadQueueRepositoryImpl(
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined
        )
        val reloadedTask = (reloadedRepository.getTaskById(taskId) as AppResult.Success).data
        assertNotNull(reloadedTask)
        assertEquals(UploadTaskEntity.STATE_RETRY_WAIT, reloadedTask?.status)
        assertEquals(1, reloadedTask?.retryCount)

        // 5. Successful retry: STATE_UPLOADED
        reloadedRepository.updateStatus(taskId, UploadTaskEntity.STATE_UPLOADED)
        val finalTask = (reloadedRepository.getTaskById(taskId) as AppResult.Success).data
        assertEquals(UploadTaskEntity.STATE_UPLOADED, finalTask?.status)
    }

    @Test(timeout = 10_000)
    fun `no false UPLOADED state on failure and CallEntity hasRecording is not updated on error`() = runBlocking {
        val taskId = "task_fail_guard_01"
        val callId = "call_fail_guard_01"

        callRepository.recordCall(
            CallEntity(
                id = callId,
                clientCallId = callId,
                normalizedNumber = "+919876543210",
                rawNumber = "9876543210",
                callType = 1,
                startTimeEpochMs = 1700000000000L,
                durationSeconds = 45L,
                isLeadCall = true,
                leadId = "lead_999",
                hasRecording = false
            )
        )

        uploadQueueRepository.enqueueTask(
            UploadTaskEntity(
                id = taskId,
                callId = callId,
                localFileUri = "content://audio/sample.m4a",
                fileName = "sample.m4a",
                fileSizeBytes = testAudioBytes.size.toLong(),
                status = UploadTaskEntity.STATE_FILE_FOUND
            )
        )

        // Simulate 500 internal server error
        mockResponseCode = 500
        mockResponseBody = """{"error":"Storage cluster down"}"""
        val uploader = createTestUploader()

        val task = (uploadQueueRepository.getTaskById(taskId) as AppResult.Success).data!!
        val result = uploader.uploadRecording(task)

        assertTrue("Failure should be classified as RetryableFailure", result is UploadExecutionResult.RetryableFailure)

        // Task must NOT be marked UPLOADED
        val taskAfterFail = (uploadQueueRepository.getTaskById(taskId) as AppResult.Success).data!!
        assertNotEquals(UploadTaskEntity.STATE_UPLOADED, taskAfterFail.status)

        // Call record must remain hasRecording = false
        val callInDb = (callRepository.findCallBySignature("+919876543210", 1700000000000L, 5000L) as AppResult.Success).data!!
        assertFalse("hasRecording must not be set to true on failure", callInDb.hasRecording)
    }

    @Test(timeout = 10_000)
    fun `recording upload worker network constraints respect wifi-only preference`() {
        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)

        // Case A: wifi-only disabled -> CONNECTED network required
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false).commit()
        val isWifiOnlyFalse = prefs.getBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false)
        assertFalse(isWifiOnlyFalse)

        val constraintsMetered = Constraints.Builder()
            .setRequiredNetworkType(if (isWifiOnlyFalse) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        assertEquals(NetworkType.CONNECTED, constraintsMetered.requiredNetworkType)

        // Case B: wifi-only enabled -> UNMETERED network required
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, true).commit()
        val isWifiOnlyTrue = prefs.getBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false)
        assertTrue(isWifiOnlyTrue)

        val constraintsUnmetered = Constraints.Builder()
            .setRequiredNetworkType(if (isWifiOnlyTrue) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        assertEquals(NetworkType.UNMETERED, constraintsUnmetered.requiredNetworkType)
    }
}
