package com.leads365.apk.recordings

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
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import com.leads365.apk.network.models.LeadSyncResponse
import kotlinx.coroutines.Dispatchers
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
class Phase7RecordingDiscoveryAndMatchingTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var callRepository: CallRepository
    private lateinit var leadRepository: LeadRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var matcher: TargetedRecordingMatcher

    private val testCallEvent = CallEvent(
        id = "call_test_001",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        startTimeEpochMs = 1700000000000L,
        endTimeEpochMs = 1700000060000L,
        durationSeconds = 60L,
        direction = CallDirection.OUTGOING
    )

    private val activeLead = LeadEntity(
        id = "lead_active_1",
        name = "Rajesh Sharma",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        status = "IN_PROGRESS",
        isDoNotRecord = false
    )

    private val dnrLead = LeadEntity(
        id = "lead_dnr_1",
        name = "VIP Client (DNR)",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        status = "FOLLOW_UP",
        isDoNotRecord = true
    )

    @Before
    fun setup() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
                .allowMainThreadQueries()
                .build()

            normalizer = PhoneNumberNormalizer("IN")
            matcher = TargetedRecordingMatcher(
                normalizer = normalizer,
                minAcceptanceScore = 0.55,
                ambiguityDeltaThreshold = 0.15
            )

            val mockCallApi = object : CallApiService {
                override suspend fun reportCall(request: CallReportRequest): CallReportResponse {
                    throw NotImplementedError()
                }
            }
            val mockLeadApi = object : LeadApiService {
                override suspend fun syncLeads(sinceTimestampEpochMs: Long?, limit: Int): LeadSyncResponse {
                    throw NotImplementedError()
                }
            }

            callRepository = CallRepositoryImpl(
                callDao = database.callDao(),
                callApiService = mockCallApi,
                dispatcher = Dispatchers.Unconfined
            )

            leadRepository = LeadRepositoryImpl(
                leadDao = database.leadDao(),
                leadApiService = mockLeadApi,
                context = context,
                normalizer = normalizer,
                dispatcher = Dispatchers.Unconfined
            )

            uploadQueueRepository = UploadQueueRepositoryImpl(
                uploadTaskDao = database.uploadTaskDao(),
                ioDispatcher = Dispatchers.Unconfined
            )

            // Prepopulate call in Room
            callRepository.recordCall(
                CallEntity(
                    id = testCallEvent.id,
                    clientCallId = testCallEvent.id,
                    normalizedNumber = testCallEvent.normalizedNumber,
                    rawNumber = testCallEvent.rawNumber,
                    callType = 2,
                    startTimeEpochMs = testCallEvent.startTimeEpochMs,
                    durationSeconds = testCallEvent.durationSeconds,
                    isLeadCall = true,
                    leadId = activeLead.id,
                    hasRecording = false
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test(timeout = 10_000)
    fun `privacy gate halts immediately on non-assigned lead and performs no scan`() = runBlocking {
        var safScanned = false
        var mediaStoreScanned = false

        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                safScanned = true
                return emptyList()
            }
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                mediaStoreScanned = true
                return emptyList()
            }
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val result = coordinator.discoverAndMatchRecording(
            callEvent = testCallEvent,
            lead = null
        )

        assertTrue("Result must be GatedNotLead", result is DiscoveryResult.GatedNotLead)
        assertFalse("SAF source must never be queried for non-lead calls", safScanned)
        assertFalse("MediaStore source must never be queried for non-lead calls", mediaStoreScanned)
    }

    @Test(timeout = 10_000)
    fun `privacy gate halts immediately on Do-Not-Record lead and performs no scan`() = runBlocking {
        var safScanned = false
        var mediaStoreScanned = false

        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                safScanned = true
                return emptyList()
            }
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                mediaStoreScanned = true
                return emptyList()
            }
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val result = coordinator.discoverAndMatchRecording(
            callEvent = testCallEvent,
            lead = dnrLead
        )

        assertTrue("Result must be GatedDoNotRecord", result is DiscoveryResult.GatedDoNotRecord)
        assertEquals(dnrLead.id, (result as DiscoveryResult.GatedDoNotRecord).leadId)
        assertFalse("SAF source must never be queried for DNR leads", safScanned)
        assertFalse("MediaStore source must never be queried for DNR leads", mediaStoreScanned)
    }

    @Test(timeout = 10_000)
    fun `unique matching candidate updates Room CallEntity and enqueues UploadTaskEntity`() = runBlocking {
        val candidate = RecordingCandidate(
            uriString = "content://saf/tree/call_9876543210_20261026.m4a",
            displayName = "Call_9876543210_20261026.m4a",
            sizeBytes = 240000L,
            lastModifiedEpochMs = testCallEvent.endTimeEpochMs + 5000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                return listOf(candidate)
            }
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String) = emptyList<RecordingCandidate>()
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val result = coordinator.discoverAndMatchRecording(
            callEvent = testCallEvent,
            lead = activeLead,
            callId = testCallEvent.id
        )

        assertTrue("Expected Matched result", result is DiscoveryResult.Matched)
        val matched = result as DiscoveryResult.Matched
        assertEquals(candidate.uriString, matched.candidate.uriString)
        assertEquals(1, matched.attemptsUsed)

        // Verify Room CallEntity was updated
        val updatedCall = (callRepository.findCallBySignature(testCallEvent.normalizedNumber, testCallEvent.startTimeEpochMs) as AppResult.Success).data
        assertNotNull(updatedCall)
        assertTrue("hasRecording must be true", updatedCall!!.hasRecording)
        assertEquals(candidate.uriString, updatedCall.recordingUri)
        assertEquals(candidate.displayName, updatedCall.recordingFileName)
        assertEquals(candidate.sizeBytes, updatedCall.recordingFileSizeBytes)

        // Verify UploadTaskEntity was enqueued
        val pendingTasks = (uploadQueueRepository.getPendingTasks() as AppResult.Success).data
        assertEquals(1, pendingTasks.size)
        val task = pendingTasks.first()
        assertEquals(testCallEvent.id, task.callId)
        assertEquals(activeLead.id, task.leadId)
        assertEquals(candidate.uriString, task.localFileUri)
        assertEquals(candidate.displayName, task.fileName)
        assertEquals(UploadTaskEntity.STATUS_PENDING, task.status)
    }

    @Test(timeout = 10_000)
    fun `ambiguous candidates below delta threshold trigger Ambiguous state and gate upload`() = runBlocking {
        // Two candidates with virtually identical high scores within ambiguity delta (0.15)
        val candidateA = RecordingCandidate(
            uriString = "content://saf/recording_A.m4a",
            displayName = "Call_9876543210_A.m4a",
            sizeBytes = 240000L,
            lastModifiedEpochMs = testCallEvent.endTimeEpochMs + 5000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val candidateB = RecordingCandidate(
            uriString = "content://saf/recording_B.m4a",
            displayName = "Call_9876543210_B.m4a",
            sizeBytes = 245000L,
            lastModifiedEpochMs = testCallEvent.endTimeEpochMs + 6000L,
            mimeType = "audio/m4a",
            durationSeconds = 59L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                return listOf(candidateA, candidateB)
            }
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String) = emptyList<RecordingCandidate>()
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val result = coordinator.discoverAndMatchRecording(
            callEvent = testCallEvent,
            lead = activeLead,
            callId = testCallEvent.id
        )

        assertTrue("Expected Ambiguous result", result is DiscoveryResult.Ambiguous)
        val ambiguous = result as DiscoveryResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)

        // Verify Room CallEntity has hasRecording = false
        val updatedCall = (callRepository.findCallBySignature(testCallEvent.normalizedNumber, testCallEvent.startTimeEpochMs) as AppResult.Success).data
        assertNotNull(updatedCall)
        assertFalse("hasRecording must remain false on ambiguous match", updatedCall!!.hasRecording)
        assertNull(updatedCall.recordingUri)

        // Verify upload task was NOT enqueued
        val pendingTasks = (uploadQueueRepository.getPendingTasks() as AppResult.Success).data
        assertTrue("No upload task should be enqueued for ambiguous candidates", pendingTasks.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `no candidate found sets hasRecording false and does not enqueue upload`() = runBlocking {
        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String) = emptyList<RecordingCandidate>()
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String) = emptyList<RecordingCandidate>()
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val result = coordinator.discoverAndMatchRecording(
            callEvent = testCallEvent,
            lead = activeLead,
            retryDelaysMs = listOf(10L, 20L),
            callId = testCallEvent.id
        )

        assertTrue("Expected NoFileFound result", result is DiscoveryResult.NoFileFound)
        val noFile = result as DiscoveryResult.NoFileFound
        assertEquals(3, noFile.attemptsUsed) // 1 initial + 2 retries

        val updatedCall = (callRepository.findCallBySignature(testCallEvent.normalizedNumber, testCallEvent.startTimeEpochMs) as AppResult.Success).data
        assertNotNull(updatedCall)
        assertFalse("hasRecording must be false", updatedCall!!.hasRecording)

        val pendingTasks = (uploadQueueRepository.getPendingTasks() as AppResult.Success).data
        assertTrue("No upload task should be enqueued when no file is found", pendingTasks.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `bounded retry discovers candidate appearing asynchronously on second attempt`() = runBlocking {
        var queryCount = 0

        val delayedCandidate = RecordingCandidate(
            uriString = "content://saf/tree/delayed_call_9876543210.m4a",
            displayName = "Call_9876543210_delayed.m4a",
            sizeBytes = 250000L,
            lastModifiedEpochMs = testCallEvent.endTimeEpochMs + 3000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                queryCount++
                return if (queryCount >= 2) {
                    listOf(delayedCandidate)
                } else {
                    emptyList() // First attempt returns empty, simulating OEM dialer flush delay
                }
            }
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String) = emptyList<RecordingCandidate>()
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val result = coordinator.discoverAndMatchRecording(
            callEvent = testCallEvent,
            lead = activeLead,
            retryDelaysMs = listOf(10L, 20L, 30L),
            callId = testCallEvent.id
        )

        assertTrue("Expected Matched result on retry", result is DiscoveryResult.Matched)
        val matched = result as DiscoveryResult.Matched
        assertEquals(2, matched.attemptsUsed)
        assertEquals(delayedCandidate.uriString, matched.candidate.uriString)

        // Verify Room updated
        val updatedCall = (callRepository.findCallBySignature(testCallEvent.normalizedNumber, testCallEvent.startTimeEpochMs) as AppResult.Success).data
        assertNotNull(updatedCall)
        assertTrue(updatedCall!!.hasRecording)
        assertEquals(delayedCandidate.displayName, updatedCall.recordingFileName)

        // Verify UploadTask enqueued
        val tasks = (uploadQueueRepository.getPendingTasks() as AppResult.Success).data
        assertEquals(1, tasks.size)
        assertEquals(delayedCandidate.uriString, tasks.first().localFileUri)
    }

    @Test(timeout = 10_000)
    fun `end-to-end CallProcessingManager coordinates discovery and persists matched recording`() = runBlocking {
        // Pre-insert lead into database
        database.leadDao().insert(activeLead)

        val matchedCandidate = RecordingCandidate(
            uriString = "content://saf/tree/e2e_call_9876543210.m4a",
            displayName = "Call_9876543210_e2e.m4a",
            sizeBytes = 280000L,
            lastModifiedEpochMs = testCallEvent.endTimeEpochMs + 2000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val testSaf = object : SafRecordingSource(context, getTreeUriString = { null }) {
            override fun isConfigured() = true
            override fun isAccessible() = true
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> {
                return listOf(matchedCandidate)
            }
        }

        val testMediaStore = object : MediaStoreRecordingSource(context) {
            override fun isAccessible() = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String) = emptyList<RecordingCandidate>()
        }

        val coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = testSaf,
            mediaStoreRecordingSource = testMediaStore,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = { /* zero delay */ }
        )

        val pendingCallManager = PendingCallManager(context, normalizer)
        val processingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = coordinator
        )

        val callEventE2E = CallEvent(
            id = "e2e_call_event_1",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = 1700100000000L,
            endTimeEpochMs = 1700100060000L,
            durationSeconds = 60L,
            direction = CallDirection.OUTGOING
        )

        val processResult = processingManager.processCallEvent(callEventE2E)
        assertTrue("Result must be Recorded", processResult is CallProcessingResult.Recorded)
        val recorded = processResult as CallProcessingResult.Recorded

        assertTrue("hasRecording must be true", recorded.call.hasRecording)
        assertEquals(matchedCandidate.uriString, recorded.call.recordingUri)
        assertEquals(matchedCandidate.displayName, recorded.call.recordingFileName)
        assertTrue("discoveryResult must be Matched", recorded.discoveryResult is DiscoveryResult.Matched)

        // Verify task enqueued in UploadQueueRepository
        val tasks = (uploadQueueRepository.getPendingTasks() as AppResult.Success).data
        val matchedTask = tasks.find { it.localFileUri == matchedCandidate.uriString }
        assertNotNull("Upload task must be present for matched call", matchedTask)
        assertEquals(UploadTaskEntity.STATUS_PENDING, matchedTask!!.status)
    }
}
