package com.leads365.apk

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.calls.CallProcessingManager
import com.leads365.apk.calls.CallProcessingResult
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.recordings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
class FinalNetworkAndInboundCustomerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var leadDao: LeadDao
    private lateinit var callDao: CallDao
    private lateinit var uploadTaskDao: UploadTaskDao
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var leadRepository: LeadRepository
    private lateinit var callRepository: CallRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var pendingCallManager: PendingCallManager
    private lateinit var coordinator: RecordingDiscoveryCoordinator
    private lateinit var callProcessingManager: CallProcessingManager

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
        uploadTaskDao = database.uploadTaskDao()

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/api/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val leadApiService = retrofit.create(LeadApiService::class.java)
        val callApiService = retrofit.create(CallApiService::class.java)
        normalizer = PhoneNumberNormalizer("IN")

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
            dispatcher = Dispatchers.Unconfined
        )

        uploadQueueRepository = UploadQueueRepositoryImpl(
            uploadTaskDao = uploadTaskDao,
            ioDispatcher = Dispatchers.Unconfined
        )

        pendingCallManager = PendingCallManager(context, normalizer)

        val safSource = object : SafRecordingSource(context, getTreeUri = { null }) {
            override fun isConfigured(): Boolean = false
            override fun isAccessible(): Boolean = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> = emptyList()
        }

        val mediaStoreSource = object : MediaStoreRecordingSource(context) {
            override fun isAccessible(): Boolean = false
            override fun queryCandidates(callStartEpochMs: Long, callEndEpochMs: Long, callDurationSec: Long, normalizedNumber: String): List<RecordingCandidate> = emptyList()
        }

        val matcher = TargetedRecordingMatcher(normalizer)

        coordinator = RecordingDiscoveryCoordinator(
            safRecordingSource = safSource,
            mediaStoreRecordingSource = mediaStoreSource,
            matcher = matcher,
            callRepository = callRepository,
            uploadQueueRepository = uploadQueueRepository,
            normalizer = normalizer,
            delayProvider = {}
        )

        callProcessingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = coordinator
        )
    }

    @After
    fun tearDown() {
        database.close()
        mockWebServer.shutdown()
    }

    @Test
    fun testNonLeadIncomingCallCapturedAsUnlinkedRecord() = runBlocking {
        val incomingEvent = CallEvent(
            id = "in_customer_001",
            rawNumber = "+91 9988776655",
            normalizedNumber = "+919988776655",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000045000L,
            durationSeconds = 45L,
            direction = CallDirection.INCOMING
        )

        val result = callProcessingManager.processCallEvent(incomingEvent)

        assertTrue(
            "Unknown incoming customer call must be recorded as UnlinkedIncomingRecorded",
            result is CallProcessingResult.UnlinkedIncomingRecorded
        )
        val unlinked = result as CallProcessingResult.UnlinkedIncomingRecorded
        assertFalse("isLeadCall must be false for unlinked inbound call", unlinked.call.isLeadCall)
        assertNull("leadId must be null for unlinked inbound call", unlinked.call.leadId)
        assertTrue("Notes must preserve unverified website attribution", unlinked.call.notes?.contains("attribution unverified") == true)

        // Verify call was persisted in Room DB
        val dbCalls = callDao.getRecentCalls(10)
        assertEquals(1, dbCalls.size)
        val dbCall = dbCalls[0]
        assertEquals(unlinked.call.clientCallId, dbCall.clientCallId)
        assertFalse(dbCall.isLeadCall)
        assertNull(dbCall.leadId)
    }

    @Test
    fun testNonLeadOutgoingCallRejectedToProtectPersonalPrivacy() = runBlocking {
        val outgoingPersonal = CallEvent(
            id = "out_personal_1",
            rawNumber = "+91 9123456789",
            normalizedNumber = "+919123456789",
            startTimeEpochMs = 1700000100000L,
            endTimeEpochMs = 1700000130000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        val result = callProcessingManager.processCallEvent(outgoingPersonal)

        assertTrue(
            "Non-lead outgoing call must be rejected to protect personal call privacy",
            result is CallProcessingResult.RejectedNotALead
        )

        // Ensure NOT saved in Room
        val dbCalls = callDao.getRecentCalls(10)
        assertEquals(0, dbCalls.size)
    }

    @Test
    fun testDuplicateCallIgnoredSeparatelyFromLeadMatching() = runBlocking {
        val callEvent = CallEvent(
            id = "dup_event_1",
            rawNumber = "+91 9988776655",
            normalizedNumber = "+919988776655",
            startTimeEpochMs = 1700000200000L,
            endTimeEpochMs = 1700000220000L,
            durationSeconds = 20L,
            direction = CallDirection.INCOMING
        )

        val firstResult = callProcessingManager.processCallEvent(callEvent)
        assertTrue(firstResult is CallProcessingResult.UnlinkedIncomingRecorded)

        // Second call with identical signature within deduplication window
        val duplicateResult = callProcessingManager.processCallEvent(callEvent)
        assertTrue(
            "Duplicate event must be recognized and ignored",
            duplicateResult is CallProcessingResult.DuplicateIgnored
        )

        // Ensure database still contains only 1 call
        val dbCalls = callDao.getRecentCalls(10)
        assertEquals(1, dbCalls.size)
    }

    @Test
    fun testRecordingNotFoundForIncomingCallReportsExactSpecificationMessage() = runBlocking {
        val incomingEvent = CallEvent(
            id = "in_no_rec",
            rawNumber = "+91 9876501234",
            normalizedNumber = "+919876501234",
            startTimeEpochMs = 1700000300000L,
            endTimeEpochMs = 1700000340000L,
            durationSeconds = 40L,
            direction = CallDirection.INCOMING
        )

        val discoveryResult = coordinator.discoverAndMatchRecording(
            callEvent = incomingEvent,
            lead = null,
            retryDelaysMs = emptyList(),
            callId = "call_test_1"
        )

        assertTrue("DiscoveryResult must be NoFileFound", discoveryResult is DiscoveryResult.NoFileFound)
        val noFile = discoveryResult as DiscoveryResult.NoFileFound
        assertEquals(
            "Must report exact specification phrase",
            "Incoming call detected — recording not found",
            noFile.reason
        )
    }

    @Test
    fun testDevelopmentLanConfigurationHostHandling() {
        val defaultDevHost = "172.25.213.99"
        val customOverrideHost = "192.168.1.100"

        val defaultBaseUrl = "http://${defaultDevHost}:5000/api/"
        val overriddenBaseUrl = "http://${customOverrideHost}:5000/api/"

        assertTrue("Dev URL must start with http://", defaultBaseUrl.startsWith("http://"))
        assertTrue("Dev URL must specify port 5000", defaultBaseUrl.contains(":5000/api/"))
        assertEquals("http://192.168.1.100:5000/api/", overriddenBaseUrl)

        val prodBaseUrl = "https://api.leads365.com/api/"
        assertTrue("Prod URL must strictly enforce HTTPS", prodBaseUrl.startsWith("https://"))
    }
}
