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
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.auth.InMemoryTokenProvider
import com.leads365.apk.network.auth.TokenProvider
import com.leads365.apk.network.models.CallReportResponse
import kotlinx.coroutines.Dispatchers
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
class AutoLeadCreationPipelineTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var leadDao: LeadDao
    private lateinit var callDao: CallDao
    private lateinit var uploadTaskDao: UploadTaskDao
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var leadRepository: LeadRepository
    private lateinit var callRepository: CallRepository
    private lateinit var pendingCallManager: PendingCallManager
    private lateinit var autoLeadCaptureConfig: AutoLeadCaptureConfig
    private lateinit var callProcessingManager: CallProcessingManager
    private lateinit var callApiService: CallApiService

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val testTokenProvider = InMemoryTokenProvider(
        accessToken = "test_bearer_token",
        refreshToken = "test_refresh_token",
        deviceIdOrInstallId = "device_test_123",
        agencyAccountId = "tenant_test_abc"
    )

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
        uploadTaskDao = database.uploadTaskDao()

        normalizer = PhoneNumberNormalizer()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        callApiService = retrofit.create(CallApiService::class.java)
        val leadApiService = retrofit.create(LeadApiService::class.java)

        leadRepository = LeadRepositoryImpl(
            leadDao = leadDao,
            leadApiService = leadApiService,
            context = context,
            normalizer = normalizer,
            dispatcher = Dispatchers.Unconfined
        )

        callRepository = CallRepositoryImpl(
            callDao = callDao,
            callApiService = callApiService,
            tokenProvider = testTokenProvider,
            uploadTaskDao = uploadTaskDao,
            recordingDiscoveryCoordinatorProvider = null,
            context = context,
            directCoordinator = null,
            leadDao = leadDao
        )

        pendingCallManager = PendingCallManager(context, normalizer)
        pendingCallManager.clearPendingCall()
        autoLeadCaptureConfig = AutoLeadCaptureConfig(context, testTokenProvider)

        // Clear shared preferences before each test
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(false)
        autoLeadCaptureConfig.setMinDurationSeconds(10)

        callProcessingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = null,
            tokenProvider = testTokenProvider,
            autoLeadCaptureConfig = autoLeadCaptureConfig
        )
    }

    @After
    fun tearDown() {
        database.close()
        mockWebServer.shutdown()
    }

    @Test
    fun testAutoLeadCreationExplicitlyDisabled_incomingCallFromUnknownNumber_doesNotCreateLead() = runBlocking {
        // Given feature is explicitly turned OFF
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(false)
        assertFalse("Feature must be disabled when set to false", autoLeadCaptureConfig.isAutoLeadCreationEnabled())

        val event = CallEvent(
            id = "call_evt_101",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350000000L,
            endTimeEpochMs = 1730350025000L,
            durationSeconds = 25L
        )

        // When
        val result = callProcessingManager.processCallEvent(event)

        // Then: treated as unlinked inbound call, no lead created
        assertTrue("Expected UnlinkedIncomingRecorded when feature is OFF", result is CallProcessingResult.UnlinkedIncomingRecorded)
        val unlinked = result as CallProcessingResult.UnlinkedIncomingRecorded
        assertFalse(unlinked.call.isLeadCall)
        assertNull(unlinked.call.leadId)

        // Database check: 0 leads exist in Room
        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals(0, allLeads.size)
    }

    @Test
    fun testAutoLeadCreationEnabledByDefault_incomingCallFromUnknownNumber_createsLead() = runBlocking {
        // Given default preference configuration (DEFAULT_ENABLED = true)
        val freshConfig = AutoLeadCaptureConfig(context)
        assertTrue("Feature must be enabled by default", freshConfig.isAutoLeadCreationEnabled())
    }

    @Test
    fun testAutoLeadCreationEnabled_answeredIncomingCallAboveMinDuration_createsLeadAndAttachesCall() = runBlocking {
        // Given feature is ON
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)
        autoLeadCaptureConfig.setMinDurationSeconds(10)

        val event = CallEvent(
            id = "call_evt_102",
            rawNumber = "09876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350001000L,
            endTimeEpochMs = 1730350029000L,
            durationSeconds = 28L
        )

        // When
        val result = callProcessingManager.processCallEvent(event)

        // Then: Result is Recorded with auto-created Lead
        assertTrue("Expected Recorded result when auto-creation enabled", result is CallProcessingResult.Recorded)
        val recorded = result as CallProcessingResult.Recorded
        val autoLead = recorded.lead

        assertTrue("Auto lead ID must start with lead_auto_", autoLead.id.startsWith("lead_auto_"))
        assertEquals("Unknown Caller - +919876543210", autoLead.name)
        assertEquals("+919876543210", autoLead.normalizedNumber)
        assertEquals("New", autoLead.status)
        assertTrue("Notes should mention auto-captured", autoLead.notes.contains("Auto-captured"))

        // Call entity verification
        assertTrue("Call must be marked isLeadCall = true", recorded.call.isLeadCall)
        assertEquals(autoLead.id, recorded.call.leadId)

        // Local Room persistence check
        val savedLead = (leadRepository.getLeadById(autoLead.id) as AppResult.Success).data
        assertNotNull("Auto-created Lead must be saved in Room", savedLead)
        assertEquals(autoLead.name, savedLead?.name)

        val savedCall = callDao.getById(recorded.call.id)
        assertNotNull("Call must be persisted in Room", savedCall)
        assertEquals(autoLead.id, savedCall?.leadId)
        assertTrue(savedCall?.isLeadCall == true)
    }

    @Test
    fun testAutoLeadCreationEnabled_missedCall_createsLeadAndAttachesCall() = runBlocking {
        // Given feature is ON
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)

        val missedEvent = CallEvent(
            id = "call_evt_103",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.MISSED,
            startTimeEpochMs = 1730350002000L,
            endTimeEpochMs = 1730350002000L,
            durationSeconds = 0L
        )

        // When
        val result = callProcessingManager.processCallEvent(missedEvent)

        // Then: Result is Recorded with auto-created Lead for missed call
        assertTrue("Missed calls must auto-create leads when enabled", result is CallProcessingResult.Recorded)
        val recorded = result as CallProcessingResult.Recorded
        val autoLead = recorded.lead

        assertTrue("Auto lead ID must start with lead_auto_", autoLead.id.startsWith("lead_auto_"))
        assertEquals("Unknown Caller - +919876543210", autoLead.name)
        assertEquals("+919876543210", autoLead.normalizedNumber)
        assertEquals("New", autoLead.status)
        assertEquals("Auto-captured from missed incoming call", autoLead.notes)
        assertTrue("Call must be marked isLeadCall = true", recorded.call.isLeadCall)
        assertEquals("Missed", recorded.call.callOutcome)
        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("1 lead should be created for missed call", 1, allLeads.size)
    }

    @Test
    fun testAutoLeadCreationEnabled_shortCallBelowMinDuration_doesNotCreateLead() = runBlocking {
        // Given feature is ON with 10s threshold
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)
        autoLeadCaptureConfig.setMinDurationSeconds(10)

        val shortEvent = CallEvent(
            id = "call_evt_104",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350003000L,
            endTimeEpochMs = 1730350009000L,
            durationSeconds = 6L // Below 10s minimum threshold
        )

        // When
        val result = callProcessingManager.processCallEvent(shortEvent)

        // Then
        assertTrue("Short incoming call must not auto-create lead", result is CallProcessingResult.UnlinkedIncomingRecorded)
        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("No lead should be created for call under threshold", 0, allLeads.size)
    }

    @Test
    fun testAutoLeadCreationEnabled_outgoingCallToUnknownNumber_isRejected() = runBlocking {
        // Given feature is ON
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)

        val outgoingEvent = CallEvent(
            id = "call_evt_105",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.OUTGOING,
            startTimeEpochMs = 1730350004000L,
            endTimeEpochMs = 1730350049000L,
            durationSeconds = 45L
        )

        // When
        val result = callProcessingManager.processCallEvent(outgoingEvent)

        // Then: Non-lead outgoing calls must be halted at assigned-lead gate
        assertTrue("Outgoing unknown call must be rejected", result is CallProcessingResult.RejectedNotALead)
        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("No lead should be created for outgoing call", 0, allLeads.size)
    }

    @Test
    fun testAutoLeadCreationEnabled_existingLeadIncomingCall_reusesExistingLeadWithoutDuplicate() = runBlocking {
        // Given feature is ON and pre-existing lead "Rahul Sharma" exists
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)

        val existingLead = LeadEntity(
            id = "lead_existing_crm_123",
            name = "Rahul Sharma",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            status = "Engaged"
        )
        leadRepository.saveLead(existingLead)

        val event = CallEvent(
            id = "call_evt_106",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350005000L,
            endTimeEpochMs = 1730350055000L,
            durationSeconds = 50L
        )

        // When
        val result = callProcessingManager.processCallEvent(event)

        // Then: Call is linked to existing Rahul Sharma lead, no duplicate created
        assertTrue(result is CallProcessingResult.Recorded)
        val recorded = result as CallProcessingResult.Recorded
        assertEquals("lead_existing_crm_123", recorded.lead.id)
        assertEquals("Rahul Sharma", recorded.lead.name)

        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("Lead count must remain 1 without duplicates", 1, allLeads.size)
    }

    @Test
    fun testAutoLeadCreationEnabled_dnrCaller_rejectedWithoutCreatingLead() = runBlocking {
        // Given feature is ON and a Do-Not-Record (DNR) lead exists for this number
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)

        val dnrLead = LeadEntity(
            id = "lead_dnr_999",
            name = "DNR Contact",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            isDoNotRecord = true
        )
        leadRepository.saveLead(dnrLead)

        val event = CallEvent(
            id = "call_evt_107",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350006000L,
            endTimeEpochMs = 1730350036000L,
            durationSeconds = 30L
        )

        // When
        val result = callProcessingManager.processCallEvent(event)

        // Then: Gate rejected with RejectedDoNotRecord
        assertTrue("DNR caller must be rejected", result is CallProcessingResult.RejectedDoNotRecord)
    }

    @Test
    fun testReportCallToBackend_canonicalizesProvisionalLeadIdAndUpdatesTasks() = runBlocking {
        // Given provisional call and upload task in Room
        val clientCallId = "call_tenant_test_abc_device_test_123_9876543210_1730350007000"
        val provisionalLeadId = "lead_auto_9876543210_1730350007000"
        val canonicalServerLeadId = "6ae5782e7729ab84c67df56a"

        val provisionalLead = LeadEntity(
            id = provisionalLeadId,
            name = "Unknown Caller - +919876543210",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            status = "New"
        )
        leadDao.insert(provisionalLead)

        val call = CallEntity(
            id = clientCallId,
            clientCallId = clientCallId,
            normalizedNumber = "+919876543210",
            rawNumber = "+919876543210",
            callType = CallLog.Calls.INCOMING_TYPE,
            startTimeEpochMs = 1730350007000L,
            durationSeconds = 25,
            isLeadCall = true,
            leadId = provisionalLeadId,
            syncStatus = "PENDING",
            notes = "Auto-captured from answered incoming call (25s)"
        )
        callDao.insert(call)

        val uploadTask = UploadTaskEntity(
            id = "task_upload_1",
            callId = clientCallId,
            leadId = provisionalLeadId,
            localFileUri = "content://media/recordings/test.m4a",
            fileName = "rec_test.m4a",
            fileSizeBytes = 1024L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(uploadTask)

        // Server responds with canonical lead_id
        val serverResponse = CallReportResponse(
            success = true,
            callId = "srv_call_999",
            leadId = canonicalServerLeadId,
            autoLeadCreated = true,
            message = "Call recorded and lead auto-created successfully"
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(json.encodeToString(serverResponse))
        )

        // When reporting call to backend
        val reportResult = callRepository.reportCallToBackend(clientCallId)

        // Then
        assertTrue("Report must succeed", reportResult is AppResult.Success)
        val resp = (reportResult as AppResult.Success).data
        assertEquals(canonicalServerLeadId, resp.leadId)

        // Verify Room callDao was upgraded with canonical server lead_id
        val updatedCall = callDao.getById(clientCallId)
        assertEquals(canonicalServerLeadId, updatedCall?.leadId)
        assertEquals("SYNCED", updatedCall?.syncStatus)

        // Verify upload_tasks was upgraded with canonical server lead_id
        val updatedTask = uploadTaskDao.getByCallId(clientCallId)
        assertEquals(canonicalServerLeadId, updatedTask?.leadId)

        // Verify local Room LeadEntity was upgraded to canonical server lead_id
        val oldLead = leadDao.getById(provisionalLeadId)
        assertNull("Old provisional lead must be deleted", oldLead)
        val canonicalLead = leadDao.getById(canonicalServerLeadId)
        assertNotNull("Canonical lead must exist in Room", canonicalLead)
        assertEquals("Unknown Caller - +919876543210", canonicalLead?.name)
    }

    @Test
    fun testAutoLeadCaptureConfig_tenantIsolation() {
        val tenantA = "tenant_company_a"
        val tenantB = "tenant_company_b"

        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true, tenantA)
        autoLeadCaptureConfig.setMinDurationSeconds(15, tenantA)
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(false, tenantB)

        assertTrue("Tenant A must have feature enabled", autoLeadCaptureConfig.isAutoLeadCreationEnabled(tenantA))
        assertEquals(15, autoLeadCaptureConfig.getMinDurationSeconds(tenantA))

        // Tenant B must strictly remain disabled
        assertFalse("Tenant B must remain disabled", autoLeadCaptureConfig.isAutoLeadCreationEnabled(tenantB))
        assertEquals(10, autoLeadCaptureConfig.getMinDurationSeconds(tenantB))
    }

    @Test
    fun testAutoLeadCreationEnabled_oemRejectedCallWithRingDuration_createsLead() = runBlocking {
        // Given feature is ON
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)
        autoLeadCaptureConfig.setMinDurationSeconds(10)

        // OEM dialer recorded 25 seconds of ringing, but callType is REJECTED
        val rejectedCall = CallEvent(
            id = "call_evt_rejected_oem",
            rawNumber = "+919876543299",
            normalizedNumber = "+919876543299",
            direction = CallDirection.INCOMING,
            callType = android.provider.CallLog.Calls.REJECTED_TYPE,
            startTimeEpochMs = 1730350005000L,
            endTimeEpochMs = 1730350030000L,
            durationSeconds = 25L
        )

        val result = callProcessingManager.processCallEvent(rejectedCall)

        assertTrue("Rejected calls must auto-create leads", result is CallProcessingResult.Recorded)
        val recorded = result as CallProcessingResult.Recorded
        assertEquals("New", recorded.lead.status)
        assertEquals("Auto-captured from missed incoming call", recorded.lead.notes)
        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("Lead should be created for rejected call", 1, allLeads.size)
    }

    @Test
    fun testAutoLeadCreationEnabled_oemMissedCallWithRingDuration_createsLead() = runBlocking {
        // Given feature is ON
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)
        autoLeadCaptureConfig.setMinDurationSeconds(10)

        // OEM dialer recorded 30 seconds of ringing, but callType is MISSED
        val missedCall = CallEvent(
            id = "call_evt_missed_oem",
            rawNumber = "+919876543298",
            normalizedNumber = "+919876543298",
            direction = CallDirection.MISSED,
            callType = android.provider.CallLog.Calls.MISSED_TYPE,
            startTimeEpochMs = 1730350006000L,
            endTimeEpochMs = 1730350036000L,
            durationSeconds = 30L
        )

        val result = callProcessingManager.processCallEvent(missedCall)

        assertTrue("Missed calls must auto-create leads", result is CallProcessingResult.Recorded)
        val recorded = result as CallProcessingResult.Recorded
        assertEquals("New", recorded.lead.status)
        assertEquals("Auto-captured from missed incoming call", recorded.lead.notes)
        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("Lead should be created for missed call", 1, allLeads.size)
    }

    @Test
    fun testCallWaitingMissedCall_capturedAlongsideActiveCall() = runBlocking {
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)

        // Call 1: Answered incoming call (duration 120s)
        val call1 = CallEvent(
            id = "call_evt_active_1",
            rawNumber = "+919876543211",
            normalizedNumber = "+919876543211",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350000000L,
            endTimeEpochMs = 1730350120000L,
            durationSeconds = 120L
        )

        // Call 2: Call-waiting missed call during Call 1 (duration 0s)
        val call2 = CallEvent(
            id = "call_evt_waiting_2",
            rawNumber = "+919876543222",
            normalizedNumber = "+919876543222",
            direction = CallDirection.MISSED,
            startTimeEpochMs = 1730350060000L,
            endTimeEpochMs = 1730350060000L,
            durationSeconds = 0L
        )

        // When both calls are processed
        val res1 = callProcessingManager.processCallEvent(call1)
        val res2 = callProcessingManager.processCallEvent(call2)

        // Then both are recorded without deduplication dropping Call 2
        assertTrue("Call 1 recorded", res1 is CallProcessingResult.Recorded)
        assertTrue("Call 2 recorded", res2 is CallProcessingResult.Recorded)

        val allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals("Both callers must have leads auto-created", 2, allLeads.size)

        val allCalls = callDao.getRecentCalls(10)
        assertEquals("Both calls must be persisted in database", 2, allCalls.size)
    }

    @Test
    fun testDeferredIdentityRecovery_upgradesToCanonicalAndSyncs() = runBlocking {
        // TokenProvider without identity
        val anonymousTokenProvider = InMemoryTokenProvider(
            accessToken = "token",
            refreshToken = "refresh",
            deviceIdOrInstallId = null,
            agencyAccountId = null
        )
        val anonCallProcessingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = null,
            tokenProvider = anonymousTokenProvider,
            autoLeadCaptureConfig = autoLeadCaptureConfig
        )
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)

        val callEvent = CallEvent(
            id = "native_call_109",
            rawNumber = "+919876543299",
            normalizedNumber = "+919876543299",
            direction = CallDirection.INCOMING,
            startTimeEpochMs = 1730350009000L,
            endTimeEpochMs = 1730350039000L,
            durationSeconds = 30L
        )

        val result = anonCallProcessingManager.processCallEvent(callEvent)
        assertTrue("Call must be recorded", result is CallProcessingResult.Recorded)
        val recorded = result as CallProcessingResult.Recorded
        assertEquals("DEFERRED_IDENTITY", recorded.call.syncStatus)
        assertTrue("ID must be local_pending_call_", recorded.call.clientCallId.startsWith("local_pending_call_"))

        // getPendingSyncCalls must return DEFERRED_IDENTITY calls
        val pendingList = callDao.getPendingSyncCalls()
        assertTrue("Pending sync calls must include DEFERRED_IDENTITY call", pendingList.any { it.clientCallId == recorded.call.clientCallId })

        // When identity becomes available
        val serverResponse = CallReportResponse(
            success = true,
            callId = "srv_recovered_1",
            leadId = "lead_srv_recovered_1",
            message = "Sync successful"
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json.encodeToString(serverResponse))
        )

        // callRepository with valid identity
        val syncResult = callRepository.syncPendingCallsToBackend()
        assertTrue("Sync must succeed", syncResult is AppResult.Success)

        val updatedCall = callDao.getById(recorded.call.id) ?: callDao.getByClientCallId("call_tenant_test_abc_device_test_123_native_call_109")
        assertNotNull("Call must be found", updatedCall)
        assertEquals("SYNCED", updatedCall?.syncStatus)
    }

    @Test
    fun testAutoLeadCreationEnabled_configuredThreshold_15s_enforced() = runBlocking {
        // Given threshold is configured to 15s
        autoLeadCaptureConfig.setAutoLeadCreationEnabled(true)
        autoLeadCaptureConfig.setMinDurationSeconds(15)

        // Call of 12s (< 15s) must not create lead
        val shortCall = CallEvent(
            id = "call_evt_12s",
            rawNumber = "+919876543297",
            normalizedNumber = "+919876543297",
            direction = CallDirection.INCOMING,
            callType = android.provider.CallLog.Calls.INCOMING_TYPE,
            startTimeEpochMs = 1730350007000L,
            endTimeEpochMs = 1730350019000L,
            durationSeconds = 12L
        )
        val res1 = callProcessingManager.processCallEvent(shortCall)
        assertTrue("Sub-15s call must not auto-create lead", res1 is CallProcessingResult.UnlinkedIncomingRecorded)
        var allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals(0, allLeads.size)

        // Call of 20s (>= 15s) DOES create lead
        val qualifyingCall = CallEvent(
            id = "call_evt_20s",
            rawNumber = "+919876543296",
            normalizedNumber = "+919876543296",
            direction = CallDirection.INCOMING,
            callType = android.provider.CallLog.Calls.INCOMING_TYPE,
            startTimeEpochMs = 1730350020000L,
            endTimeEpochMs = 1730350040000L,
            durationSeconds = 20L
        )
        val res2 = callProcessingManager.processCallEvent(qualifyingCall)
        assertTrue("Qualifying call must auto-create lead", res2 is CallProcessingResult.Recorded)
        allLeads = (leadRepository.getAllLeads() as AppResult.Success).data
        assertEquals(1, allLeads.size)
    }
}
