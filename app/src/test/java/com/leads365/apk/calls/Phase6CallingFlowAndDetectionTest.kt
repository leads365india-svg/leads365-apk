package com.leads365.apk.calls

import android.content.Context
import android.provider.CallLog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.models.CallReportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase6CallingFlowAndDetectionTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var leadDao: LeadDao
    private lateinit var callDao: CallDao
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var leadRepository: LeadRepository
    private lateinit var callRepository: CallRepository
    private lateinit var pendingCallManager: PendingCallManager
    private lateinit var callProcessingManager: CallProcessingManager
    private lateinit var callApiService: CallApiService

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        leadDao = database.leadDao()
        callDao = database.callDao()

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/api/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val leadApiService = retrofit.create(LeadApiService::class.java)
        callApiService = retrofit.create(CallApiService::class.java)
        normalizer = PhoneNumberNormalizer("IN")

        leadRepository = LeadRepositoryImpl(
            leadDao = leadDao,
            leadApiService = leadApiService,
            context = context,
            normalizer = normalizer,
            dispatcher = Dispatchers.Unconfined
        )

        val tokenProvider = com.leads365.apk.network.auth.InMemoryTokenProvider(
            accessToken = "test_token",
            refreshToken = "test_refresh",
            deviceIdOrInstallId = "inst_test_01",
            agencyAccountId = "tenant_test_01"
        )

        callRepository = CallRepositoryImpl(
            callDao = callDao,
            callApiService = callApiService,
            dispatcher = Dispatchers.Unconfined,
            tokenProvider = tokenProvider
        )

        pendingCallManager = PendingCallManager(context, normalizer)
        pendingCallManager.clearPendingCall()

        callProcessingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = null,
            tokenProvider = tokenProvider
        )
    }

    @After
    fun teardown() {
        pendingCallManager.clearPendingCall()
        database.close()
        mockWebServer.shutdown()
    }

    @Test
    fun `outgoing call flow stores pending call context and consumes it when call completes`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_outgoing_1",
            name = "Anil Kapoor",
            rawNumber = "09876543210",
            normalizedNumber = "+919876543210",
            status = "New"
        )
        leadDao.insert(lead)

        // Step 1: User initiates call in UI -> PendingCallManager records intent
        pendingCallManager.setPendingCall(lead)

        val pending = pendingCallManager.getPendingCall()
        assertNotNull(pending)
        assertEquals("lead_outgoing_1", pending?.leadId)
        assertEquals("+919876543210", pending?.normalizedNumber)

        // Step 2: Telephony/CallLog detects completed outgoing call
        val callEvent = CallEvent(
            id = "sys_call_out_1",
            rawNumber = "9876543210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = System.currentTimeMillis() - 45_000L,
            endTimeEpochMs = System.currentTimeMillis(),
            durationSeconds = 45L,
            direction = CallDirection.OUTGOING,
            simId = "SIM1"
        )

        val result = callProcessingManager.processCallEvent(callEvent)
        assertTrue("Eligible outgoing call must be recorded", result is CallProcessingResult.Recorded)

        val recorded = (result as CallProcessingResult.Recorded)
        assertEquals("lead_outgoing_1", recorded.lead.id)
        assertEquals(45L, recorded.call.durationSeconds)
        assertEquals(CallLog.Calls.OUTGOING_TYPE, recorded.call.callType)
        assertEquals("SIM1", recorded.call.simId)
        assertFalse("Pending call must not claim recording exists before discovery", recorded.call.hasRecording)

        // Step 3: Pending call context must be consumed
        assertNull("Pending call context must be cleared after call processing", pendingCallManager.getPendingCall())

        // Step 4: Verify record persisted in Room
        val saved = callDao.getById(recorded.call.id)
        assertNotNull(saved)
        assertEquals("+919876543210", saved?.normalizedNumber)
        assertEquals("PENDING", saved?.syncStatus)
    }

    @Test
    fun `incoming call matching assigned lead is recorded with clientCallId`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_inc_1",
            name = "Priya Sharma",
            rawNumber = "+91 98765 11111",
            normalizedNumber = "+919876511111",
            status = "Engaged"
        )
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "sys_inc_1",
            rawNumber = "+919876511111",
            normalizedNumber = "+919876511111",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000060000L,
            durationSeconds = 60L,
            direction = CallDirection.INCOMING
        )

        val result = callProcessingManager.processCallEvent(callEvent)
        assertTrue(result is CallProcessingResult.Recorded)

        val recorded = (result as CallProcessingResult.Recorded)
        assertEquals("lead_inc_1", recorded.call.leadId)
        assertTrue("clientCallId must be non-empty UUID", recorded.call.clientCallId.isNotBlank())
        assertEquals(CallLog.Calls.INCOMING_TYPE, recorded.call.callType)
        assertTrue(recorded.call.isLeadCall)

        // Verify Flow emits the call
        val leadCalls = callRepository.getLeadCallsFlow().first()
        assertEquals(1, leadCalls.size)
        assertEquals(recorded.call.id, leadCalls[0].id)
    }

    @Test
    fun `missed and rejected calls are recorded with Missed outcome without audio recording flag`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_missed_1",
            name = "Ramesh Verma",
            rawNumber = "+91 98765 22222",
            normalizedNumber = "+919876522222",
            status = "New"
        )
        leadDao.insert(lead)

        val missedCall = CallEvent(
            id = "sys_missed_1",
            rawNumber = "+919876522222",
            normalizedNumber = "+919876522222",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000000000L,
            durationSeconds = 0L,
            direction = CallDirection.MISSED
        )

        val result = callProcessingManager.processCallEvent(missedCall)
        assertTrue(result is CallProcessingResult.Recorded)

        val recorded = (result as CallProcessingResult.Recorded)
        assertEquals("Missed", recorded.call.callOutcome)
        assertEquals(0L, recorded.call.durationSeconds)
        assertFalse("Missed calls must never claim audio recording", recorded.call.hasRecording)
        assertNull(recorded.call.recordingUri)
    }

    @Test
    fun `non-lead call is blocked before recording lookup and reporting`() = runBlocking {
        // Only lead in Room is lead_1
        leadDao.insert(LeadEntity(id = "l1", name = "Test", rawNumber = "9876500001", normalizedNumber = "+919876500001"))

        // Call from unknown non-lead number (e.g. personal outgoing call)
        val nonLeadCall = CallEvent(
            id = "sys_stranger_1",
            rawNumber = "+91 91111 22222",
            normalizedNumber = "+919111122222",
            startTimeEpochMs = System.currentTimeMillis() - 20_000L,
            endTimeEpochMs = System.currentTimeMillis(),
            durationSeconds = 20L,
            direction = CallDirection.OUTGOING
        )

        val result = callProcessingManager.processCallEvent(nonLeadCall)
        assertTrue("Non-lead call must be rejected", result is CallProcessingResult.RejectedNotALead)

        // Strict privacy rule: Room calls table must remain completely empty
        assertEquals(0, callDao.count())
    }

    @Test
    fun `DNR call is blocked before recording lookup and reporting`() = runBlocking {
        // Lead marked with Do-Not-Record = true
        val dnrLead = LeadEntity(
            id = "lead_dnr_1",
            name = "VIP Client (Do Not Record)",
            rawNumber = "+91 98765 33333",
            normalizedNumber = "+919876533333",
            isDoNotRecord = true,
            status = "Engaged"
        )
        leadDao.insert(dnrLead)

        val dnrCall = CallEvent(
            id = "sys_dnr_1",
            rawNumber = "+919876533333",
            normalizedNumber = "+919876533333",
            startTimeEpochMs = System.currentTimeMillis() - 30_000L,
            endTimeEpochMs = System.currentTimeMillis(),
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        val result = callProcessingManager.processCallEvent(dnrCall)
        assertTrue("DNR call must be rejected at gate", result is CallProcessingResult.RejectedDoNotRecord)

        // DNR calls must NOT create a recording or reportable record
        assertEquals(0, callDao.count())
    }

    @Test
    fun `phone number normalization across formats matches assigned lead`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_norm_1",
            name = "Kavita Reddy",
            rawNumber = "+91 98765 44444",
            normalizedNumber = "+919876544444",
            status = "Proposal Sent"
        )
        leadDao.insert(lead)

        // Call log reports local 10-digit number without country code
        val rawCall = CallEvent(
            id = "call_local_fmt",
            rawNumber = "09876544444",
            normalizedNumber = "9876544444",
            startTimeEpochMs = System.currentTimeMillis() - 15_000L,
            endTimeEpochMs = System.currentTimeMillis(),
            durationSeconds = 15L,
            direction = CallDirection.OUTGOING
        )

        val result = callProcessingManager.processCallEvent(rawCall)
        assertTrue(result is CallProcessingResult.Recorded)

        val recorded = (result as CallProcessingResult.Recorded)
        assertEquals("lead_norm_1", recorded.lead.id)
        assertEquals("+919876544444", recorded.call.normalizedNumber)
    }

    @Test
    fun `duplicate event handling is idempotent and prevents duplicate local calls`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_dup_1",
            name = "Deepak Joshi",
            rawNumber = "+91 98765 55555",
            normalizedNumber = "+919876555555",
            status = "New"
        )
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "call_shared_1",
            rawNumber = "+919876555555",
            normalizedNumber = "+919876555555",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000030000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        // Event from TelephonyCallback
        val res1 = callProcessingManager.processCallEvent(callEvent)
        assertTrue("First event must be recorded", res1 is CallProcessingResult.Recorded)
        assertEquals(1, callDao.count())

        // Duplicate event from BroadcastReceiver / onResume
        val res2 = callProcessingManager.processCallEvent(callEvent)
        assertTrue("Second event must be detected as duplicate", res2 is CallProcessingResult.DuplicateIgnored)
        assertEquals("Database must still contain exactly 1 call record", 1, callDao.count())
    }

    @Test
    fun `post-call outcome update modifies Room CallEntity and LeadEntity`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_outcome_1",
            name = "Sunil Gavaskar",
            rawNumber = "+91 98765 66666",
            normalizedNumber = "+919876566666",
            status = "New",
            notes = "Initial lead"
        )
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "call_outcome_evt",
            rawNumber = "+919876566666",
            normalizedNumber = "+919876566666",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000090000L,
            durationSeconds = 90L,
            direction = CallDirection.OUTGOING
        )

        val processResult = callProcessingManager.processCallEvent(callEvent)
        assertTrue(processResult is CallProcessingResult.Recorded)
        val callId = (processResult as CallProcessingResult.Recorded).call.id

        // User fills Post-Call Outcome screen
        val followUpMs = System.currentTimeMillis() + 86_400_000L
        callRepository.updateOutcome(
            callId = callId,
            outcome = "Proposal Sent",
            notes = "Client requested detailed brochure",
            nextFollowUpEpochMs = followUpMs
        )

        val updatedCall = callDao.getById(callId)
        assertNotNull(updatedCall)
        assertEquals("Proposal Sent", updatedCall?.callOutcome)
        assertEquals("Client requested detailed brochure", updatedCall?.notes)
        assertEquals(followUpMs, updatedCall?.nextFollowUpEpochMs)
        assertEquals("PENDING", updatedCall?.syncStatus)
    }

    @Test
    fun `call reporting to backend with clientCallId succeeds and updates syncStatus to SYNCED`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_sync_1",
            name = "Ananya Roy",
            rawNumber = "+91 98765 77777",
            normalizedNumber = "+919876577777",
            status = "New"
        )
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "call_srv_sync",
            rawNumber = "+919876577777",
            normalizedNumber = "+919876577777",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000050000L,
            durationSeconds = 50L,
            direction = CallDirection.OUTGOING,
            simId = "SIM2_JIO"
        )

        val processResult = callProcessingManager.processCallEvent(callEvent)
        val callId = (processResult as CallProcessingResult.Recorded).call.id

        // Setup mock backend 200 response
        val responseJson = json.encodeToString(
            CallReportResponse(
                success = true,
                callId = "srv_call_456",
                message = "Call report received successfully"
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val reportResult = callRepository.reportCallToBackend(callId)
        assertTrue(reportResult is AppResult.Success)

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.path?.contains("/api/mobile/v1/calls") == true)
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.body.readUtf8().contains("client_call_id"))

        val syncedCall = callDao.getById(callId)
        assertEquals("SYNCED", syncedCall?.syncStatus)
        assertNull(syncedCall?.syncErrorMessage)
    }

    @Test
    fun `call reporting network failure preserves offline call record in Room with PENDING status`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_off_1",
            name = "Offline Lead",
            rawNumber = "+91 98765 88888",
            normalizedNumber = "+919876588888",
            status = "Engaged"
        )
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "call_offline_evt",
            rawNumber = "+919876588888",
            normalizedNumber = "+919876588888",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000020000L,
            durationSeconds = 20L,
            direction = CallDirection.INCOMING
        )

        val processResult = callProcessingManager.processCallEvent(callEvent)
        val callId = (processResult as CallProcessingResult.Recorded).call.id

        // Simulate 503 backend error
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val reportResult = callRepository.reportCallToBackend(callId)
        assertTrue("Backend failure must return AppResult.Failure", reportResult is AppResult.Failure)

        // Call MUST remain safely preserved in Room database!
        val preservedCall = callDao.getById(callId)
        assertNotNull("Call must not be deleted on network failure", preservedCall)
        assertEquals("FAILED", preservedCall?.syncStatus)
        assertNotNull(preservedCall?.syncErrorMessage)
    }

    @Test
    fun `pending call context survives process restart and expires after window`() {
        val lead = LeadEntity(
            id = "lead_restart_1",
            name = "Persistent Lead",
            rawNumber = "9876500009",
            normalizedNumber = "+919876500009"
        )
        pendingCallManager.setPendingCall(lead)

        // Create new PendingCallManager simulating new app process reading from SharedPreferences
        val newManager = PendingCallManager(context, normalizer)
        val recovered = newManager.getPendingCall()
        assertNotNull(recovered)
        assertEquals("lead_restart_1", recovered?.leadId)
        assertEquals("+919876500009", recovered?.normalizedNumber)

        // Expire test: windowMs = 0L -> must not match
        val expired = newManager.consumePendingCallForNumber("+919876500009", windowMs = 0L)
        assertNull(expired)
    }

    @Test
    fun `normal missed call saved and synced immediately without recording`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_missed_direct_1",
            name = "Vikas Khanna",
            rawNumber = "+91 98765 99001",
            normalizedNumber = "+919876599001",
            status = "New"
        )
        leadDao.insert(lead)

        val missedCall = CallEvent(
            id = "sys_missed_direct_1",
            rawNumber = "+919876599001",
            normalizedNumber = "+919876599001",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000000000L,
            durationSeconds = 0L,
            direction = CallDirection.MISSED,
            callType = CallLog.Calls.MISSED_TYPE
        )

        // Step 1: Call processing manager processes missed call
        val result = callProcessingManager.processCallEvent(missedCall)
        assertTrue("Missed call for lead must be recorded", result is CallProcessingResult.Recorded)

        val recorded = (result as CallProcessingResult.Recorded)
        assertEquals("lead_missed_direct_1", recorded.call.leadId)
        assertEquals("Missed", recorded.call.callOutcome)
        assertEquals(0L, recorded.call.durationSeconds)
        assertEquals(CallLog.Calls.MISSED_TYPE, recorded.call.callType)
        assertFalse("Missed call must never have recording", recorded.call.hasRecording)
        assertNull(recorded.call.recordingUri)

        // Step 2: Immediate reporting to backend
        val responseJson = json.encodeToString(
            CallReportResponse(
                success = true,
                callId = "srv_call_missed_1",
                message = "Call reported successfully"
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val reportResult = callRepository.reportCallToBackend(recorded.call.id)
        assertTrue(reportResult is AppResult.Success)

        // Step 3: Verify backend request payload contains MISSED direction and outcome
        val recordedRequest = mockWebServer.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"call_direction\":\"MISSED\""))
        assertTrue(requestBody.contains("\"call_outcome\":\"Missed\""))
        assertTrue(requestBody.contains("\"lead_id\":\"lead_missed_direct_1\""))

        // Step 4: Verify Room DB status is SYNCED
        val saved = callDao.getById(recorded.call.id)
        assertNotNull(saved)
        assertEquals("SYNCED", saved?.syncStatus)
        assertEquals("Missed", saved?.callOutcome)
    }

    @Test
    fun `call-waiting missed call during active offhook call is captured and synced`() = runBlocking {
        val leadA = LeadEntity(
            id = "lead_active_call_a",
            name = "Call A Client",
            rawNumber = "+91 98765 10001",
            normalizedNumber = "+919876510001",
            status = "Engaged"
        )
        val leadB = LeadEntity(
            id = "lead_waiting_call_b",
            name = "Call B Client (Waiting)",
            rawNumber = "+91 98765 20002",
            normalizedNumber = "+919876520002",
            status = "New"
        )
        leadDao.insert(leadA)
        leadDao.insert(leadB)

        val callAStart = 1700000000000L
        val callAEnd = 1700000120000L // 120s duration
        val callBStart = 1700000045000L // Call waiting arrives 45s into Call A

        val callEventA = CallEvent(
            id = "call_a_active",
            rawNumber = "+919876510001",
            normalizedNumber = "+919876510001",
            startTimeEpochMs = callAStart,
            endTimeEpochMs = callAEnd,
            durationSeconds = 120L,
            direction = CallDirection.OUTGOING,
            callType = CallLog.Calls.OUTGOING_TYPE
        )

        val callEventB = CallEvent(
            id = "call_b_waiting_missed",
            rawNumber = "+919876520002",
            normalizedNumber = "+919876520002",
            startTimeEpochMs = callBStart,
            endTimeEpochMs = callBStart,
            durationSeconds = 0L,
            direction = CallDirection.MISSED,
            callType = CallLog.Calls.MISSED_TYPE
        )

        // When Call A ends, CallLogReader.findRecentCalls retrieves both Call A and Call Waiting Call B
        // Step 1: Process Call A
        val resA = callProcessingManager.processCallEvent(callEventA)
        assertTrue("Active call A must be recorded", resA is CallProcessingResult.Recorded)
        val recordedA = (resA as CallProcessingResult.Recorded).call
        assertEquals("lead_active_call_a", recordedA.leadId)
        assertEquals(120L, recordedA.durationSeconds)

        // Step 2: Process Call B (Call Waiting missed call)
        val resB = callProcessingManager.processCallEvent(callEventB)
        assertTrue("Call waiting missed call B must be recorded", resB is CallProcessingResult.Recorded)
        val recordedB = (resB as CallProcessingResult.Recorded).call
        assertEquals("lead_waiting_call_b", recordedB.leadId)
        assertEquals(0L, recordedB.durationSeconds)
        assertEquals("Missed", recordedB.callOutcome)
        assertFalse(recordedB.hasRecording)

        // Verify both calls exist in Room DB
        assertEquals(2, callDao.count())

        // Step 3: Both calls report to backend
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(
            CallReportResponse(success = true, callId = "srv_call_a", message = "Call A ok")
        )))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(
            CallReportResponse(success = true, callId = "srv_call_b", message = "Call B ok")
        )))

        val reportA = callRepository.reportCallToBackend(recordedA.id)
        val reportB = callRepository.reportCallToBackend(recordedB.id)

        assertTrue(reportA is AppResult.Success)
        assertTrue(reportB is AppResult.Success)

        // Verify Call B backend request
        val reqA = mockWebServer.takeRequest()
        val reqB = mockWebServer.takeRequest()

        val reqBBody = reqB.body.readUtf8()
        assertTrue(reqBBody.contains("\"call_direction\":\"MISSED\""))
        assertTrue(reqBBody.contains("\"call_outcome\":\"Missed\""))
        assertTrue(reqBBody.contains("\"lead_id\":\"lead_waiting_call_b\""))

        // Step 4: Secondary duplicate run of Call B is properly ignored by deduplication
        val resBDup = callProcessingManager.processCallEvent(callEventB)
        assertTrue("Duplicate of Call B must be ignored", resBDup is CallProcessingResult.DuplicateIgnored)
        assertEquals(2, callDao.count())
    }

    @Test
    fun `call-waiting missed call from unknown number is captured as unlinked and synced with autoCreateLead`() = runBlocking {
        val unknownMissedCall = CallEvent(
            id = "sys_waiting_unknown_1",
            rawNumber = "+919999888877",
            normalizedNumber = "+919999888877",
            startTimeEpochMs = 1700000050000L,
            endTimeEpochMs = 1700000050000L,
            durationSeconds = 0L,
            direction = CallDirection.MISSED,
            callType = CallLog.Calls.MISSED_TYPE
        )

        val result = callProcessingManager.processCallEvent(unknownMissedCall)
        assertTrue("Unknown missed call must be captured as unlinked inbound", result is CallProcessingResult.UnlinkedIncomingRecorded)

        val unlinked = (result as CallProcessingResult.UnlinkedIncomingRecorded).call
        assertEquals("Missed", unlinked.callOutcome)
        assertFalse(unlinked.hasRecording)
        assertFalse(unlinked.isLeadCall)

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(
            CallReportResponse(success = true, callId = "srv_auto_missed_1", leadId = "lead_srv_new_77", message = "Auto lead created")
        )))

        val reportResult = callRepository.reportCallToBackend(unlinked.id)
        assertTrue(reportResult is AppResult.Success)

        val recordedRequest = mockWebServer.takeRequest()
        val reqBody = recordedRequest.body.readUtf8()
        assertTrue("Backend request must have auto_create_lead = true", reqBody.contains("\"auto_create_lead\":true"))
        assertTrue("Backend request must have call_direction = MISSED", reqBody.contains("\"call_direction\":\"MISSED\""))
        assertTrue("Backend request must have call_outcome = Missed", reqBody.contains("\"call_outcome\":\"Missed\""))

        val saved = callDao.getById(unlinked.id)
        assertNotNull(saved)
        assertEquals("SYNCED", saved?.syncStatus)
    }
}
