package com.leads365.apk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.*
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.diagnostics.*
import com.leads365.apk.network.models.PolicyData
import com.leads365.apk.notifications.LeadsNotificationManager
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase11DiagnosticsAndSelfTest {

    private lateinit var context: Context
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var pendingCallManager: PendingCallManager
    private lateinit var fakeScheduler: WorkManagerScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        normalizer = PhoneNumberNormalizer("IN")
        pendingCallManager = PendingCallManager(context, normalizer)
        LeadsNotificationManager.initChannels(context)

        fakeScheduler = object : WorkManagerScheduler(
            context,
            androidx.work.WorkManager.getInstance(context)
        ) {}
    }

    // -------------------------------------------------------------
    // Test 1: Full self-test execution produces structured report
    // -------------------------------------------------------------
    @Test
    fun testSelfTestProducesComprehensiveStructuredReport() = runTest {
        val fakeAuth = FakeAuthRepo(isLoggedIn = true, agentId = "agent_12345678", agencyName = "agency_abc")
        val fakeLead = FakeLeadRepo(leads = listOf(LeadEntity("1", "Rohan", "+919876543210", "+919876543210", "New")))
        val fakePolicy = FakePolicyRepo(acknowledged = true, paused = false, strictDnr = true)
        val fakeUploadQueue = FakeUploadQueueRepo()

        val runner = SelfTestRunner(
            context = context,
            authRepository = fakeAuth,
            leadRepository = fakeLead,
            policyRepository = fakePolicy,
            uploadQueueRepository = fakeUploadQueue,
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )

        val report = runner.runFullDiagnostics()
        assertNotNull("Report must not be null", report)
        assertTrue("Report must have evaluated diagnostic items", report.totalCount >= 10)
        assertTrue("Report must contain PASS items", report.passedCount > 0)

        // Internal logic item must pass deterministically
        val internalLogic = report.items.find { it.id == "diag_internal_logic" }
        assertNotNull(internalLogic)
        assertEquals("Internal logic must PASS in test environment", DiagnosticStatus.PASS, internalLogic?.status)
    }

    // -------------------------------------------------------------
    // Test 2: Unconfigured Firebase yields WARNING and does not crash or claim fake delivery
    // -------------------------------------------------------------
    @Test
    fun testFirebaseMissingConfigurationYieldsWarningWithoutCrashing() = runTest {
        val runner = SelfTestRunner(
            context = context,
            authRepository = FakeAuthRepo(),
            leadRepository = FakeLeadRepo(),
            policyRepository = FakePolicyRepo(),
            uploadQueueRepository = FakeUploadQueueRepo(),
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )

        val report = runner.runFullDiagnostics()
        val fcmItem = report.items.find { it.id == "diag_fcm_push" }
        assertNotNull("FCM diagnostic item must exist", fcmItem)
        assertEquals("Missing google-services.json must report WARNING", DiagnosticStatus.WARNING, fcmItem?.status)
        assertTrue(
            "FCM summary must explain missing config honestly",
            fcmItem?.summary?.contains("Missing google-services.json") == true ||
            fcmItem?.summary?.contains("Firebase") == true
        )
        assertNotNull("Actionable step must be provided for missing Firebase config", fcmItem?.actionableStep)
    }

    // -------------------------------------------------------------
    // Test 3: Queued uploads are never falsely reported as uploaded
    // -------------------------------------------------------------
    @Test
    fun testQueuedUploadsAreNeverReportedAsUploaded() = runTest {
        val pendingTask = UploadTaskEntity(
            id = "upload_task_1",
            callId = "call_1",
            localFileUri = "content://recordings/1.m4a",
            fileName = "1.m4a",
            fileSizeBytes = 1024L,
            status = "PENDING"
        )
        val failedTask = UploadTaskEntity(
            id = "upload_task_2",
            callId = "call_2",
            localFileUri = "content://recordings/2.m4a",
            fileName = "2.m4a",
            fileSizeBytes = 2048L,
            status = "FAILED",
            lastError = "Connection reset"
        )

        val fakeUploadQueue = FakeUploadQueueRepo(tasks = listOf(pendingTask, failedTask))

        val runner = SelfTestRunner(
            context = context,
            authRepository = FakeAuthRepo(),
            leadRepository = FakeLeadRepo(),
            policyRepository = FakePolicyRepo(),
            uploadQueueRepository = fakeUploadQueue,
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )

        val report = runner.runFullDiagnostics()
        val queueItem = report.items.find { it.id == "diag_upload_queue" }
        assertNotNull("Queue diagnostic item must exist", queueItem)
        // With failed task present, status must be WARNING
        assertEquals("Queue with failed tasks must yield WARNING", DiagnosticStatus.WARNING, queueItem?.status)
        assertTrue(
            "Queue summary must distinguish pending/failed from uploaded",
            queueItem?.summary?.contains("failed") == true && queueItem?.summary?.contains("pending") == true
        )
    }

    // -------------------------------------------------------------
    // Test 4: Auth session masks sensitive IDs and does not expose tokens
    // -------------------------------------------------------------
    @Test
    fun testAuthSessionMasksCredentialsAndExposesNoRawTokens() = runTest {
        val fakeAuth = FakeAuthRepo(
            isLoggedIn = true,
            agentId = "secret_agent_id_xyz999",
            agencyName = "confidential_agency_888"
        )

        val runner = SelfTestRunner(
            context = context,
            authRepository = fakeAuth,
            leadRepository = FakeLeadRepo(),
            policyRepository = FakePolicyRepo(),
            uploadQueueRepository = FakeUploadQueueRepo(),
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )

        val report = runner.runFullDiagnostics()
        val authItem = report.items.find { it.id == "diag_auth_session" }
        assertNotNull("Auth diagnostic item must exist", authItem)
        assertEquals(DiagnosticStatus.PASS, authItem?.status)
        assertFalse("Must not expose full secret agent ID", authItem?.summary?.contains("secret_agent_id_xyz999") == true)
        assertFalse("Must not expose full agency name", authItem?.summary?.contains("confidential_agency_888") == true)
    }

    // -------------------------------------------------------------
    // Test 5: Self-test is idempotent, repeatable and mutates no production data
    // -------------------------------------------------------------
    @Test
    fun testSelfTestIsRepeatableAndNonMutating() = runTest {
        val fakeLead = FakeLeadRepo(leads = listOf(LeadEntity("1", "Anita", "+919876543210", "+919876543210", "New")))
        val fakeUpload = FakeUploadQueueRepo()

        val runner = SelfTestRunner(
            context = context,
            authRepository = FakeAuthRepo(),
            leadRepository = fakeLead,
            policyRepository = FakePolicyRepo(),
            uploadQueueRepository = fakeUpload,
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )

        // Run 1
        val report1 = runner.runFullDiagnostics()
        val leadsAfterRun1 = (fakeLead.getAllLeads() as AppResult.Success).data.size
        assertEquals("Run 1 must not alter leads count", 1, leadsAfterRun1)

        // Run 2
        val report2 = runner.runFullDiagnostics()
        val leadsAfterRun2 = (fakeLead.getAllLeads() as AppResult.Success).data.size
        assertEquals("Run 2 must not alter leads count", 1, leadsAfterRun2)

        assertEquals("Repeat run item count must match", report1.totalCount, report2.totalCount)
    }

    // -------------------------------------------------------------
    // Test 6: Privacy and DNR state correctly reflected
    // -------------------------------------------------------------
    @Test
    fun testPrivacyAndDnrStateCorrectlyReflected() = runTest {
        // When consent is not acknowledged
        val unacknowledgedPolicy = FakePolicyRepo(acknowledged = false)
        val runnerUnacked = SelfTestRunner(
            context = context,
            authRepository = FakeAuthRepo(),
            leadRepository = FakeLeadRepo(),
            policyRepository = unacknowledgedPolicy,
            uploadQueueRepository = FakeUploadQueueRepo(),
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )
        val reportUnacked = runnerUnacked.runFullDiagnostics()
        val policyItemUnacked = reportUnacked.items.find { it.id == "diag_privacy_policy" }
        assertEquals(DiagnosticStatus.WARNING, policyItemUnacked?.status)

        // When sync is paused
        val pausedPolicy = FakePolicyRepo(acknowledged = true, paused = true)
        val runnerPaused = SelfTestRunner(
            context = context,
            authRepository = FakeAuthRepo(),
            leadRepository = FakeLeadRepo(),
            policyRepository = pausedPolicy,
            uploadQueueRepository = FakeUploadQueueRepo(),
            workManagerScheduler = fakeScheduler,
            normalizer = normalizer,
            pendingCallManager = pendingCallManager
        )
        val reportPaused = runnerPaused.runFullDiagnostics()
        val policyItemPaused = reportPaused.items.find { it.id == "diag_privacy_policy" }
        assertEquals(DiagnosticStatus.WARNING, policyItemPaused?.status)
        assertTrue(policyItemPaused?.summary?.contains("paused") == true)
    }
}

