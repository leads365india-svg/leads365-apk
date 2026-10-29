package com.leads365.apk.ui.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.calls.CallProcessingManager
import com.leads365.apk.calls.CallProcessingResult
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.models.CallDto
import com.leads365.apk.network.models.CallListResponse
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import com.leads365.apk.network.models.LeadSyncData
import com.leads365.apk.network.models.LeadSyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UploadSelectionAndStartupOptimizationTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createFakeUploadQueueRepo(initialTasks: List<UploadTaskEntity> = emptyList()): UploadQueueRepository {
        val flow = MutableStateFlow(initialTasks)
        return object : UploadQueueRepository {
            override fun getAllTasksFlow(): Flow<List<UploadTaskEntity>> = flow
            override suspend fun enqueueTask(task: UploadTaskEntity): AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun getPendingTasks(): AppResult<List<UploadTaskEntity>> = AppResult.Success(flow.value.filter { it.status == UploadTaskEntity.STATUS_PENDING })
            override suspend fun getTasksByStatus(status: String): AppResult<List<UploadTaskEntity>> = AppResult.Success(flow.value.filter { it.status == status })
            override suspend fun getTaskById(id: String): AppResult<UploadTaskEntity?> = AppResult.Success(flow.value.firstOrNull { it.id == id })
            override suspend fun getTaskByCallId(callId: String): AppResult<UploadTaskEntity?> = AppResult.Success(flow.value.firstOrNull { it.callId == callId })
            override suspend fun updateStatus(id: String, status: String, error: String?): AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun incrementRetry(id: String, error: String): AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun deleteTaskById(id: String): AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun clearCompleted(): AppResult<Unit> = AppResult.Success(Unit)
        }
    }

    @Test
    fun `test upload eligibility classifies pending and retryable tasks as eligible`() {
        val fakeRepo = createFakeUploadQueueRepo()
        val viewModel = UploadStatusViewModel(fakeRepo, context)

        val pendingTask = UploadTaskEntity(
            id = "t1", callId = "c1", localFileUri = "uri://test1", fileName = "test1.m4a",
            fileSizeBytes = 100L, status = UploadTaskEntity.STATUS_PENDING
        )
        val fileFoundTask = UploadTaskEntity(
            id = "t2", callId = "c2", localFileUri = "uri://test2", fileName = "test2.m4a",
            fileSizeBytes = 100L, status = UploadTaskEntity.STATE_FILE_FOUND
        )
        val retryTask = UploadTaskEntity(
            id = "t3", callId = "c3", localFileUri = "uri://test3", fileName = "test3.m4a",
            fileSizeBytes = 100L, status = UploadTaskEntity.STATE_RETRY_WAIT
        )
        val failedTask = UploadTaskEntity(
            id = "t4", callId = "c4", localFileUri = "uri://test4", fileName = "test4.m4a",
            fileSizeBytes = 100L, status = UploadTaskEntity.STATUS_FAILED
        )
        val uploadedTask = UploadTaskEntity(
            id = "t5", callId = "c5", localFileUri = "uri://test5", fileName = "test5.m4a",
            fileSizeBytes = 100L, status = UploadTaskEntity.STATE_UPLOADED
        )
        val uploadingTask = UploadTaskEntity(
            id = "t6", callId = "c6", localFileUri = "uri://test6", fileName = "test6.m4a",
            fileSizeBytes = 100L, status = UploadTaskEntity.STATE_UPLOADING
        )

        assertTrue(viewModel.isTaskEligibleForUpload(pendingTask))
        assertTrue(viewModel.isTaskEligibleForUpload(fileFoundTask))
        assertTrue(viewModel.isTaskEligibleForUpload(retryTask))
        assertTrue(viewModel.isTaskEligibleForUpload(failedTask))
        assertFalse(viewModel.isTaskEligibleForUpload(uploadedTask))
        assertFalse(viewModel.isTaskEligibleForUpload(uploadingTask))
    }

    @Test
    fun `test selection toggle and clear actions update selectedTaskIds correctly`() {
        val fakeRepo = createFakeUploadQueueRepo()
        val viewModel = UploadStatusViewModel(fakeRepo, context)

        assertTrue(viewModel.selectedTaskIds.value.isEmpty())

        viewModel.toggleTaskSelection("task-1")
        assertEquals(setOf("task-1"), viewModel.selectedTaskIds.value)

        viewModel.toggleTaskSelection("task-2")
        assertEquals(setOf("task-1", "task-2"), viewModel.selectedTaskIds.value)

        // Toggling again deselects
        viewModel.toggleTaskSelection("task-1")
        assertEquals(setOf("task-2"), viewModel.selectedTaskIds.value)

        viewModel.clearSelection()
        assertTrue(viewModel.selectedTaskIds.value.isEmpty())
    }

    @Test
    fun `test CallRepository syncCallsFromBackend persists remote calls into Room`() = runBlocking {
        val mockApi = object : CallApiService {
            override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                return CallReportResponse(success = true)
            }

            override suspend fun getCalls(): CallListResponse {
                return CallListResponse(
                    success = true,
                    data = listOf(
                        CallDto(
                            id = "server-call-1",
                            clientCallId = "client-call-1",
                            phoneNumber = "+919876543210",
                            callDirection = "OUTGOING",
                            startTimeMs = 1700000000000L,
                            durationSeconds = 45L,
                            leadId = "lead-123",
                            callOutcome = "Interested",
                            notes = "Customer ready for demo",
                            simId = "SIM1"
                        ),
                        CallDto(
                            id = "server-call-2",
                            clientCallId = "client-call-2",
                            phoneNumber = "+919876543211",
                            callDirection = "INCOMING",
                            startTimeMs = 1700000060000L,
                            durationSeconds = 120L,
                            leadId = "lead-456",
                            callOutcome = "Follow Up",
                            notes = "Callback requested",
                            simId = "SIM2"
                        )
                    )
                )
            }
        }

        val callRepo = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = mockApi,
            dispatcher = Dispatchers.Unconfined
        )

        val result = callRepo.syncCallsFromBackend()
        assertTrue(result is AppResult.Success)
        assertEquals(2, (result as AppResult.Success).data)

        // Verify calls are stored in Room with SYNCED status
        val storedCall1 = database.callDao().getByClientCallId("client-call-1")
        assertNotNull(storedCall1)
        assertEquals("+919876543210", storedCall1?.normalizedNumber)
        assertEquals("lead-123", storedCall1?.leadId)
        assertEquals("SYNCED", storedCall1?.syncStatus)
        assertEquals("Interested", storedCall1?.callOutcome)

        val storedCall2 = database.callDao().getByClientCallId("client-call-2")
        assertNotNull(storedCall2)
        assertEquals("+919876543211", storedCall2?.normalizedNumber)
        assertEquals("lead-456", storedCall2?.leadId)
        assertEquals("SYNCED", storedCall2?.syncStatus)

        // Second sync should ignore duplicates
        val secondResult = callRepo.syncCallsFromBackend()
        assertTrue(secondResult is AppResult.Success)
        assertEquals(0, (secondResult as AppResult.Success).data)
    }

    @Test
    fun `test CallProcessingManager matches call to CRM lead via last 10 digits of rawNumber`() = runBlocking {
        val normalizer = PhoneNumberNormalizer("IN")

        val mockCallApi = object : CallApiService {
            override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                return CallReportResponse(success = true)
            }
        }
        val mockLeadApi = object : LeadApiService {
            override suspend fun syncLeads(sinceTimestampEpochMs: Long?, limit: Int): LeadSyncResponse {
                return LeadSyncResponse(success = true, data = LeadSyncData(leads = emptyList()))
            }
        }

        val callRepo = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = mockCallApi,
            dispatcher = Dispatchers.Unconfined
        )
        val leadRepo = LeadRepositoryImpl(
            leadDao = database.leadDao(),
            leadApiService = mockLeadApi,
            context = context,
            normalizer = normalizer,
            dispatcher = Dispatchers.Unconfined
        )
        val pendingCallManager = PendingCallManager(context, normalizer)

        // Insert lead with rawNumber '09876543210' (11 digits, but last 10 digits match '9876543210')
        val lead = LeadEntity(
            id = "lead-sumit",
            name = "sumit",
            rawNumber = "09876543210",
            normalizedNumber = "09876543210",
            status = "NEW",
            createdAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        leadRepo.saveLead(lead)

        val processingManager = CallProcessingManager(
            leadRepository = leadRepo,
            callRepository = callRepo,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer
        )

        // Incoming call dialed as '+919876543210'
        val now = System.currentTimeMillis()
        val callEvent = CallEvent(
            id = "call-event-1",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = now - 60000,
            endTimeEpochMs = now - 30000,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING,
            simId = "SIM1"
        )

        val result = processingManager.processCallEvent(callEvent)
        assertTrue("Call should be accepted and recorded for lead 'sumit'", result is CallProcessingResult.Recorded)

        val recorded = result as CallProcessingResult.Recorded
        assertEquals(lead.id, recorded.call.leadId)
        assertTrue(recorded.call.isLeadCall)
    }

    @Test
    fun `test AuthViewModel does not eagerly resolve Lazy dependencies during cold startup unauthenticated state`() {
        var leadRepoResolved = false
        var callRepoResolved = false
        var workManagerResolved = false

        val fakeLeadRepoLazy = dagger.Lazy<com.leads365.apk.data.repository.LeadRepository> {
            leadRepoResolved = true
            throw AssertionError("LeadRepository should not be eagerly resolved during cold startup")
        }
        val fakeCallRepoLazy = dagger.Lazy<com.leads365.apk.data.repository.CallRepository> {
            callRepoResolved = true
            throw AssertionError("CallRepository should not be eagerly resolved during cold startup")
        }
        val fakeWorkManagerLazy = dagger.Lazy<com.leads365.apk.work.WorkManagerScheduler> {
            workManagerResolved = true
            throw AssertionError("WorkManagerScheduler should not be eagerly resolved during cold startup")
        }

        val authStateFlow = MutableStateFlow<com.leads365.apk.data.repository.AuthState>(
            com.leads365.apk.data.repository.AuthState.Unauthenticated
        )
        val mockAuthRepo = object : com.leads365.apk.data.repository.AuthRepository {
            override val authState: kotlinx.coroutines.flow.StateFlow<com.leads365.apk.data.repository.AuthState> = authStateFlow
            override fun isLoggedIn(): Boolean = false
            override fun getCurrentUser(): com.leads365.apk.network.models.UserDto? = null
            override fun getAgencyName(): String? = null
            override fun getDeviceId(): String? = null
            override suspend fun login(email: String, password: String): AppResult<com.leads365.apk.network.models.AuthData> =
                AppResult.Failure(com.leads365.apk.core.AppError.UnknownError("test"))
            override suspend fun registerDevice(
                deviceModel: String, manufacturer: String, osVersion: String,
                sdkInt: Int, appVersionCode: Int, appVersionName: String, fcmToken: String?
            ): AppResult<com.leads365.apk.network.models.DeviceData> =
                AppResult.Failure(com.leads365.apk.core.AppError.UnknownError("test"))
            override suspend fun updatePushToken(token: String): AppResult<Unit> = AppResult.Success(Unit)
            override fun getSavedFcmToken(): String? = null
            override fun saveFcmToken(token: String) {}
            override suspend fun logout(preservePendingUploads: Boolean): AppResult<Unit> = AppResult.Success(Unit)
            override fun invalidateSession(reason: String?) {}
        }

        // Instantiate AuthViewModel as done by Hilt during MainActivity cold-start
        val viewModel = com.leads365.apk.ui.auth.AuthViewModel(
            authRepository = mockAuthRepo,
            leadRepository = fakeLeadRepoLazy,
            callRepository = fakeCallRepoLazy,
            workManagerScheduler = fakeWorkManagerLazy
        )

        // Verify that none of the heavy dependencies were touched during initial composition
        assertFalse("leadRepository should not be resolved on cold startup", leadRepoResolved)
        assertFalse("callRepository should not be resolved on cold startup", callRepoResolved)
        assertFalse("workManagerScheduler should not be resolved on cold startup", workManagerResolved)
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun `test RecordingUploadManager extractErrorMessage parses JSON and fallback errors`() {
        val mockOkHttpClient = okhttp3.OkHttpClient()
        val mockTokenProvider = com.leads365.apk.network.auth.InMemoryTokenProvider("test-token", "test-refresh")
        val manager = com.leads365.apk.recordings.RecordingUploadManager(
            context = context,
            okHttpClient = mockOkHttpClient,
            tokenProvider = mockTokenProvider,
            baseUrl = "https://leadmanagementsystem-hdtp.onrender.com/api/"
        )

        val jsonError = """{"success":false,"error":"Call record not found on server"}"""
        val parsedJsonError = manager.extractErrorMessage(jsonError, "Default Error")
        assertEquals("Call record not found on server", parsedJsonError)

        val jsonMessage = """{"success":false,"message":"Tenant quota exceeded"}"""
        val parsedJsonMessage = manager.extractErrorMessage(jsonMessage, "Default Error")
        assertEquals("Tenant quota exceeded", parsedJsonMessage)

        val htmlBody = "<html><body>502 Bad Gateway</body></html>"
        val fallback = manager.extractErrorMessage(htmlBody, "Default Error")
        assertEquals("Default Error", fallback)
    }

    @Test
    fun `test CallRepository reportCallToBackend updates call status to SYNCED before upload`() = runBlocking {
        var callReported = false
        val mockApi = object : CallApiService {
            override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                callReported = true
                return CallReportResponse(success = true)
            }
        }

        val callRepo = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = mockApi,
            dispatcher = Dispatchers.Unconfined
        )

        // Insert local call with PENDING status
        val call = com.leads365.apk.database.entity.CallEntity(
            id = "test-call-id-99",
            clientCallId = "client-call-99",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            callType = android.provider.CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = System.currentTimeMillis() - 60000,
            durationSeconds = 60,
            syncStatus = "PENDING"
        )
        database.callDao().insert(call)

        // Execute reportCallToBackend
        val result = callRepo.reportCallToBackend("test-call-id-99")
        assertTrue("Reporting call should succeed", result is AppResult.Success)
        assertTrue("Backend API should be called", callReported)

        val updated = database.callDao().getById("test-call-id-99")
        assertNotNull(updated)
        assertEquals("SYNCED", updated?.syncStatus)
    }
}
