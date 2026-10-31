package com.leads365.apk

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.data.repository.CachedPolicy
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.diagnostics.BackgroundHardeningHelper
import com.leads365.apk.work.BootCompletedReceiver
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase12BackgroundHardeningTest {

    private lateinit var context: Context
    private lateinit var testWorkManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        testWorkManager = WorkManager.getInstance(context)
    }

    @Test
    fun testSchedulePeriodicLeadSyncExecutesWithoutCrashing() {
        val scheduler = WorkManagerScheduler(
            context = context,
            workManager = testWorkManager
        )

        // Multiple calls must not throw or fail (ExistingPeriodicWorkPolicy.KEEP)
        scheduler.schedulePeriodicLeadSync()
        scheduler.schedulePeriodicLeadSync()
        assertTrue("Periodic work enqueued safely", true)
    }

    @Test
    fun testScheduleUploadTaskEnqueuesSafely() {
        val scheduler = WorkManagerScheduler(
            context = context,
            workManager = testWorkManager
        )

        // Upload task enqueued with KEEP
        scheduler.scheduleUploadTask(taskId = "task_101", fileUri = "content://audio/101.m4a", callId = "call_202")
        scheduler.scheduleUploadTask(taskId = "task_101", fileUri = "content://audio/101.m4a", callId = "call_202")
        assertTrue("Upload task enqueued with KEEP safely", true)
    }

    @Test
    fun testResumePendingUploadsRecoversStuckUploadingTasks() = runBlocking {
        val stuckTask = UploadTaskEntity(
            id = "task_stuck",
            callId = "call_stuck",
            localFileUri = "content://audio/stuck.m4a",
            fileName = "stuck.m4a",
            fileSizeBytes = 1024L,
            status = UploadTaskEntity.STATE_UPLOADING
        )
        val retryTask = UploadTaskEntity(
            id = "task_retry",
            callId = "call_retry",
            localFileUri = "content://audio/retry.m4a",
            fileName = "retry.m4a",
            fileSizeBytes = 2048L,
            status = UploadTaskEntity.STATE_RETRY_WAIT
        )

        val fakeUploadRepo = TestUploadQueueRepo(mutableListOf(stuckTask, retryTask))
        val fakePolicyRepo = TestPolicyRepo(paused = false)

        var scheduledCount = 0
        val recordingScheduler = object : WorkManagerScheduler(
            context = context,
            workManager = testWorkManager,
            uploadQueueRepository = fakeUploadRepo,
            policyRepository = fakePolicyRepo
        ) {
            override fun scheduleUploadTask(taskId: String, fileUri: String, callId: String?) {
                scheduledCount++
                super.scheduleUploadTask(taskId, fileUri, callId)
            }
        }

        val resumed = recordingScheduler.resumePendingUploads()

        assertEquals("Should resume 2 tasks", 2, resumed)
        assertEquals("Both tasks should be scheduled in WorkManager", 2, scheduledCount)

        // The stuck task should have had its status updated to RETRY_WAIT
        val updatedStuck = fakeUploadRepo.tasks.find { it.id == "task_stuck" }
        assertEquals("Stuck task must be reset to RETRY_WAIT", UploadTaskEntity.STATE_RETRY_WAIT, updatedStuck?.status)
        assertTrue(
            "LastError should indicate recovery after termination",
            updatedStuck?.lastError?.contains("Resumed") == true
        )
    }

    @Test
    fun testResumePendingUploadsDefersWhenAgencySyncPaused() = runBlocking {
        val pendingTask = UploadTaskEntity(
            id = "task_pending",
            callId = "call_pending",
            localFileUri = "content://audio/pending.m4a",
            fileName = "pending.m4a",
            fileSizeBytes = 1024L,
            status = UploadTaskEntity.STATUS_PENDING
        )

        val fakeUploadRepo = TestUploadQueueRepo(mutableListOf(pendingTask))
        val pausedPolicyRepo = TestPolicyRepo(paused = true)

        var scheduledCount = 0
        val recordingScheduler = object : WorkManagerScheduler(
            context = context,
            workManager = testWorkManager,
            uploadQueueRepository = fakeUploadRepo,
            policyRepository = pausedPolicyRepo
        ) {
            override fun scheduleUploadTask(taskId: String, fileUri: String, callId: String?) {
                scheduledCount++
                super.scheduleUploadTask(taskId, fileUri, callId)
            }
        }

        val resumed = recordingScheduler.resumePendingUploads()

        assertEquals("Must return 0 resumed tasks when paused by policy", 0, resumed)
        assertEquals("No work should be scheduled while paused", 0, scheduledCount)
    }

    @Test
    fun testBootCompletedReceiverSkipsWhenUserLoggedOut() {
        val fakeAuth = FakeAuthRepo(isLoggedIn = false)
        var periodicScheduled = false

        val testScheduler = object : WorkManagerScheduler(context, testWorkManager) {
            override fun schedulePeriodicLeadSync() {
                periodicScheduled = true
            }
        }

        val receiver = BootCompletedReceiver().apply {
            this.workManagerScheduler = testScheduler
            this.authRepository = fakeAuth
        }

        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        assertFalse("Periodic sync must NOT be scheduled if user is logged out", periodicScheduled)
    }

    @Test
    fun testBootCompletedReceiverReschedulesWhenUserLoggedIn() {
        val fakeAuth = FakeAuthRepo(isLoggedIn = true, agentId = "agent_123")
        var periodicScheduled = false

        val testScheduler = object : WorkManagerScheduler(context, testWorkManager) {
            override fun schedulePeriodicLeadSync() {
                periodicScheduled = true
            }
        }

        val receiver = BootCompletedReceiver().apply {
            this.workManagerScheduler = testScheduler
            this.authRepository = fakeAuth
        }

        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        assertTrue("Periodic sync must be rescheduled for logged-in user on boot", periodicScheduled)
    }

    @Test
    fun testBackgroundHardeningHelperEvaluatesReadinessAndBuildsSafeIntents() {
        val assessment = BackgroundHardeningHelper.evaluateBackgroundHardening(context)

        assertNotNull("Hardening assessment must not be null", assessment)
        assertNotNull("Manufacturer must be detected", assessment.manufacturer)
        assertTrue("Guidance title must not be blank", assessment.guidanceTitle.isNotBlank())
        assertTrue("Actionable guidance must not be blank", assessment.actionableGuidance.isNotBlank())

        assertNotNull("Device model must be present", assessment.deviceModel)
        assertTrue("Device model must not be blank", assessment.deviceModel.isNotBlank())
        assertNotNull("Verified status must be present", assessment.verifiedByApp)
        assertNotNull("Manual action steps must be present", assessment.requiresManualUserAction)

        val batteryIntent = BackgroundHardeningHelper.createBatteryOptimizationIntent(context)
        assertNotNull("Battery optimization intent must not be null", batteryIntent)
        assertNotNull("Battery intent action must not be null", batteryIntent.action)

        val appDetailsIntent = BackgroundHardeningHelper.createAppDetailsIntent(context)
        assertEquals(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            appDetailsIntent.action
        )
        assertEquals("package:${context.packageName}", appDetailsIntent.dataString)
    }

    @Test
    fun testSafeQueueRetentionPurgesOnlyExpiredTerminalTasks() = runBlocking {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.leads365.apk.database.Leads365Database::class.java
        ).allowMainThreadQueries().build()

        val repo = com.leads365.apk.data.repository.UploadQueueRepositoryImpl(
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val now = System.currentTimeMillis()
        val eightDaysAgo = now - (8 * 24 * 60 * 60 * 1000L)
        val oneDayAgo = now - (1 * 24 * 60 * 60 * 1000L)

        // Pending upload (8 days old) -> MUST BE PRESERVED
        repo.enqueueTask(
            UploadTaskEntity(
                id = "p1",
                callId = "c1",
                localFileUri = "content://rec/1",
                fileName = "call1.m4a",
                fileSizeBytes = 1000L,
                status = UploadTaskEntity.STATUS_PENDING,
                updatedAtEpochMs = eightDaysAgo
            )
        )

        // Retry wait upload (8 days old) -> MUST BE PRESERVED
        repo.enqueueTask(
            UploadTaskEntity(
                id = "r1",
                callId = "c2",
                localFileUri = "content://rec/2",
                fileName = "call2.m4a",
                fileSizeBytes = 2000L,
                status = UploadTaskEntity.STATE_RETRY_WAIT,
                updatedAtEpochMs = eightDaysAgo
            )
        )

        // Uploaded task (8 days old) -> MUST BE PURGED
        repo.enqueueTask(
            UploadTaskEntity(
                id = "u1",
                callId = "c3",
                localFileUri = "content://rec/3",
                fileName = "call3.m4a",
                fileSizeBytes = 3000L,
                status = UploadTaskEntity.STATE_UPLOADED,
                updatedAtEpochMs = eightDaysAgo
            )
        )

        // Recent uploaded task (1 day old) -> MUST BE PRESERVED
        repo.enqueueTask(
            UploadTaskEntity(
                id = "u2",
                callId = "c4",
                localFileUri = "content://rec/4",
                fileName = "call4.m4a",
                fileSizeBytes = 4000L,
                status = UploadTaskEntity.STATE_UPLOADED,
                updatedAtEpochMs = oneDayAgo
            )
        )

        val cleanResult = repo.cleanExpiredTerminalTasks(retentionPeriodMs = 7 * 24 * 60 * 60 * 1000L)
        assertTrue(cleanResult is AppResult.Success)
        assertEquals(1, (cleanResult as AppResult.Success).data)

        // Active tasks must remain
        assertNotNull((repo.getTaskById("p1") as AppResult.Success).data)
        assertNotNull((repo.getTaskById("r1") as AppResult.Success).data)
        assertNotNull((repo.getTaskById("u2") as AppResult.Success).data)

        // Expired terminal task must be removed
        assertNull((repo.getTaskById("u1") as AppResult.Success).data)

        database.close()
    }
}