// -----------------------------------------------------------------
// Test Fakes
// -----------------------------------------------------------------
class FakeAuthRepo(
    private val isLoggedIn: Boolean = false,
    private val agentId: String? = null,
    private val agencyName: String? = "Test Agency"
) : AuthRepository {
    override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Unauthenticated)
    override fun isLoggedIn(): Boolean = isLoggedIn
    override fun getCurrentUser(): com.leads365.apk.network.models.UserDto? =
        if (isLoggedIn) com.leads365.apk.network.models.UserDto(id = agentId ?: "dummy_id", name = "Test Agent", email = "agent@leads365.com") else null
    override fun getAgencyName(): String? = if (isLoggedIn) agencyName else null
    override fun getDeviceId(): String? = "dummy_device_id"

    override suspend fun login(email: String, password: String): AppResult<com.leads365.apk.network.models.AuthData> =
        AppResult.Failure(com.leads365.apk.core.AppError.UnknownError("Not implemented"))
    override suspend fun registerDevice(
        deviceModel: String,
        manufacturer: String,
        osVersion: String,
        sdkInt: Int,
        appVersionCode: Int,
        appVersionName: String,
        fcmToken: String?
    ): AppResult<com.leads365.apk.network.models.DeviceData> =
        AppResult.Failure(com.leads365.apk.core.AppError.UnknownError("Not implemented"))

    override suspend fun updatePushToken(token: String): AppResult<Unit> = AppResult.Success(Unit)
    override fun getSavedFcmToken(): String? = null
    override fun saveFcmToken(token: String) {}
    override suspend fun logout(preservePendingUploads: Boolean): AppResult<Unit> = AppResult.Success(Unit)
    override fun invalidateSession(reason: String?) {}
}

