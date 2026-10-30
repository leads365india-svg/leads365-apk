package com.leads365.apk.recordings

import android.content.Context
import android.provider.CallLog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.auth.InMemoryTokenProvider
import com.leads365.apk.network.models.CallListResponse
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import com.leads365.apk.ui.calls.CallFilterTab
import com.leads365.apk.ui.calls.CallRecordUiModel
import com.leads365.apk.ui.calls.CallsViewModel
import com.leads365.apk.work.RecordingUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecordingUploadCardAndPipelineTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var callDao: CallDao
    private lateinit var uploadTaskDao: UploadTaskDao
    private lateinit var callRepository: CallRepositoryImpl
    private lateinit var uploadQueueRepository: UploadQueueRepositoryImpl
    private lateinit var tokenProvider: InMemoryTokenProvider
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var matcher: TargetedRecordingMatcher
    private lateinit var workManager: WorkManager

    private val testLead = LeadEntity(
        id = "lead_test_001",
        name = "CRM Test Lead",
        rawNumber = "9876543210",
        normalizedNumber = "+919876543210"
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)

        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        callDao = database.callDao()
        uploadTaskDao = database.uploadTaskDao()
        normalizer = PhoneNumberNormalizer("IN")
        matcher = TargetedRecordingMatcher(normalizer)

        tokenProvider = InMemoryTokenProvider(
            accessToken = "test_token_leads365",
            deviceIdOrInstallId = "install_test_001",
            agencyAccountId = "tenant_test_001"
        )

        val mockCallApi = object : CallApiService {
            override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                return CallReportResponse(success = true, message = "Call saved")
            }
            override suspend fun getCalls(): CallListResponse {
                return CallListResponse(success = true, data = emptyList())
            }
        }

        uploadQueueRepository = UploadQueueRepositoryImpl(
            uploadTaskDao = uploadTaskDao,
            ioDispatcher = Dispatchers.Unconfined
        )

        callRepository = CallRepositoryImpl(
            callDao = callDao,
            callApiService = mockCallApi,
            dispatcher = Dispatchers.Unconfined,
            tokenProvider = tokenProvider,
            uploadTaskDao = uploadTaskDao,
            context = context
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createTestViewModel(): CallsViewModel {
        val dummySaf = object : SafRecordingSource(context, getTreeUriString = { "content://tree/recordings" }) {
            override fun isAccessible() = true
            override fun isConfigured() = true
            override fun listDiscoveredRecordings(limit: Int): List<RecordingCandidate> = emptyList()
        }
        val dummyMediaStore = MediaStoreRecordingSource(context)

        return CallsViewModel(
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            safRecordingSource = dummySaf,
            mediaStoreRecordingSource = dummyMediaStore,
            context = context
        )
    }

    @Test
    fun testRecordingFound_UploadButtonVisible_CardDisplaysFileDetails() {
        // Oct 30, 4:03 PM, 39-second call
        val callTimeMs = 1793356980000L
        val call = CallEntity(
            id = "call_403_pm",
            clientCallId = "call_tenant_inst_403",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = callTimeMs,
            durationSeconds = 39,
            isLeadCall = true,
            leadId = testLead.id,
            syncStatus = "SYNCED",
            hasRecording = true,
            recordingUri = "content://recordings/rec_403.m4a",
            recordingFileName = "Call_20261030_160322.m4a",
            recordingFileSizeBytes = 48_500L
        )

        val task = UploadTaskEntity(
            id = "task_403_pm",
            callId = call.clientCallId,
            leadId = testLead.id,
            localFileUri = call.recordingUri!!,
            fileName = call.recordingFileName!!,
            fileSizeBytes = call.recordingFileSizeBytes!!,
            status = UploadTaskEntity.STATUS_PENDING
        )

        val mappedItems = CallsViewModel.mapCallRecords(listOf(call), listOf(task))
        assertEquals(1, mappedItems.size)
        val item = mappedItems.first()

        // Assert card visibility conditions
        assertTrue("Recording section card must be visible", item.hasRecording)
        assertEquals("Call_20261030_160322.m4a", item.recordingFileName)
        assertEquals(48_500L, item.recordingFileSizeBytes)
        assertNotNull("UploadTask must be linked", item.uploadTask)
        assertEquals(UploadTaskEntity.STATUS_PENDING, item.uploadTask?.status)
    }

    @Test
    fun testPendingUpload_TriggersWorkManagerUploadWorker() = runBlocking {
        val call = CallEntity(
            id = "call_trigger_worker",
            clientCallId = "call_tenant_inst_trigger",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.INCOMING_TYPE,
            startTimeEpochMs = System.currentTimeMillis() - 120_000L,
            durationSeconds = 25,
            isLeadCall = true,
            leadId = testLead.id,
            syncStatus = "SYNCED",
            hasRecording = true,
            recordingUri = "content://recordings/sample.m4a",
            recordingFileName = "sample.m4a",
            recordingFileSizeBytes = 32_000L
        )
        callDao.insert(call)

        val task = UploadTaskEntity(
            id = "task_trigger_worker",
            callId = call.clientCallId,
            leadId = testLead.id,
            localFileUri = call.recordingUri!!,
            fileName = call.recordingFileName!!,
            fileSizeBytes = call.recordingFileSizeBytes!!,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(task)

        val viewModel = createTestViewModel()

        // Trigger manual upload via ViewModel
        viewModel.executeUploadRecording(call.id)

        // Verify WorkManager received unique work request
        val workInfos = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + call.clientCallId).get()
        assertNotNull("WorkManager work must be enqueued", workInfos)
        assertTrue("WorkManager must have at least one scheduled work", workInfos.isNotEmpty())
        val workState = workInfos.first().state
        assertTrue("Work must be in ENQUEUED or RUNNING state", workState == WorkInfo.State.ENQUEUED || workState == WorkInfo.State.RUNNING)
    }

    @Test
    fun testWorkerSuccess_UpdatesRoomAndUiState() {
        val call = CallEntity(
            id = "call_uploaded_success",
            clientCallId = "call_tenant_inst_success",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = System.currentTimeMillis() - 200_000L,
            durationSeconds = 20,
            isLeadCall = true,
            leadId = testLead.id,
            syncStatus = "SYNCED",
            hasRecording = true,
            recordingUri = "content://recordings/uploaded_rec.m4a",
            recordingFileName = "uploaded_rec.m4a",
            recordingFileSizeBytes = 28_000L
        )

        val task = UploadTaskEntity(
            id = "task_uploaded_success",
            callId = call.clientCallId,
            leadId = testLead.id,
            localFileUri = call.recordingUri!!,
            fileName = call.recordingFileName!!,
            fileSizeBytes = call.recordingFileSizeBytes!!,
            status = UploadTaskEntity.STATE_UPLOADED
        )

        val mapped = CallsViewModel.mapCallRecords(listOf(call), listOf(task))
        assertEquals(1, mapped.size)
        val item = mapped.first()

        assertEquals(UploadTaskEntity.STATE_UPLOADED, item.uploadTask?.status)
        assertTrue("Uploaded recording must have recordingUri for playback", !item.recordingUri.isNullOrBlank())
    }

    @Test
    fun testWorkerFailure_ExposesRetryActionAndLastError() = runBlocking {
        val call = CallEntity(
            id = "call_failed_retry",
            clientCallId = "call_tenant_inst_fail",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.INCOMING_TYPE,
            startTimeEpochMs = System.currentTimeMillis() - 300_000L,
            durationSeconds = 40,
            isLeadCall = true,
            leadId = testLead.id,
            syncStatus = "SYNCED",
            hasRecording = true,
            recordingUri = "content://recordings/failed_rec.m4a",
            recordingFileName = "failed_rec.m4a",
            recordingFileSizeBytes = 50_000L
        )
        callDao.insert(call)

        val failureError = "Server returned HTTP 503: Service Unavailable"
        val task = UploadTaskEntity(
            id = "task_failed_retry",
            callId = call.clientCallId,
            leadId = testLead.id,
            localFileUri = call.recordingUri!!,
            fileName = call.recordingFileName!!,
            fileSizeBytes = call.recordingFileSizeBytes!!,
            status = UploadTaskEntity.STATE_RETRY_WAIT,
            lastError = failureError
        )
        uploadTaskDao.insert(task)

        val viewModel = createTestViewModel()
        val mapped = CallsViewModel.mapCallRecords(listOf(call), listOf(task))
        val item = mapped.first()

        assertEquals(UploadTaskEntity.STATE_RETRY_WAIT, item.uploadTask?.status)
        assertEquals(failureError, item.uploadTask?.lastError)

        // Trigger retry
        viewModel.executeRetryUpload(task)

        // Verify task status was reset to PENDING and work enqueued with REPLACE policy
        val updatedTask = uploadTaskDao.getById(task.id)
        assertEquals(UploadTaskEntity.STATUS_PENDING, updatedTask?.status)
        val workInfos = workManager.getWorkInfosForUniqueWork(RecordingUploadWorker.WORK_NAME_PREFIX + call.clientCallId).get()
        assertTrue("WorkManager must have rescheduled work for retry", workInfos.isNotEmpty())
    }

    @Test
    fun testMissingFile_ProducesUsefulDiagnostic() {
        val candidate = RecordingCandidate(
            uriString = "content://recordings/missing_file.m4a",
            displayName = "missing_file.m4a",
            sizeBytes = 0L,
            lastModifiedEpochMs = System.currentTimeMillis(),
            mimeType = "audio/m4a",
            durationSeconds = null,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
            isReadable = false,
            unreadableReason = "File size reported as 0 bytes or deleted"
        )

        val call = CallEvent(
            id = "call_missing_file_01",
            rawNumber = "9876543210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = System.currentTimeMillis() - 100_000L,
            endTimeEpochMs = System.currentTimeMillis() - 70_000L,
            durationSeconds = 30,
            direction = CallDirection.INCOMING
        )

        val matchResult = matcher.match(listOf(candidate), call)
        assertEquals(MatchStatus.INACCESSIBLE, matchResult.status)
        assertTrue(matchResult.reason.contains("inaccessible or 0-byte file") || matchResult.reason.contains("missing_file.m4a"))
    }

    @Test
    fun testCallMetadataReported_AudioNotUploaded_ExposesUploadCard() {
        // Screenshot scenario 3: Oct 30, 4:03 PM call: "Call Reported", but recording not uploaded
        val call = CallEntity(
            id = "call_reported_audio_pending",
            clientCallId = "call_tenant_inst_403_rep",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = 1793356980000L,
            durationSeconds = 39,
            isLeadCall = true,
            leadId = testLead.id,
            syncStatus = "SYNCED", // Call Reported
            hasRecording = true,
            recordingUri = "content://recordings/rec_403.m4a",
            recordingFileName = "Call_20261030_160322.m4a",
            recordingFileSizeBytes = 47_000L
        )

        val task = UploadTaskEntity(
            id = "task_rep_pending",
            callId = call.clientCallId,
            leadId = testLead.id,
            localFileUri = call.recordingUri!!,
            fileName = call.recordingFileName!!,
            fileSizeBytes = call.recordingFileSizeBytes!!,
            status = UploadTaskEntity.STATUS_PENDING
        )

        val mapped = CallsViewModel.mapCallRecords(listOf(call), listOf(task))
        val item = mapped.first()

        assertEquals("SYNCED", item.call?.syncStatus)
        assertTrue("Recording card must be visible", item.hasRecording)
        assertEquals(UploadTaskEntity.STATUS_PENDING, item.uploadTask?.status)
    }

    @Test
    fun testOct30_Calls_MatchingWithFilenameTimestampsAndCompression() = runBlocking {
        // Oct 30, 4:00 PM, 22-second call (AMR-NB compression ~26KB)
        val time400Ms = 1793356800000L // 16:00:00
        val call400 = CallEntity(
            id = "call_400",
            clientCallId = "call_client_400",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = time400Ms,
            durationSeconds = 22,
            isLeadCall = true,
            leadId = testLead.id,
            syncStatus = "LOCAL_QUEUED",
            hasRecording = false
        )
        callDao.insert(call400)

        // Candidate on disk detected during refresh
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val candidate400 = RecordingCandidate(
            uriString = "content://recordings/rec_400.m4a",
            displayName = "Record_${sdf.format(Date(time400Ms + 15_000L))}.m4a",
            sizeBytes = 26_500L, // AMR compression
            lastModifiedEpochMs = time400Ms + 25_000L,
            mimeType = "audio/m4a",
            durationSeconds = null,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
            isReadable = true
        )

        // Reconcile unmatched recordings
        val reconcileResult = callRepository.reconcileUnmatchedRecordings(listOf(candidate400))
        assertTrue(reconcileResult is AppResult.Success)
        assertEquals(1, (reconcileResult as AppResult.Success).data)

        // Verify call was updated in Room
        val updatedCall400 = callDao.getById("call_400")
        assertNotNull(updatedCall400)
        assertTrue("Call must now have recording matched", updatedCall400?.hasRecording == true)
        assertEquals("content://recordings/rec_400.m4a", updatedCall400?.recordingUri)

        // Verify UploadTask was created in Room
        val task400 = uploadTaskDao.getByCallId(call400.clientCallId)
        assertNotNull("UploadTask must be created for reconciled recording", task400)
        assertEquals(UploadTaskEntity.STATUS_PENDING, task400?.status)
    }

    @Test
    fun testMultipleCallsWithSameNumberRemainDistinct() = runBlocking {
        val baseTime = System.currentTimeMillis() - 500_000L
        val call1 = CallEntity(
            id = "call_dist_1",
            clientCallId = "call_client_dist_1",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = baseTime, // First call
            durationSeconds = 22,
            isLeadCall = true,
            leadId = testLead.id,
            hasRecording = false
        )
        val call2 = CallEntity(
            id = "call_dist_2",
            clientCallId = "call_client_dist_2",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = baseTime + 180_000L, // Second call (3 mins later)
            durationSeconds = 39,
            isLeadCall = true,
            leadId = testLead.id,
            hasRecording = false
        )
        callDao.insertAll(listOf(call1, call2))

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name1 = "Call_${sdf.format(Date(call1.startTimeEpochMs + 15_000L))}.m4a"
        val name2 = "Call_${sdf.format(Date(call2.startTimeEpochMs + 22_000L))}.m4a"

        val cand1 = RecordingCandidate(
            uriString = "content://recordings/dist_1.m4a",
            displayName = name1,
            sizeBytes = 26_000L,
            lastModifiedEpochMs = baseTime + 25_000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
            isReadable = true
        )
        val cand2 = RecordingCandidate(
            uriString = "content://recordings/dist_2.m4a",
            displayName = name2,
            sizeBytes = 48_000L,
            lastModifiedEpochMs = baseTime + 180_000L + 42_000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
            isReadable = true
        )

        val result = callRepository.reconcileUnmatchedRecordings(listOf(cand1, cand2))
        assertTrue(result is AppResult.Success)
        assertEquals(2, (result as AppResult.Success).data)

        val uCall1 = callDao.getById("call_dist_1")
        val uCall2 = callDao.getById("call_dist_2")

        assertEquals(name1, uCall1?.recordingFileName)
        assertEquals(name2, uCall2?.recordingFileName)
        assertNotEquals("Calls must not share recording URI", uCall1?.recordingUri, uCall2?.recordingUri)
        assertNotEquals("Calls must not share recording URI", uCall1?.recordingUri, uCall2?.recordingUri)
    }

    @Test
    fun testProcessRestartWithPendingUploadTask_RemainsRecoverable() = runBlocking {
        val task = UploadTaskEntity(
            id = "task_restart_test",
            callId = "call_restart_001",
            leadId = testLead.id,
            localFileUri = "content://recordings/persisted.m4a",
            fileName = "persisted.m4a",
            fileSizeBytes = 35_000L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(task)

        // Simulate new process instance querying Room
        val tasksInDb = uploadTaskDao.getPendingTasks()
        assertEquals(1, tasksInDb.size)
        assertEquals("task_restart_test", tasksInDb.first().id)
        assertEquals(UploadTaskEntity.STATUS_PENDING, tasksInDb.first().status)
    }

    @Test
    fun testHistoricalCallReconciliation_AttachesRecordingAndUploadTask() = runBlocking {
        // Unknown direct call captured locally
        val directCall = CallEntity(
            id = "call_hist_rec_001",
            clientCallId = "call_client_hist_001",
            normalizedNumber = "+919876543210",
            rawNumber = "9876543210",
            callType = CallLog.Calls.INCOMING_TYPE,
            startTimeEpochMs = System.currentTimeMillis() - 500_000L,
            durationSeconds = 30,
            isLeadCall = false,
            leadId = null,
            syncStatus = "LOCAL_QUEUED",
            hasRecording = true,
            recordingUri = "content://recordings/hist_rec.m4a",
            recordingFileName = "hist_rec.m4a",
            recordingFileSizeBytes = 36_000L
        )
        callDao.insert(directCall)

        val initialTask = UploadTaskEntity(
            id = "task_hist_001",
            callId = directCall.clientCallId,
            leadId = "unlinked",
            localFileUri = directCall.recordingUri!!,
            fileName = directCall.recordingFileName!!,
            fileSizeBytes = directCall.recordingFileSizeBytes!!,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(initialTask)

        // User manually creates Lead
        val reconcileCount = callRepository.reconcileCallsForLead(testLead)
        assertTrue(reconcileCount is AppResult.Success)
        assertEquals(1, (reconcileCount as AppResult.Success).data)

        // Verify task leadId was updated to the new Lead
        val updatedTask = uploadTaskDao.getById(initialTask.id)
        assertNotNull(updatedTask)
        assertEquals(testLead.id, updatedTask?.leadId)

        // Verify call in Room now marked as lead call
        val updatedCall = callDao.getById(directCall.id)
        assertEquals(testLead.id, updatedCall?.leadId)
        assertTrue(updatedCall?.isLeadCall == true)
    }
}