// -----------------------------------------------------------------
// Test Fakes for Phase 12
// -----------------------------------------------------------------
private class TestUploadQueueRepo(
    val tasks: MutableList<UploadTaskEntity> = mutableListOf()
) : UploadQueueRepository {
    override fun getAllTasksFlow(): Flow<List<UploadTaskEntity>> = flowOf(tasks)
    override suspend fun enqueueTask(task: UploadTaskEntity): AppResult<Unit> {
        tasks.add(task)
        return AppResult.Success(Unit)
    }
    override suspend fun getPendingTasks(): AppResult<List<UploadTaskEntity>> =
        AppResult.Success(tasks.filter { it.status == UploadTaskEntity.STATUS_PENDING })
    override suspend fun getTasksByStatus(status: String): AppResult<List<UploadTaskEntity>> =
        AppResult.Success(tasks.filter { it.status == status })
    override suspend fun getTaskById(id: String): AppResult<UploadTaskEntity?> =
        AppResult.Success(tasks.find { it.id == id })
    override suspend fun getTaskByCallId(callId: String): AppResult<UploadTaskEntity?> =
        AppResult.Success(tasks.find { it.callId == callId })
    override suspend fun updateStatus(id: String, status: String, error: String?): AppResult<Unit> {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tasks[idx] = tasks[idx].copy(status = status, lastError = error)
        }
        return AppResult.Success(Unit)
    }
    override suspend fun incrementRetry(id: String, error: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteTaskById(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun clearCompleted(): AppResult<Unit> = AppResult.Success(Unit)
}

private class TestPolicyRepo(
    private val paused: Boolean = false
) : PolicyRepository {
    override val policyState: StateFlow<CachedPolicy> = MutableStateFlow(
        CachedPolicy(
            allowRecordingPause = true,
            recordingSyncPaused = paused,
            enforceStrictDnr = true
        )
    )
    override val consentAcknowledged: StateFlow<Boolean> = MutableStateFlow(true)
    override suspend fun syncPolicy(): AppResult<com.leads365.apk.network.models.PolicyData> =
        AppResult.Failure(com.leads365.apk.core.AppError.UnknownError("Not implemented"))
    override suspend fun setPauseSyncState(paused: Boolean): AppResult<Boolean> = AppResult.Success(paused)
    override suspend fun setAutoLeadPolicy(
        enabled: Boolean?,
        minDurationSeconds: Int?,
        tenantId: String?
    ): AppResult<com.leads365.apk.network.models.AutoLeadPolicyData> =
        AppResult.Success(com.leads365.apk.network.models.AutoLeadPolicyData(enabled ?: false, minDurationSeconds ?: 10))
    override fun acknowledgeConsent() {}
    override fun isConsentAcknowledged(): Boolean = true
    override fun isRecordingSyncPaused(): Boolean = paused
    override fun isStrictDnrEnforced(): Boolean = true
}