class FakeLeadRepo(
    private val leads: List<LeadEntity> = emptyList(),
    private val lastSync: Long = 1000L
) : LeadRepository {
    override fun getAllLeadsFlow(): Flow<List<LeadEntity>> = flowOf(leads)
    override fun searchLeadsFlow(query: String, status: String): Flow<List<LeadEntity>> = flowOf(leads)
    override suspend fun getAllLeads(): AppResult<List<LeadEntity>> = AppResult.Success(leads)
    override suspend fun getLeadById(id: String): AppResult<LeadEntity?> = AppResult.Success(leads.find { it.id == id })
    override suspend fun getLeadByNormalizedNumber(normalizedNumber: String): AppResult<LeadEntity?> =
        AppResult.Success(leads.find { it.normalizedNumber == normalizedNumber })
    override suspend fun saveLead(lead: LeadEntity): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun saveLeads(leads: List<LeadEntity>): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteLeadById(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun clearAllLeads(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun syncLeads(forceFullSync: Boolean): AppResult<LeadSyncResult> =
        AppResult.Success(LeadSyncResult(0, 0, false, lastSync))
    override fun getLastSyncTimestamp(): Long = lastSync
}

class FakePolicyRepo(
    private val acknowledged: Boolean = true,
    private val paused: Boolean = false,
    private val strictDnr: Boolean = true
) : PolicyRepository {
    override val policyState: StateFlow<CachedPolicy> = MutableStateFlow(
        CachedPolicy(
            allowRecordingPause = true,
            recordingSyncPaused = paused,
            enforceStrictDnr = strictDnr
        )
    )
    override val consentAcknowledged: StateFlow<Boolean> = MutableStateFlow(acknowledged)
    override suspend fun syncPolicy(): AppResult<PolicyData> =
        AppResult.Failure(com.leads365.apk.core.AppError.UnknownError("Not implemented"))
    override suspend fun setPauseSyncState(paused: Boolean): AppResult<Boolean> = AppResult.Success(paused)
    override fun acknowledgeConsent() {}
    override fun isConsentAcknowledged(): Boolean = acknowledged
    override fun isRecordingSyncPaused(): Boolean = paused
    override fun isStrictDnrEnforced(): Boolean = strictDnr
}

class FakeUploadQueueRepo(
    private val tasks: List<UploadTaskEntity> = emptyList()
) : UploadQueueRepository {
    override fun getAllTasksFlow(): Flow<List<UploadTaskEntity>> = flowOf(tasks)
    override suspend fun enqueueTask(task: UploadTaskEntity): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun getPendingTasks(): AppResult<List<UploadTaskEntity>> =
        AppResult.Success(tasks.filter { it.status == "PENDING" })
    override suspend fun getTasksByStatus(status: String): AppResult<List<UploadTaskEntity>> =
        AppResult.Success(tasks.filter { it.status == status })
    override suspend fun getTaskById(id: String): AppResult<UploadTaskEntity?> =
        AppResult.Success(tasks.find { it.id == id })
    override suspend fun getTaskByCallId(callId: String): AppResult<UploadTaskEntity?> =
        AppResult.Success(tasks.find { it.callId == callId })
    override suspend fun updateStatus(id: String, status: String, error: String?): AppResult<Unit> =
        AppResult.Success(Unit)
    override suspend fun incrementRetry(id: String, error: String): AppResult<Unit> =
        AppResult.Success(Unit)
    override suspend fun deleteTaskById(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun clearCompleted(): AppResult<Unit> = AppResult.Success(Unit)
}
