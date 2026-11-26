package com.leads365.apk.recordings

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.calls.CallLogDiagnosticResult
import com.leads365.apk.calls.WhatsAppCallAccessibilityService
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PermissionStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.CachedPolicy
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.diagnostics.DiagnosticsManager
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.auth.InMemoryTokenProvider
import com.leads365.apk.network.models.AutoLeadPolicyData
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import com.leads365.apk.network.models.PolicyData
import com.leads365.apk.ui.MainUiState
import com.leads365.apk.work.RecordingUploadWorker
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WhatsAppVoipPipelineTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var callRepository: CallRepositoryImpl
    private lateinit var uploadQueueRepository: UploadQueueRepositoryImpl
    private lateinit var tokenProvider: InMemoryTokenProvider
    private lateinit var policyRepository: FakePolicyRepository
    private lateinit var scheduler: RecordingWorkManagerScheduler
    private lateinit var enqueueHelper: VoipCallEnqueueHelper
    private lateinit var voipDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()

        tokenProvider = InMemoryTokenProvider(
            accessToken = "test_access_token",
            deviceIdOrInstallId = "install_wa_01",
            agencyAccountId = "tenant_wa_01",
            activeWorkspaceId = "ws_wa_01"
        )

        val mockCallApi = object : CallApiService {
            override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                return CallReportResponse(success = true)
            }
        }

        callRepository = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = mockCallApi,
            dispatcher = Dispatchers.Unconfined,
            tokenProvider = tokenProvider,
            uploadTaskDao = database.uploadTaskDao(),
            recordingDiscoveryCoordinator = null,
            context = context
        )

        uploadQueueRepository = UploadQueueRepositoryImpl(
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined
        )

        policyRepository = FakePolicyRepository()
        scheduler = RecordingWorkManagerScheduler(context, WorkManager.getInstance(context))

        enqueueHelper = VoipCallEnqueueHelper(
            context = context,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            leadDao = database.leadDao(),
            policyRepository = policyRepository,
            tokenProvider = tokenProvider,
            workManagerScheduler = scheduler,
            phoneNumberNormalizer = PhoneNumberNormalizer("IN")
        )

        voipDir = File(context.filesDir, "voip_recordings").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        voipDir.listFiles()?.forEach { it.delete() }
        database.close()
    }

    @Test
    fun `RecordingUploadManager openStream and inspectStream read file URI with exact bytes and SHA256`() {
        val payload = ByteArray(4096) { idx -> (idx % 127).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val tempFile = File(voipDir, "WA_stream_test.m4a").apply { writeBytes(payload) }
        val fileUri = Uri.fromFile(tempFile).toString()

        val manager = RecordingUploadManager(
            context = context,
            okHttpClient = OkHttpClient(),
            tokenProvider = tokenProvider,
            baseUrl = "https://example.invalid/api"
        )

        manager.openStream(fileUri).use { stream ->
            assertNotNull("openStream must open file:// URI", stream)
            assertArrayEquals(payload, stream!!.readBytes())
        }

        val inspection = manager.inspectStream(fileUri)
        assertNotNull("inspectStream must succeed for file:// URI", inspection)
        assertTrue("SHA-256 must be non-empty", inspection!!.sha256Hex.isNotEmpty())
        assertEquals(expectedSha, inspection.sha256Hex)
        assertEquals(payload.size.toLong(), inspection.bytesRead)
    }

    @Test
    fun `VoipCallEnqueueHelper persists whatsapp_voip CallEntity, enqueues FILE_FOUND UploadTask, and schedules worker`() = runBlocking {
        database.leadDao().insert(
            LeadEntity(
                id = "lead_wa_1",
                name = "Rohan Sharma",
                rawNumber = "+91 98765 43210",
                normalizedNumber = "+919876543210",
                isDoNotRecord = false,
                workspaceId = "ws_wa_lead",
                tenantId = "tenant_wa_01"
            )
        )

        val audioFile = File(voipDir, "WA_Rohan_1732500000000.m4a").apply {
            writeBytes(ByteArray(4096) { 0x42 })
        }

        val result = enqueueHelper.enqueueVoipRecording(
            callerIdentifier = "Rohan Sharma",
            sourceApp = "com.whatsapp",
            file = audioFile,
            startTimeMs = 1732500000000L,
            durationSec = 45
        )

        assertTrue("enqueueVoipRecording should return true", result)

        val expectedCallId = "wa_voip_tenant_wa_01_install_wa_01_1732500000000"
        val savedCall = database.callDao().getByClientCallId(expectedCallId)
        assertNotNull("CallEntity must be persisted", savedCall)
        assertEquals("whatsapp_voip", savedCall!!.recordingOrigin)
        assertEquals("tenant_wa_01", savedCall.tenantId)
        assertEquals("ws_wa_lead", savedCall.workspaceId)
        assertEquals("lead_wa_1", savedCall.leadId)
        assertEquals("+919876543210", savedCall.normalizedNumber)
        assertEquals("Rohan Sharma", savedCall.rawNumber)
        assertTrue(savedCall.hasRecording)

        val savedTask = database.uploadTaskDao().getByCallId(expectedCallId)
        assertNotNull("UploadTaskEntity must be enqueued", savedTask)
        assertEquals(UploadTaskEntity.STATE_FILE_FOUND, savedTask!!.status)
        assertTrue("localFileUri must be a file:// URI", savedTask.localFileUri.startsWith("file://"))
        assertEquals("tenant_wa_01", savedTask.tenantId)
        assertEquals("ws_wa_lead", savedTask.workspaceId)

        assertEquals(1, scheduler.scheduledUploads.size)
        val scheduled = scheduler.scheduledUploads.single()
        assertEquals(savedTask.id, scheduled.taskId)
        assertEquals(savedTask.localFileUri, scheduled.fileUri)
        assertEquals(expectedCallId, scheduled.callId)
    }

    @Test
    fun `VoipCallEnqueueHelper deletes temp file and returns false when recording sync is paused`() = runBlocking {
        policyRepository.paused = true
        val audioFile = File(voipDir, "WA_paused.m4a").apply {
            writeBytes(ByteArray(4096) { 0x11 })
        }
        assertTrue(audioFile.exists())

        val result = enqueueHelper.enqueueVoipRecording(
            callerIdentifier = "+919876543210",
            sourceApp = "com.whatsapp",
            file = audioFile,
            startTimeMs = 1732500001000L,
            durationSec = 20
        )

        assertFalse("Must return false when recording sync is paused", result)
        assertFalse("Temp .m4a file must be deleted when paused", audioFile.exists())
        assertTrue("No call should be persisted when paused", database.callDao().getPendingSyncCalls().isEmpty())
        assertTrue("No upload should be scheduled when paused", scheduler.scheduledUploads.isEmpty())
    }

    @Test
    fun `VoipCallEnqueueHelper deletes temp file and returns false for Do-Not-Record lead`() = runBlocking {
        database.leadDao().insert(
            LeadEntity(
                id = "lead_dnr_1",
                name = "VIP DNR Contact",
                rawNumber = "+91 98111 22333",
                normalizedNumber = "+919811122333",
                isDoNotRecord = true,
                tenantId = "tenant_wa_01"
            )
        )

        val audioFile = File(voipDir, "WA_dnr.m4a").apply {
            writeBytes(ByteArray(4096) { 0x22 })
        }
        assertTrue(audioFile.exists())

        val result = enqueueHelper.enqueueVoipRecording(
            callerIdentifier = "+919811122333",
            sourceApp = "com.whatsapp.w4b",
            file = audioFile,
            startTimeMs = 1732500002000L,
            durationSec = 30
        )

        assertFalse("Must return false for DNR lead", result)
        assertFalse("Temp .m4a file must be deleted for DNR lead", audioFile.exists())
        assertTrue("No call should be persisted for DNR lead", database.callDao().getPendingSyncCalls().isEmpty())
        assertTrue("No upload should be scheduled for DNR lead", scheduler.scheduledUploads.isEmpty())
    }

    @Test
    fun `DiagnosticsManager exposes optional WhatsApp permissions without blocking canContinueToStep2`() {
        val diag = DiagnosticsManager(
            context = context,
            isSafConfigured = { false },
            isSafValid = { false },
            isMediaStoreAccessible = { false },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS }
        )

        val permissions = diag.checkPermissions()
        val recordAudioItem = permissions.find { it.id == "perm_record_audio" }
        val whatsappA11yItem = permissions.find { it.id == "perm_whatsapp_a11y" }

        assertNotNull("perm_record_audio must be present", recordAudioItem)
        assertNotNull("perm_whatsapp_a11y must be present", whatsappA11yItem)
        assertFalse("perm_record_audio must be optional (isMandatory = false)", recordAudioItem!!.isMandatory)
        assertFalse("perm_whatsapp_a11y must be optional (isMandatory = false)", whatsappA11yItem!!.isMandatory)

        // Simulate mandatory permissions granted while both WhatsApp permissions are NOT_GRANTED
        val simulatedPermissions = permissions.map { item ->
            when {
                item.isMandatory -> item.copy(status = PermissionStatus.GRANTED)
                item.id == "perm_record_audio" || item.id == "perm_whatsapp_a11y" ->
                    item.copy(status = PermissionStatus.NOT_GRANTED)
                else -> item
            }
        }
        val state = MainUiState(permissions = simulatedPermissions)
        assertTrue(
            "canContinueToStep2 must stay true when mandatory cellular permissions are granted and WhatsApp permissions are NOT_GRANTED",
            state.canContinueToStep2
        )
    }

    @Test
    fun `VoipRecordingService watchdog auto-stops only after sawCommunicationMode and enforces 44_1kHz 64kbps config`() {
        assertEquals(44_100, VoipRecordingService.AUDIO_SAMPLING_RATE)
        assertEquals(64_000, VoipRecordingService.AUDIO_ENCODING_BITRATE)
        assertEquals(45L * 1024L * 1024L, VoipRecordingService.MAX_FILE_SIZE_BYTES)
        assertEquals(45 * 60, VoipRecordingService.MAX_RECORDING_DURATION_SEC)
        assertEquals(1_500L, VoipRecordingService.SILENCE_CHECK_DELAY_MS)
        assertEquals(
            listOf(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                android.media.MediaRecorder.AudioSource.MIC,
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            ),
            VoipRecordingService.AUDIO_SOURCE_PRIORITY
        )
        assertEquals(1, VoipRecordingService.nextFallbackSourceIndex(currentIndex = 0, maxAmplitude = 0))
        assertEquals(2, VoipRecordingService.nextFallbackSourceIndex(currentIndex = 1, maxAmplitude = 0))
        assertNull(VoipRecordingService.nextFallbackSourceIndex(currentIndex = 2, maxAmplitude = 0))
        assertNull(VoipRecordingService.nextFallbackSourceIndex(currentIndex = 0, maxAmplitude = 512))

        // When OEM hides MODE_IN_COMMUNICATION (sawCommunicationMode == false), watchdog must NOT auto-stop even after 6s/60s
        for (sec in listOf(2, 6, 8, 60)) {
            val res = VoipRecordingService.evaluateWatchdogSample(
                audioMode = AudioManager.MODE_NORMAL,
                sawCommunicationMode = false,
                nonCommChecks = 0,
                elapsedSec = sec
            )
            assertFalse("Must not stop at ${sec}s when sawCommunicationMode == false", res.shouldStop)
            assertEquals(0, res.nonCommChecks)
        }

        // Active WhatsApp call sets MODE_IN_COMMUNICATION -> sawCommunicationMode = true
        val activeComm = VoipRecordingService.evaluateWatchdogSample(
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            sawCommunicationMode = false,
            nonCommChecks = 0,
            elapsedSec = 4
        )
        assertTrue(activeComm.sawCommunicationMode)
        assertEquals(0, activeComm.nonCommChecks)
        assertFalse(activeComm.shouldStop)

        // Transient 1-poll drop (2s) must not stop immediately
        val firstDrop = VoipRecordingService.evaluateWatchdogSample(
            audioMode = AudioManager.MODE_NORMAL,
            sawCommunicationMode = activeComm.sawCommunicationMode,
            nonCommChecks = activeComm.nonCommChecks,
            elapsedSec = 16
        )
        assertEquals(1, firstDrop.nonCommChecks)
        assertFalse(firstDrop.shouldStop)

        // Audio bouncing back to MODE_IN_COMMUNICATION resets nonCommChecks to 0
        val recovered = VoipRecordingService.evaluateWatchdogSample(
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            sawCommunicationMode = firstDrop.sawCommunicationMode,
            nonCommChecks = firstDrop.nonCommChecks,
            elapsedSec = 18
        )
        assertEquals(0, recovered.nonCommChecks)
        assertFalse(recovered.shouldStop)

        // Two consecutive MODE_NORMAL polls (4s) after sawCommunicationMode == true must auto-stop
        val stopCheck1 = VoipRecordingService.evaluateWatchdogSample(
            audioMode = AudioManager.MODE_NORMAL,
            sawCommunicationMode = recovered.sawCommunicationMode,
            nonCommChecks = recovered.nonCommChecks,
            elapsedSec = 20
        )
        val stopCheck2 = VoipRecordingService.evaluateWatchdogSample(
            audioMode = AudioManager.MODE_NORMAL,
            sawCommunicationMode = stopCheck1.sawCommunicationMode,
            nonCommChecks = stopCheck1.nonCommChecks,
            elapsedSec = 22
        )
        assertFalse(stopCheck1.shouldStop)
        assertTrue("Second consecutive MODE_NORMAL check must auto-stop capture", stopCheck2.shouldStop)

        // Hard 45-minute safety duration ceiling stops capture even if sawCommunicationMode == false
        val maxDurationStop = VoipRecordingService.evaluateWatchdogSample(
            audioMode = AudioManager.MODE_NORMAL,
            sawCommunicationMode = false,
            nonCommChecks = 0,
            elapsedSec = VoipRecordingService.MAX_RECORDING_DURATION_SEC
        )
        assertTrue("45-minute hard safety ceiling must auto-stop capture", maxDurationStop.shouldStop)
    }

    @Test
    fun `RecordingUploadWorker treats 413 50MB limit as permanent failure and cleans up managed VoIP file`() {
        // Must NEVER match "50" inside "50MB limit" as transient HTTP 50x
        assertFalse(
            "413 50MB limit must never be retried as transient",
            RecordingUploadWorker.isTransientFailure(
                statusCode = 413,
                reason = "File size (53300000 bytes) exceeds maximum permitted 50MB limit"
            )
        )
        assertFalse(
            "413 must always be permanent regardless of message text",
            RecordingUploadWorker.isTransientFailure(statusCode = 413, reason = "HTTP 500 text")
        )
        assertFalse(
            "50MB string without status code must never trigger retry",
            RecordingUploadWorker.isTransientFailure(statusCode = null, reason = "File exceeds 50MB limit")
        )

        // Explicit 5xx / network errors remain transient
        assertTrue(RecordingUploadWorker.isTransientFailure(500, "Internal Server Error"))
        assertTrue(RecordingUploadWorker.isTransientFailure(502, "Bad Gateway"))
        assertTrue(RecordingUploadWorker.isTransientFailure(503, "Service Unavailable"))
        assertTrue(RecordingUploadWorker.isTransientFailure(504, "Gateway Timeout"))
        assertTrue(RecordingUploadWorker.isTransientFailure(null, "Read timeout"))

        // Managed oversized file inside filesDir/voip_recordings is deleted; external files are untouched
        val managedOversized = File(voipDir, "WA_oversized.m4a").apply { writeBytes(ByteArray(32)) }
        val externalFile = File(context.cacheDir, "external_keep.m4a").apply { writeBytes(ByteArray(32)) }
        try {
            RecordingUploadWorker.deleteLocalVoipFileIfManaged(context, Uri.fromFile(managedOversized).toString())
            RecordingUploadWorker.deleteLocalVoipFileIfManaged(context, Uri.fromFile(externalFile).toString())

            assertFalse("Managed VoIP file must be deleted on 413 cleanup", managedOversized.exists())
            assertTrue("External file outside voip_recordings must never be deleted", externalFile.exists())
        } finally {
            externalFile.delete()
        }
    }

    @Test
    fun `WhatsAppCallAccessibilityService detects w4b variant end-call and in-call controls while rejecting chat rows`() {
        // Variant viewIds and clickable-parent exact "End" labels match
        assertTrue(
            WhatsAppCallAccessibilityService.matchesEndCallNode(
                viewId = "com.whatsapp.w4b:id/voip_hangup_action",
                text = null,
                contentDesc = null,
                isNodeOrParentClickable = false
            )
        )
        assertTrue(
            WhatsAppCallAccessibilityService.matchesEndCallNode(
                viewId = null,
                text = "End",
                contentDesc = null,
                isNodeOrParentClickable = true
            )
        )
        assertFalse(
            "Exact 'End' inside non-clickable node must not match",
            WhatsAppCallAccessibilityService.matchesEndCallNode(
                viewId = null,
                text = "End",
                contentDesc = null,
                isNodeOrParentClickable = false
            )
        )

        // In-call controls (mute, speaker, bluetooth, audio_route, call_controls, voip_call) match
        assertTrue(
            WhatsAppCallAccessibilityService.matchesInCallControlMarker(
                viewId = "com.whatsapp.w4b:id/footer_speaker_toggle",
                contentDesc = "Speaker",
                isNodeOrParentClickable = true
            )
        )
        assertTrue(
            WhatsAppCallAccessibilityService.matchesInCallControlMarker(
                viewId = "com.whatsapp.w4b:id/call_controls_container",
                contentDesc = null,
                isNodeOrParentClickable = false
            )
        )
        assertFalse(
            "Chat list muted indicator must not trigger in-call detection",
            WhatsAppCallAccessibilityService.matchesInCallControlMarker(
                viewId = "com.whatsapp.w4b:id/conversations_row_mute_indicator",
                contentDesc = "Muted",
                isNodeOrParentClickable = false
            )
        )

        // AccessibilityNodeInfo tree check with Speaker button even without standard end_call_btn ID
        val callControlNode = AccessibilityNodeInfo.obtain().apply {
            viewIdResourceName = "com.whatsapp.w4b:id/audio_route_button"
            contentDescription = "Bluetooth"
            isClickable = true
        }
        val chatTimestampNode = AccessibilityNodeInfo.obtain().apply {
            text = "17:21"
            isClickable = false
        }
        try {
            assertTrue(WhatsAppCallAccessibilityService.hasInCallControlsInTree(callControlNode))
            assertNull(WhatsAppCallAccessibilityService.findEndCallButtonInTree(chatTimestampNode))
            assertFalse(WhatsAppCallAccessibilityService.hasInCallControlsInTree(chatTimestampNode))
        } finally {
            callControlNode.recycle()
            chatTimestampNode.recycle()
        }
    }

    @Test
    fun `VoipCallEnqueueHelper persists INCOMING_TYPE and WhatsAppCallAccessibilityService classifies incoming vs outgoing cues`() = runBlocking {
        // 1. Accessibility direction cue classification
        assertEquals(
            android.provider.CallLog.Calls.INCOMING_TYPE,
            WhatsAppCallAccessibilityService.classifyCallDirectionText("Incoming voice call", null)
        )
        assertEquals(
            android.provider.CallLog.Calls.INCOMING_TYPE,
            WhatsAppCallAccessibilityService.classifyCallDirectionText(null, "WhatsApp Business voice call")
        )
        assertEquals(
            android.provider.CallLog.Calls.INCOMING_TYPE,
            WhatsAppCallAccessibilityService.classifyCallDirectionText("Swipe up to accept", null)
        )
        assertEquals(
            android.provider.CallLog.Calls.INCOMING_TYPE,
            WhatsAppCallAccessibilityService.classifyCallDirectionText("Answer", "Decline")
        )
        assertEquals(
            android.provider.CallLog.Calls.OUTGOING_TYPE,
            WhatsAppCallAccessibilityService.classifyCallDirectionText("Calling…", null)
        )
        assertEquals(
            android.provider.CallLog.Calls.OUTGOING_TYPE,
            WhatsAppCallAccessibilityService.classifyCallDirectionText("Ringing", null)
        )

        val incomingTreeNode = AccessibilityNodeInfo.obtain().apply {
            text = "WhatsApp Business voice call"
            contentDescription = "Ringing"
        }
        try {
            assertEquals(
                "Incoming cues must win over generic Ringing cue",
                android.provider.CallLog.Calls.INCOMING_TYPE,
                WhatsAppCallAccessibilityService.detectCallDirectionInTree(incomingTreeNode)
            )
        } finally {
            incomingTreeNode.recycle()
        }

        // 2. VoipCallEnqueueHelper persists INCOMING_TYPE when detected
        val audioFile = File(voipDir, "WA_incoming_1732600000000.m4a").apply {
            writeBytes(ByteArray(4096) { 0x55 })
        }
        val enqueued = enqueueHelper.enqueueVoipRecording(
            callerIdentifier = "+919876500111",
            sourceApp = "com.whatsapp.w4b",
            file = audioFile,
            startTimeMs = 1732600000000L,
            durationSec = 32,
            callType = android.provider.CallLog.Calls.INCOMING_TYPE
        )
        assertTrue("Incoming WhatsApp call must enqueue cleanly", enqueued)

        val expectedCallId = "wa_voip_tenant_wa_01_install_wa_01_1732600000000"
        val savedIncoming = database.callDao().getByClientCallId(expectedCallId)
        assertNotNull(savedIncoming)
        assertEquals(android.provider.CallLog.Calls.INCOMING_TYPE, savedIncoming!!.callType)
        assertEquals("whatsapp_voip", savedIncoming.recordingOrigin)
    }

    @Test
    fun `WhatsAppAccessibilityHelper retains last 12 timestamped diagnostic events`() {
        context.getSharedPreferences("com.leads365.apk.preferences", Context.MODE_PRIVATE)
            .edit()
            .remove("pref_whatsapp_diag_log")
            .commit()

        for (i in 1..15) {
            com.leads365.apk.calls.WhatsAppAccessibilityHelper.appendDiagEvent(context, "Event #$i")
        }

        val events = com.leads365.apk.calls.WhatsAppAccessibilityHelper.getDiagEvents(context)
        assertEquals("Only last 12 diagnostic events must be retained", 12, events.size)
        assertTrue(events.first().endsWith("Event #4"))
        assertTrue(events.last().endsWith("Event #15"))
        assertTrue(
            "Diagnostic entries must begin with HH:mm:ss timestamp",
            Regex("""^\d{2}:\d{2}:\d{2} Event #\d+$""").matches(events.last())
        )
    }

    private data class ScheduledUpload(
        val taskId: String,
        val fileUri: String,
        val callId: String?
    )

    private class RecordingWorkManagerScheduler(
        context: Context,
        workManager: WorkManager
    ) : WorkManagerScheduler(context, workManager) {
        val scheduledUploads = mutableListOf<ScheduledUpload>()

        override fun scheduleUploadTask(
            taskId: String,
            fileUri: String,
            callId: String?,
            policy: ExistingWorkPolicy
        ) {
            scheduledUploads.add(ScheduledUpload(taskId, fileUri, callId))
        }
    }

    private class FakePolicyRepository : PolicyRepository {
        var paused: Boolean = false
        private val _policyState = MutableStateFlow(CachedPolicy())
        override val policyState: StateFlow<CachedPolicy> = _policyState
        private val _consentAcknowledged = MutableStateFlow(true)
        override val consentAcknowledged: StateFlow<Boolean> = _consentAcknowledged

        override suspend fun syncPolicy(): AppResult<PolicyData> =
            AppResult.Success(PolicyData())

        override suspend fun setPauseSyncState(paused: Boolean): AppResult<Boolean> {
            this.paused = paused
            return AppResult.Success(paused)
        }

        override suspend fun setAutoLeadPolicy(
            enabled: Boolean?,
            minDurationSeconds: Int?,
            tenantId: String?
        ): AppResult<AutoLeadPolicyData> = AppResult.Success(AutoLeadPolicyData())

        override fun acknowledgeConsent() {
            _consentAcknowledged.value = true
        }

        override fun isConsentAcknowledged(): Boolean = true
        override fun isRecordingSyncPaused(): Boolean = paused
        override fun isStrictDnrEnforced(): Boolean = true
    }
}
