package com.leads365.apk.leads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.models.LeadSyncData
import com.leads365.apk.network.models.LeadSyncResponse
import com.leads365.apk.network.models.MobileLeadDto
import com.leads365.apk.ui.leads.LeadsViewModel
import com.leads365.apk.work.LeadSyncWorker
import com.leads365.apk.work.WorkManagerScheduler
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
class LeadSyncAndRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var leadDao: LeadDao
    private lateinit var leadApiService: LeadApiService
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var leadRepository: LeadRepository
    private lateinit var workManagerScheduler: WorkManagerScheduler

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

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        leadApiService = retrofit.create(LeadApiService::class.java)
        normalizer = PhoneNumberNormalizer("IN")

        leadRepository = LeadRepositoryImpl(
            leadDao = leadDao,
            leadApiService = leadApiService,
            context = context,
            normalizer = normalizer,
            dispatcher = Dispatchers.Unconfined
        )

        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        workManagerScheduler = WorkManagerScheduler(context, workManager)

        // Clear shared preferences for lead sync
        context.getSharedPreferences("leads365_lead_sync_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun teardown() {
        database.close()
        mockWebServer.shutdown()
        context.getSharedPreferences("leads365_lead_sync_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `initial sync performs full fetch when since is null`() = runBlocking {
        val serverTime = 1700000000000L
        val responseObj = LeadSyncResponse(
            success = true,
            serverTime = serverTime,
            data = LeadSyncData(
                leads = listOf(
                    MobileLeadDto(
                        id = "lead_101",
                        name = "Amit Kumar",
                        phone = "+919876543210",
                        normalizedPhone = "+919876543210",
                        status = "New",
                        isDoNotRecord = false,
                        notes = "First inquiry",
                        assignedToAgentId = "agent_1",
                        updatedAt = serverTime
                    ),
                    MobileLeadDto(
                        id = "lead_102",
                        name = "Sneha Patel",
                        phone = "09876500002",
                        status = "Engaged",
                        isDoNotRecord = false,
                        notes = "Scheduled callback",
                        assignedToAgentId = "agent_1",
                        updatedAt = serverTime
                    )
                ),
                deletedLeadIds = emptyList(),
                fullSyncRequired = false
            )
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(responseObj)))

        val result = leadRepository.syncLeads(forceFullSync = false)
        assertTrue(result is AppResult.Success)

        val syncData = (result as AppResult.Success).data
        assertEquals(2, syncData.insertedOrUpdatedCount)
        assertEquals(0, syncData.deletedCount)
        assertTrue("Initial sync with no prior timestamp must be treated as full sync", syncData.isFullSync)
        assertEquals(serverTime, syncData.lastSyncTimestampEpochMs)

        // Verify request sent to MockWebServer had no since parameter
        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.path?.contains("/api/mobile/v1/leads/sync") == true)
        assertFalse(recordedRequest.path?.contains("since=") ?: true)

        // Verify leads in Room
        val allLeads = leadDao.getAllLeads()
        assertEquals(2, allLeads.size)

        val lead1 = leadDao.getById("lead_101")
        assertNotNull(lead1)
        assertEquals("Amit Kumar", lead1?.name)
        assertEquals("+919876543210", lead1?.normalizedNumber)

        val lead2 = leadDao.getById("lead_102")
        assertNotNull(lead2)
        assertEquals("Sneha Patel", lead2?.name)
        assertEquals("+919876500002", lead2?.normalizedNumber) // Auto-normalized

        // Verify last sync timestamp persisted
        assertEquals(serverTime, leadRepository.getLastSyncTimestamp())
    }

    @Test
    fun `delta sync sends since timestamp and updates or removes reassigned leads`() = runBlocking {
        // Step 1: Pre-populate Room with initial state
        val initialTime = 1700000000000L
        context.getSharedPreferences("leads365_lead_sync_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("key_last_sync_epoch_ms", initialTime)
            .commit()

        val initialLeads = listOf(
            LeadEntity(id = "lead_101", name = "Amit Kumar", rawNumber = "9876543210", normalizedNumber = "+919876543210", status = "New"),
            LeadEntity(id = "lead_102", name = "Sneha Patel", rawNumber = "9876500002", normalizedNumber = "+919876500002", status = "New")
        )
        leadDao.insertAll(initialLeads)
        assertEquals(2, leadDao.count())

        // Step 2: Delta response: lead_101 updated, lead_103 added, lead_102 removed/reassigned
        val deltaServerTime = 1700000060000L
        val deltaResponse = LeadSyncResponse(
            success = true,
            serverTime = deltaServerTime,
            data = LeadSyncData(
                leads = listOf(
                    MobileLeadDto(
                        id = "lead_101",
                        name = "Amit Kumar",
                        phone = "+919876543210",
                        normalizedPhone = "+919876543210",
                        status = "Engaged",
                        isDoNotRecord = false,
                        notes = "Status upgraded to Engaged",
                        assignedToAgentId = "agent_1",
                        updatedAt = deltaServerTime
                    ),
                    MobileLeadDto(
                        id = "lead_103",
                        name = "Vikas Verma",
                        phone = "9876500003",
                        status = "Proposal Sent",
                        isDoNotRecord = false,
                        notes = "Sent quotation",
                        assignedToAgentId = "agent_1",
                        updatedAt = deltaServerTime
                    )
                ),
                deletedLeadIds = listOf("lead_102"),
                fullSyncRequired = false
            )
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(deltaResponse)))

        val result = leadRepository.syncLeads(forceFullSync = false)
        assertTrue(result is AppResult.Success)

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue("Request must include since parameter", recordedRequest.path?.contains("since=$initialTime") == true)

        // Verify lead_101 is updated
        val updatedLead101 = leadDao.getById("lead_101")
        assertNotNull(updatedLead101)
        assertEquals("Engaged", updatedLead101?.status)
        assertEquals("Status upgraded to Engaged", updatedLead101?.notes)

        // Verify lead_103 is inserted
        val lead103 = leadDao.getById("lead_103")
        assertNotNull(lead103)
        assertEquals("Vikas Verma", lead103?.name)
        assertEquals("+919876500003", lead103?.normalizedNumber)

        // Verify lead_102 (reassigned/deleted) has been purged from cache
        val removedLead102 = leadDao.getById("lead_102")
        assertNull("Reassigned or deleted lead must be removed from Room", removedLead102)

        // Verify total leads count is 2 (101 and 103)
        assertEquals(2, leadDao.count())
    }

    @Test
    fun `full reconciliation retains only active assigned leads and deletes unassigned leads`() = runBlocking {
        // Pre-populate with 3 leads
        val oldLeads = listOf(
            LeadEntity(id = "lead_A", name = "Client A", rawNumber = "9876500001", normalizedNumber = "+919876500001", status = "New"),
            LeadEntity(id = "lead_B", name = "Client B (Reassigned)", rawNumber = "9876500002", normalizedNumber = "+919876500002", status = "New"),
            LeadEntity(id = "lead_C", name = "Client C (Deleted)", rawNumber = "9876500003", normalizedNumber = "+919876500003", status = "New")
        )
        leadDao.insertAll(oldLeads)
        assertEquals(3, leadDao.count())

        // Server sends full sync required = true with only lead_A
        val serverTime = 1700000090000L
        val fullSyncResponse = LeadSyncResponse(
            success = true,
            serverTime = serverTime,
            data = LeadSyncData(
                leads = listOf(
                    MobileLeadDto(
                        id = "lead_A",
                        name = "Client A",
                        phone = "+919876500001",
                        normalizedPhone = "+919876500001",
                        status = "Engaged",
                        isDoNotRecord = false,
                        updatedAt = serverTime
                    )
                ),
                deletedLeadIds = emptyList(),
                fullSyncRequired = true
            )
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(fullSyncResponse)))

        val result = leadRepository.syncLeads(forceFullSync = false)
        assertTrue(result is AppResult.Success)
        val syncData = (result as AppResult.Success).data
        assertTrue(syncData.isFullSync)

        // Only lead_A should remain; B and C should have been purged by retainOnlyActiveLeads
        assertEquals(1, leadDao.count())
        assertNotNull(leadDao.getById("lead_A"))
        assertNull(leadDao.getById("lead_B"))
        assertNull(leadDao.getById("lead_C"))
    }

    @Test
    fun `call gate integrity rejects removed or unassigned leads and respects DNR flag`() = runBlocking {
        val leadAuthorized = LeadEntity(
            id = "lead_auth",
            name = "Authorized Lead",
            rawNumber = "+91 98765 11111",
            normalizedNumber = "+919876511111",
            isDoNotRecord = false,
            status = "Engaged"
        )
        val leadDnr = LeadEntity(
            id = "lead_dnr",
            name = "DNR Client",
            rawNumber = "+91 98765 22222",
            normalizedNumber = "+919876522222",
            isDoNotRecord = true,
            status = "New"
        )
        leadDao.insertAll(listOf(leadAuthorized, leadDnr))

        // 1. Authorized lead lookup
        val lookupAuth = leadRepository.getLeadByNormalizedNumber("+919876511111")
        assertTrue(lookupAuth is AppResult.Success)
        val authEntity = (lookupAuth as AppResult.Success).data
        assertNotNull(authEntity)
        assertEquals("Authorized Lead", authEntity?.name)
        assertFalse(authEntity?.isDoNotRecord ?: true)

        // 2. DNR lead lookup - must be flagged as Do Not Record
        val lookupDnr = leadRepository.getLeadByNormalizedNumber("+919876522222")
        assertTrue(lookupDnr is AppResult.Success)
        val dnrEntity = (lookupDnr as AppResult.Success).data
        assertNotNull(dnrEntity)
        assertTrue("DNR flag must be strictly preserved", dnrEntity?.isDoNotRecord == true)

        // 3. Unassigned or removed lead lookup - must return null (call gate rejects recording)
        val lookupUnassigned = leadRepository.getLeadByNormalizedNumber("+919876599999")
        assertTrue(lookupUnassigned is AppResult.Success)
        val unassignedEntity = (lookupUnassigned as AppResult.Success).data
        assertNull("Call gate query for removed/unassigned number must return null", unassignedEntity)

        // 4. After removing lead_auth, it should immediately return null
        leadRepository.deleteLeadById("lead_auth")
        val lookupDeleted = leadRepository.getLeadByNormalizedNumber("+919876511111")
        assertNull((lookupDeleted as AppResult.Success).data)
    }

    @Test
    fun `rate limit 429 response is parsed into RateLimitError with retryAfterSeconds`() = runBlocking {
        val rateLimitResponse = MockResponse()
            .setResponseCode(429)
            .addHeader("Retry-After", "75")
            .setBody("""{"error":"Too Many Requests"}""")

        mockWebServer.enqueue(rateLimitResponse)

        val result = leadRepository.syncLeads()
        assertTrue(result is AppResult.Failure)

        val error = (result as AppResult.Failure).error
        assertTrue("Expected RateLimitError but was $error", error is AppError.RateLimitError)
        val rateLimitError = error as AppError.RateLimitError
        assertEquals(75L, rateLimitError.retryAfterSeconds)
        assertTrue(rateLimitError.message.contains("75"))
    }

    @Test
    fun `offline operation preserves cached leads when network sync fails`() = runBlocking {
        // Pre-populate cache
        val localLead = LeadEntity(
            id = "lead_cached",
            name = "Offline Client",
            rawNumber = "9876512345",
            normalizedNumber = "+919876512345",
            status = "Engaged"
        )
        leadDao.insert(localLead)

        // Simulate network failure (503 Service Unavailable)
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val syncResult = leadRepository.syncLeads()
        assertTrue(syncResult is AppResult.Failure)

        // Verify local cached lead is still present and accessible
        val cached = leadDao.getById("lead_cached")
        assertNotNull("Offline cached leads must remain completely intact", cached)
        assertEquals("Offline Client", cached?.name)

        val all = leadRepository.getAllLeads()
        assertTrue(all is AppResult.Success)
        assertEquals(1, (all as AppResult.Success).data.size)
    }

    @Test
    fun `searchLeadsFlow filters by text query and status chips`() = runBlocking {
        val leads = listOf(
            LeadEntity(id = "1", name = "Rahul Dravid", rawNumber = "9876500001", normalizedNumber = "+919876500001", status = "New", notes = "Bangalore residential"),
            LeadEntity(id = "2", name = "Rohit Sharma", rawNumber = "9876500002", normalizedNumber = "+919876500002", status = "Engaged", notes = "Commercial plot Mumbai"),
            LeadEntity(id = "3", name = "Virat Kohli", rawNumber = "9876500003", normalizedNumber = "+919876500003", status = "Proposal Sent", notes = "Luxury villa Delhi"),
            LeadEntity(id = "4", name = "Sachin Tendulkar", rawNumber = "9876500004", normalizedNumber = "+919876500004", status = "Closed-Won", notes = "Deal closed")
        )
        leadDao.insertAll(leads)

        // 1. ALL filter, empty query -> all 4
        val allList = leadRepository.searchLeadsFlow("", "ALL").first()
        assertEquals(4, allList.size)

        // 2. Text query "Rohit"
        val queryResult = leadRepository.searchLeadsFlow("Rohit", "ALL").first()
        assertEquals(1, queryResult.size)
        assertEquals("Rohit Sharma", queryResult[0].name)

        // 3. Status filter "Engaged"
        val engagedList = leadRepository.searchLeadsFlow("", "Engaged").first()
        assertEquals(1, engagedList.size)
        assertEquals("Rohit Sharma", engagedList[0].name)

        // 4. Notes search "luxury"
        val notesResult = leadRepository.searchLeadsFlow("luxury", "ALL").first()
        assertEquals(1, notesResult.size)
        assertEquals("Virat Kohli", notesResult[0].name)

        // 5. Phone search "00004"
        val phoneResult = leadRepository.searchLeadsFlow("00004", "ALL").first()
        assertEquals(1, phoneResult.size)
        assertEquals("Sachin Tendulkar", phoneResult[0].name)

        // 6. Non-matching query
        val emptyResult = leadRepository.searchLeadsFlow("NonExistent", "ALL").first()
        assertEquals(0, emptyResult.size)
    }

    @Test
    fun `WorkManagerScheduler correctly enqueues periodic and one-time lead sync work`() {
        val workManager = WorkManager.getInstance(context)

        // Schedule periodic sync
        workManagerScheduler.schedulePeriodicLeadSync()
        val periodicWorkInfos = workManager.getWorkInfosForUniqueWork(LeadSyncWorker.PERIODIC_WORK_NAME).get()
        assertEquals(1, periodicWorkInfos.size)
        assertTrue(periodicWorkInfos[0].tags.contains(LeadSyncWorker.TAG))

        // Trigger one-time sync
        workManagerScheduler.triggerOneTimeLeadSync(forceFullSync = true)
        val oneTimeWorkInfos = workManager.getWorkInfosForUniqueWork(LeadSyncWorker.ONE_TIME_WORK_NAME).get()
        assertEquals(1, oneTimeWorkInfos.size)
        assertTrue(oneTimeWorkInfos[0].tags.contains(LeadSyncWorker.TAG))
    }

    @Test
    fun `LeadsViewModel updates search query and status filter correctly`() = runBlocking {
        // Enqueue an initial empty sync response so ViewModel init sync succeeds
        val initialEmptyResponse = LeadSyncResponse(
            success = true,
            serverTime = 1700000000000L,
            data = LeadSyncData()
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(initialEmptyResponse)))

        val viewModel = LeadsViewModel(leadRepository, workManagerScheduler)

        // Test search query update
        viewModel.onSearchQueryChanged("Kavita")
        assertEquals("Kavita", viewModel.uiState.value.searchQuery)

        // Test status chip selection
        viewModel.onStatusSelected("Proposal Sent")
        assertEquals("Proposal Sent", viewModel.uiState.value.selectedStatus)

        // Test lead detail selection
        val testLead = LeadEntity(id = "lead_test", name = "Test", rawNumber = "123", normalizedNumber = "+91123")
        viewModel.selectLeadForDetail(testLead)
        assertEquals("lead_test", viewModel.uiState.value.selectedLeadForDetail?.id)

        viewModel.selectLeadForDetail(null)
        assertNull(viewModel.uiState.value.selectedLeadForDetail)
    }
}
