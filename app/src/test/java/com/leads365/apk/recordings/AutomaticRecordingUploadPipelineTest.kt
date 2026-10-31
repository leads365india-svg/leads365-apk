package com.leads365.apk.recordings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.ui.upload.getBadgeAttributes
import com.leads365.apk.ui.upload.isWaitingForWifi
import com.leads365.apk.work.RecordingUploadWorker
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AutomaticRecordingUploadPipelineTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var uploadTaskDao: UploadTaskDao
    private lateinit var uploadQueueRepository: UploadQueueRepositoryImpl
    private lateinit var workManager: WorkManager
    private lateinit var workManagerScheduler: WorkManagerScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)

        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        uploadTaskDao = database.uploadTaskDao()

        uploadQueueRepository = UploadQueueRepositoryImpl(
            uploadTaskDao = uploadTaskDao,
            ioDispatcher = Dispatchers.Unconfined
        )

        workManagerScheduler = WorkManagerScheduler(
            context = context,
            workManager = workManager,
            uploadQueueRepository = uploadQueueRepository
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test 4G to Wi-Fi transition - pending recordings safely queued on 4G and upload scheduled on Wi-Fi`() = runBlocking {
        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, true).commit()

        val task = UploadTaskEntity(
            id = "task_4g_to_wifi",
            callId = "call_4g_to_wifi",
            leadId = "lead_001",
            localFileUri = "content://recordings/sample.m4a",
            fileName = "sample.m4a",
            fileSizeBytes = 45_000L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(task)

        // 1. Enqueue upload while Wi-Fi Only is ON
        workManagerScheduler.scheduleUploadTask(task.id, task.localFileUri, task.callId)

        // Verify WorkManager constraint requires UNMETERED network
        val workInfos = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + task.callId).get()
        assertNotNull(workInfos)
        assertTrue(workInfos.isNotEmpty())
        val workInfo = workInfos.first()
        assertEquals(NetworkType.UNMETERED, workInfo.constraints.requiredNetworkType)

        // Verify UI status when on 4G/Cellular (not Wi-Fi) displays "Waiting for Wi-Fi"
        val waitingWifi = isWaitingForWifi(task, wifiOnly = true, isWifiConnected = false)
        assertTrue("Task must be marked waiting for Wi-Fi on cellular connection", waitingWifi)

        val (_, _, badgeText, _) = getBadgeAttributes(task, wifiOnly = true, isWifiConnected = false)
        assertEquals("Waiting for Wi-Fi", badgeText)

        // 2. Simulate Wi-Fi connection becoming available
        val (_, _, wifiBadgeText, _) = getBadgeAttributes(task, wifiOnly = true, isWifiConnected = true)
        assertEquals("Queued", wifiBadgeText)

        // Resuming pending uploads on Wi-Fi schedules the task
        val resumed = workManagerScheduler.resumePendingUploads()
        assertTrue("Resume pending uploads must process queued tasks", resumed >= 1)
    }

    @Test
    fun `test Wi-Fi to 4G transition - Wi-Fi only uploads pause safely`() = runBlocking {
        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, true).commit()

        // Configure a task in RETRY_WAIT due to Wi-Fi disconnect
        val task = UploadTaskEntity(
            id = "task_wifi_dropped",
            callId = "call_wifi_dropped",
            leadId = "lead_001",
            localFileUri = "content://recordings/dropped.m4a",
            fileName = "dropped.m4a",
            fileSizeBytes = 50_000L,
            status = UploadTaskEntity.STATE_RETRY_WAIT,
            lastError = "Waiting for unmetered Wi-Fi connection"
        )
        uploadTaskDao.insert(task)

        // On 4G, task safely reflects Waiting for Wi-Fi status
        val isWaiting = isWaitingForWifi(task, wifiOnly = true, isWifiConnected = false)
        assertTrue(isWaiting)

        val (_, _, badgeText, _) = getBadgeAttributes(task, wifiOnly = true, isWifiConnected = false)
        assertEquals("Waiting for Wi-Fi", badgeText)
    }

    @Test
    fun `test Wi-Fi Only OFF plus 4G - automatic upload works over cellular network`() = runBlocking {
        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false).commit()

        val task = UploadTaskEntity(
            id = "task_cellular_ok",
            callId = "call_cellular_ok",
            leadId = "lead_001",
            localFileUri = "content://recordings/cellular.m4a",
            fileName = "cellular.m4a",
            fileSizeBytes = 25_000L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(task)

        // Schedule task with Wi-Fi Only = false
        workManagerScheduler.scheduleUploadTask(task.id, task.localFileUri, task.callId)

        val workInfos = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + task.callId).get()
        assertNotNull(workInfos)
        val workInfo = workInfos.first()
        // Constraint requires CONNECTED (not restricted to UNMETERED)
        assertEquals(NetworkType.CONNECTED, workInfo.constraints.requiredNetworkType)

        // UI badge reflects "Queued" rather than waiting for Wi-Fi
        val (_, _, badgeText, _) = getBadgeAttributes(task, wifiOnly = false, isWifiConnected = false)
        assertEquals("Queued", badgeText)
    }

    @Test
    fun `test toggling Wi-Fi Only setting reschedules all pending uploads with new network constraints`() = runBlocking {
        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        // Initially Wi-Fi Only is ON
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, true).commit()

        val task = UploadTaskEntity(
            id = "task_toggle_setting",
            callId = "call_toggle_setting",
            leadId = "lead_001",
            localFileUri = "content://recordings/test.m4a",
            fileName = "test.m4a",
            fileSizeBytes = 30_000L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(task)

        workManagerScheduler.scheduleUploadTask(task.id, task.localFileUri, task.callId)
        val initialWork = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + task.callId).get().first()
        assertEquals(NetworkType.UNMETERED, initialWork.constraints.requiredNetworkType)

        // User turns Wi-Fi Only OFF
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false).commit()
        val rescheduledCount = workManagerScheduler.rescheduleAllPendingUploads()
        assertEquals(1, rescheduledCount)

        val updatedWork = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + task.callId).get().first()
        assertEquals(NetworkType.CONNECTED, updatedWork.constraints.requiredNetworkType)

        // Verify task in Room is preserved
        val taskInDb = uploadTaskDao.getById(task.id)
        assertNotNull(taskInDb)
        assertEquals(UploadTaskEntity.STATUS_PENDING, taskInDb?.status)
    }

    @Test
    fun `test app restart recovers interrupted UPLOADING tasks and resumes pending uploads`() = runBlocking {
        // Task was stuck in UPLOADING when process died / crashed
        val stuckTask = UploadTaskEntity(
            id = "task_interrupted",
            callId = "call_interrupted",
            leadId = "lead_001",
            localFileUri = "content://recordings/stuck.m4a",
            fileName = "stuck.m4a",
            fileSizeBytes = 40_000L,
            status = UploadTaskEntity.STATE_UPLOADING
        )
        val pendingTask = UploadTaskEntity(
            id = "task_pending_restart",
            callId = "call_pending_restart",
            leadId = "lead_001",
            localFileUri = "content://recordings/pending.m4a",
            fileName = "pending.m4a",
            fileSizeBytes = 35_000L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(stuckTask)
        uploadTaskDao.insert(pendingTask)

        // Simulate app restart / cold boot recovery
        val resumedCount = workManagerScheduler.resumePendingUploads()
        assertEquals(2, resumedCount)

        // Verify stuck task was recovered to RETRY_WAIT and re-enqueued
        val recoveredStuck = uploadTaskDao.getById("task_interrupted")
        assertEquals(UploadTaskEntity.STATE_RETRY_WAIT, recoveredStuck?.status)
        assertTrue(recoveredStuck?.lastError?.contains("Resumed after process death") == true)

        // Verify WorkManager has jobs scheduled for both
        val work1 = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + stuckTask.callId).get()
        val work2 = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + pendingTask.callId).get()
        assertTrue(work1.isNotEmpty())
        assertTrue(work2.isNotEmpty())
    }

    @Test
    fun `test UI statuses map correctly to required labels`() {
        val baseTask = UploadTaskEntity(
            id = "t_status",
            localFileUri = "content://test",
            fileName = "test.m4a",
            fileSizeBytes = 1024L
        )

        // 1. Waiting for Wi-Fi
        val waitingWifiTask = baseTask.copy(
            status = UploadTaskEntity.STATE_RETRY_WAIT,
            lastError = "Waiting for unmetered Wi-Fi connection"
        )
        assertEquals("Waiting for Wi-Fi", getBadgeAttributes(waitingWifiTask, wifiOnly = true, isWifiConnected = false).text)

        // 2. Queued
        val queuedTask1 = baseTask.copy(status = UploadTaskEntity.STATUS_PENDING)
        val queuedTask2 = baseTask.copy(status = UploadTaskEntity.STATE_FILE_FOUND)
        assertEquals("Queued", getBadgeAttributes(queuedTask1, wifiOnly = false, isWifiConnected = true).text)
        assertEquals("Queued", getBadgeAttributes(queuedTask2, wifiOnly = false, isWifiConnected = true).text)

        // 3. Uploading
        val uploadingTask = baseTask.copy(status = UploadTaskEntity.STATE_UPLOADING)
        assertEquals("Uploading", getBadgeAttributes(uploadingTask, wifiOnly = false, isWifiConnected = true).text)

        // 4. Uploaded
        val uploadedTask = baseTask.copy(status = UploadTaskEntity.STATE_UPLOADED)
        assertEquals("Uploaded", getBadgeAttributes(uploadedTask, wifiOnly = false, isWifiConnected = true).text)

        // 5. Failed (with retry)
        val failedTask1 = baseTask.copy(status = UploadTaskEntity.STATE_FAILED_PERMANENT, lastError = "HTTP 500")
        val failedTask2 = baseTask.copy(status = UploadTaskEntity.STATUS_FAILED, lastError = "Timeout")
        val failedTask3 = baseTask.copy(status = UploadTaskEntity.STATE_REJECTED_BY_SERVER, lastError = "HTTP 403")
        assertEquals("Failed", getBadgeAttributes(failedTask1, wifiOnly = false, isWifiConnected = true).text)
        assertEquals("Failed", getBadgeAttributes(failedTask2, wifiOnly = false, isWifiConnected = true).text)
        assertEquals("Failed", getBadgeAttributes(failedTask3, wifiOnly = false, isWifiConnected = true).text)
    }

    @Test
    fun `test DNR calls are never uploaded and map to skipped status`() {
        val dnrTask = UploadTaskEntity(
            id = "t_dnr",
            callId = "c_dnr",
            leadId = "lead_dnr",
            localFileUri = "content://dnr",
            fileName = "dnr.m4a",
            fileSizeBytes = 1024L,
            status = UploadTaskEntity.STATE_SKIPPED_DNR
        )
        val badge = getBadgeAttributes(dnrTask, wifiOnly = false, isWifiConnected = true)
        assertEquals("DNR Skipped", badge.text)
    }
}
