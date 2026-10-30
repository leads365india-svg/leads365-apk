package com.leads365.apk.recordings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.CallRepositoryImpl
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

/**
 * Unit Tests for Leads365 Historical Call & Recording Backfill
 * 
 * Verifies:
 * 1. Direct call from an unknown number: stored as unassigned in Room (leadId = null, isLeadCall = false).
 * 2. Manual creation of that number as a Lead: automatically reconciles & associates calls in Room.
 * 3. Pending UploadTask for that call has leadId updated from unlinked to the new lead ID.
 * 4. Calls already associated with another Lead are strictly preserved and never silently reassigned.
 * 5. Multiple historical calls to the same number remain distinct separate records.
 * 6. Offline network resilience: local association succeeds and marks syncStatus = PENDING without crashing.
 * 7. Repeated reconciliation execution is idempotent with zero duplicate records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HistoricalCallReconciliationTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var callDao: CallDao
    private lateinit var leadDao: LeadDao
    private lateinit var uploadTaskDao: UploadTaskDao
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var mockWebServer: MockWebServer
    private lateinit var callApiService: CallApiService
    private lateinit var leadApiService: LeadApiService
    private lateinit var callRepository: CallRepositoryImpl
    private lateinit var leadRepository: LeadRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        callDao = database.callDao()
        leadDao = database.leadDao()
        uploadTaskDao = database.uploadTaskDao()
        normalizer = PhoneNumberNormalizer("IN")

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        callApiService = retrofit.create(CallApiService::class.java)
        leadApiService = retrofit.create(LeadApiService::class.java)

        val tokenProvider = InMemoryTokenProvider(
            accessToken = "test_access_token_123",
            deviceIdOrInstallId = "dev_inst_test_001",
            agencyAccountId = "tenant_test_123"
        )

        callRepository = CallRepositoryImpl(
            callDao = callDao,
            callApiService = callApiService,
            tokenProvider = tokenProvider,
            uploadTaskDao = uploadTaskDao,
            recordingDiscoveryCoordinatorProvider = null,
            context = context
        )

        leadRepository = LeadRepositoryImpl(
            leadDao = leadDao,
            leadApiService = leadApiService,
            context = context,
            normalizer = normalizer,
            dispatcher = Dispatchers.Unconfined,
            callRepository = callRepository
        )
    }

    @After
    fun teardown() {
        database.close()
        mockWebServer.shutdown()
    }

    @Test
    fun testDirectCallFromUnknownNumber_persistsAsUnassigned() = runBlocking {
        val rawPhone = "9876543210"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        val unassignedCall = CallEntity(
            id = "call_native_001",
            clientCallId = "call_tenant_dev_native_001",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 1, // INCOMING
            startTimeEpochMs = System.currentTimeMillis() - 3600000,
            durationSeconds = 45,
            isLeadCall = false,
            leadId = null,
            syncStatus = "PENDING"
        )
        callDao.insert(unassignedCall)

        val unassignedResult = callRepository.getUnassignedCallsForNumber(normalized, rawPhone)
        assertTrue(unassignedResult is AppResult.Success)
        val unassignedList = (unassignedResult as AppResult.Success).data
        assertEquals(1, unassignedList.size)
        assertEquals("call_native_001", unassignedList[0].id)
        assertNull(unassignedList[0].leadId)
        assertFalse(unassignedList[0].isLeadCall)
    }

    @Test
    fun testSaveLead_automaticallyReconcilesHistoricalUnassignedCalls() = runBlocking {
        val rawPhone = "9876543210"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        // 1. Existing unassigned call in Room
        val call = CallEntity(
            id = "call_native_002",
            clientCallId = "call_tenant_dev_native_002",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 1,
            startTimeEpochMs = System.currentTimeMillis() - 1800000,
            durationSeconds = 60,
            isLeadCall = false,
            leadId = null,
            syncStatus = "SYNCED"
        )
        callDao.insert(call)

        // Mock backend sync response for call reporting
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"call_id":"call_native_002","message":"Call updated"}""")
        )

        // 2. User manually creates lead with that phone number
        val lead = LeadEntity(
            id = "lead_prospect_101",
            name = "Enterprise Lead",
            rawNumber = rawPhone,
            normalizedNumber = normalized
        )
        leadRepository.saveLead(lead)

        // 3. Verify call in Room was automatically reconciled and linked
        val updatedCall = callDao.getById("call_native_002")
        assertNotNull(updatedCall)
        assertEquals("lead_prospect_101", updatedCall?.leadId)
        assertTrue(updatedCall?.isLeadCall == true)
    }

    @Test
    fun testReconcileCalls_updatesPendingUploadTaskLeadId() = runBlocking {
        val rawPhone = "9876543211"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        // 1. Insert unassigned call
        val call = CallEntity(
            id = "call_native_003",
            clientCallId = "call_tenant_dev_native_003",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 2, // OUTGOING
            startTimeEpochMs = System.currentTimeMillis() - 7200000,
            durationSeconds = 120,
            isLeadCall = false,
            leadId = null,
            hasRecording = true,
            recordingFileName = "recording_003.m4a"
        )
        callDao.insert(call)

        // 2. Insert UploadTask for that unassigned call
        val uploadTask = UploadTaskEntity(
            id = "task_003",
            callId = "call_native_003",
            leadId = "unlinked",
            localFileUri = "content://recordings/003",
            fileName = "recording_003.m4a",
            fileSizeBytes = 32768L,
            status = UploadTaskEntity.STATUS_PENDING
        )
        uploadTaskDao.insert(uploadTask)

        // 3. Reconcile with new Lead
        val lead = LeadEntity(
            id = "lead_prospect_102",
            name = "Second Prospect",
            rawNumber = rawPhone,
            normalizedNumber = normalized
        )

        // Mock backend sync response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"call_id":"call_native_003"}""")
        )

        callRepository.reconcileCallsForLead(lead)

        // 4. Verify UploadTask leadId was updated to the new lead
        val updatedTask = uploadTaskDao.getByCallId("call_native_003")
        assertNotNull(updatedTask)
        assertEquals("lead_prospect_102", updatedTask?.leadId)
    }

    @Test
    fun testCallsAlreadyAssociatedWithAnotherLead_areNeverReassigned() = runBlocking {
        val rawPhone = "9876543212"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        // Call already assigned to Lead A
        val assignedCall = CallEntity(
            id = "call_native_004",
            clientCallId = "call_tenant_dev_native_004",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 1,
            startTimeEpochMs = System.currentTimeMillis() - 5000000,
            durationSeconds = 30,
            isLeadCall = true,
            leadId = "lead_existing_aaa",
            syncStatus = "SYNCED"
        )
        callDao.insert(assignedCall)

        // Create new Lead B with the same phone number
        val leadB = LeadEntity(
            id = "lead_new_bbb",
            name = "Lead B Same Phone",
            rawNumber = rawPhone,
            normalizedNumber = normalized
        )
        leadRepository.saveLead(leadB)

        // Verify call retains its original Lead A assignment
        val callAfter = callDao.getById("call_native_004")
        assertNotNull(callAfter)
        assertEquals("lead_existing_aaa", callAfter?.leadId)
        assertNotEquals("lead_new_bbb", callAfter?.leadId)
    }

    @Test
    fun testMultipleHistoricalCalls_remainSeparateAndDistinct() = runBlocking {
        val rawPhone = "9876543213"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        val t1 = System.currentTimeMillis() - 10000000
        val t2 = System.currentTimeMillis() - 5000000
        val t3 = System.currentTimeMillis() - 1000000

        val call1 = CallEntity(
            id = "call_multi_1",
            clientCallId = "c_1",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 1, // INCOMING
            startTimeEpochMs = t1,
            durationSeconds = 25,
            isLeadCall = false,
            leadId = null
        )
        val call2 = CallEntity(
            id = "call_multi_2",
            clientCallId = "c_2",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 2, // OUTGOING
            startTimeEpochMs = t2,
            durationSeconds = 85,
            isLeadCall = false,
            leadId = null
        )
        val call3 = CallEntity(
            id = "call_multi_3",
            clientCallId = "c_3",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 3, // MISSED
            startTimeEpochMs = t3,
            durationSeconds = 0,
            isLeadCall = false,
            leadId = null
        )

        callDao.insert(call1)
        callDao.insert(call2)
        callDao.insert(call3)

        // 3 calls to sync
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        val lead = LeadEntity(
            id = "lead_multi_prospect",
            name = "Frequent Contact",
            rawNumber = rawPhone,
            normalizedNumber = normalized
        )
        leadRepository.saveLead(lead)

        val refreshed1 = callDao.getById("call_multi_1")
        val refreshed2 = callDao.getById("call_multi_2")
        val refreshed3 = callDao.getById("call_multi_3")

        // All 3 associated
        assertEquals("lead_multi_prospect", refreshed1?.leadId)
        assertEquals("lead_multi_prospect", refreshed2?.leadId)
        assertEquals("lead_multi_prospect", refreshed3?.leadId)

        // All distinct metadata preserved
        assertEquals(25L, refreshed1?.durationSeconds)
        assertEquals(85L, refreshed2?.durationSeconds)
        assertEquals(0L, refreshed3?.durationSeconds)
        assertEquals(1, refreshed1?.callType)
        assertEquals(2, refreshed2?.callType)
        assertEquals(3, refreshed3?.callType)
    }

    @Test
    fun testOfflineReconciliation_succeedsLocallyAndMarksPending() = runBlocking {
        val rawPhone = "9876543214"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        val call = CallEntity(
            id = "call_offline_001",
            clientCallId = "call_offline_client_001",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 1,
            startTimeEpochMs = System.currentTimeMillis() - 2000000,
            durationSeconds = 40,
            isLeadCall = false,
            leadId = null,
            syncStatus = "PENDING"
        )
        callDao.insert(call)

        // Simulate backend offline / 503 Service Unavailable
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val lead = LeadEntity(
            id = "lead_offline_created",
            name = "Offline Lead",
            rawNumber = rawPhone,
            normalizedNumber = normalized
        )

        // Reconciliation should NOT throw or crash
        val reconcileResult = callRepository.reconcileCallsForLead(lead)
        assertTrue(reconcileResult is AppResult.Success)
        val count = (reconcileResult as AppResult.Success).data
        assertEquals(1, count)

        // Call is associated locally and ready for retry
        val updatedCall = callDao.getById("call_offline_001")
        assertNotNull(updatedCall)
        assertEquals("lead_offline_created", updatedCall?.leadId)
        assertTrue(updatedCall?.isLeadCall == true)
        assertTrue(updatedCall?.syncStatus in listOf("PENDING", "FAILED"))
    }

    @Test
    fun testRepeatedReconciliation_isIdempotent() = runBlocking {
        val rawPhone = "9876543215"
        val normalized = normalizer.normalizeToE164(rawPhone) ?: "+91$rawPhone"

        val call = CallEntity(
            id = "call_repeat_001",
            clientCallId = "call_repeat_client_001",
            normalizedNumber = normalized,
            rawNumber = rawPhone,
            callType = 1,
            startTimeEpochMs = System.currentTimeMillis() - 1000000,
            durationSeconds = 50,
            isLeadCall = false,
            leadId = null
        )
        callDao.insert(call)

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        val lead = LeadEntity(
            id = "lead_repeat_001",
            name = "Idempotency Test Lead",
            rawNumber = rawPhone,
            normalizedNumber = normalized
        )

        // Run 1: associates the 1 unassigned call
        val res1 = callRepository.reconcileCallsForLead(lead)
        assertTrue(res1 is AppResult.Success)
        assertEquals(1, (res1 as AppResult.Success).data)

        // Run 2: call is now already assigned, so 0 calls are re-associated
        val res2 = callRepository.reconcileCallsForLead(lead)
        assertTrue(res2 is AppResult.Success)
        assertEquals(0, (res2 as AppResult.Success).data)

        // Still exactly 1 call record in database
        val callInDb = callDao.getById("call_repeat_001")
        assertNotNull(callInDb)
        assertEquals("lead_repeat_001", callInDb?.leadId)
    }
}
