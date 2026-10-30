package com.leads365.apk.calls

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.network.auth.InMemoryTokenProvider
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
@Config(manifest = Config.NONE)
class StableIdentityAndDeferredSyncTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var callDao: CallDao
    private lateinit var leadDao: LeadDao
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var mockWebServer: MockWebServer
    private lateinit var callApiService: CallApiService
    private lateinit var leadApiService: LeadApiService
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
        normalizer = PhoneNumberNormalizer("IN")

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        callApiService = retrofit.create(CallApiService::class.java)
        leadApiService = retrofit.create(LeadApiService::class.java)
    }

    @After
    fun teardown() {
        database.close()
        mockWebServer.shutdown()
    }

    @Test
    fun testInstallId_stableAcrossProviderRestartsAndClearSession() {
        val provider1 = EncryptedTokenProvider(context)
        val installId1 = provider1.getOrCreateInstallId()

        assertTrue("Install ID must start with inst_", installId1.startsWith("inst_"))
        assertEquals("Install ID must be stable on repeat calls", installId1, provider1.getOrCreateInstallId())

        // Simulate app restart by re-instantiating provider with same context
        val provider2 = EncryptedTokenProvider(context)
        assertEquals("Install ID must persist across app restarts", installId1, provider2.getOrCreateInstallId())

        // Save session info and tokens
        provider2.saveTokens("access_123", "refresh_123")
        provider2.saveSessionInfo(
            userId = "user_1",
            userName = "Test Agent",
            userEmail = "agent@test.com",
            agencyName = "Alpha Agency",
            agencyAccountId = "tenant_alpha"
        )
        assertEquals("tenant_alpha", provider2.getAgencyAccountId())

        // Clear session on logout
        provider2.clearAllSession()
        assertNull("Tokens must be cleared", provider2.getAccessToken())
        assertNull("Tenant account must be cleared", provider2.getAgencyAccountId())

        // Install ID must remain strictly preserved!
        val provider3 = EncryptedTokenProvider(context)
        assertEquals("Install ID must be preserved even after clearAllSession", installId1, provider3.getDeviceIdOrInstallId())
    }

    @Test
    fun testServerDeviceId_overridesLocalInstallIdWhenRegistered() {
        val provider = EncryptedTokenProvider(context)
        val localInstallId = provider.getOrCreateInstallId()
        assertEquals(localInstallId, provider.getDeviceIdOrInstallId())

        // Server registers device
        provider.saveDeviceId("srv_dev_998877")
        assertEquals("srv_dev_998877", provider.getDeviceIdOrInstallId())
    }

    @Test
    fun testCallProcessing_whenIdentityResolved_generatesCanonicalIdWithoutDevOrDefault() = runBlocking {
        val tokenProvider = InMemoryTokenProvider(
            accessToken = "token_abc",
            deviceIdOrInstallId = "dev_inst_xyz",
            agencyAccountId = "tenant_acme"
        )

        val leadRepository = LeadRepositoryImpl(leadDao, leadApiService, context, normalizer, Dispatchers.Unconfined)
        val callRepository = CallRepositoryImpl(callDao, callApiService, Dispatchers.Unconfined, tokenProvider)
        val pendingCallManager = PendingCallManager(context, normalizer)

        val callProcessingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = null,
            tokenProvider = tokenProvider
        )

        val lead = LeadEntity(id = "lead_1", name = "Ravi Kumar", rawNumber = "9876543210", normalizedNumber = "+919876543210")
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "native_log_123",
            rawNumber = "9876543210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000030000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        val result = callProcessingManager.processCallEvent(callEvent)
        assertTrue(result is CallProcessingResult.Recorded)

        val call = (result as CallProcessingResult.Recorded).call
        assertEquals("call_tenant_acme_dev_inst_xyz_native_log_123", call.clientCallId)
        assertFalse("clientCallId must not contain 'default'", call.clientCallId.contains("default"))
        assertFalse("clientCallId must not contain 'dev_' placeholder for install", call.clientCallId.contains("_dev_native"))
        assertEquals("PENDING", call.syncStatus)
    }

    @Test
    fun testCallProcessing_whenIdentityMissing_safelyDefersCallWithoutDropping() = runBlocking {
        // TokenProvider with NO tenant and NO install id
        val tokenProvider = InMemoryTokenProvider()

        val leadRepository = LeadRepositoryImpl(leadDao, leadApiService, context, normalizer, Dispatchers.Unconfined)
        val callRepository = CallRepositoryImpl(callDao, callApiService, Dispatchers.Unconfined, tokenProvider)
        val pendingCallManager = PendingCallManager(context, normalizer)

        val callProcessingManager = CallProcessingManager(
            leadRepository = leadRepository,
            callRepository = callRepository,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            recordingDiscoveryCoordinator = null,
            tokenProvider = tokenProvider
        )

        val lead = LeadEntity(id = "lead_2", name = "Priya Sharma", rawNumber = "9876500000", normalizedNumber = "+919876500000")
        leadDao.insert(lead)

        val callEvent = CallEvent(
            id = "native_log_456",
            rawNumber = "9876500000",
            normalizedNumber = "+919876500000",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000030000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        val result = callProcessingManager.processCallEvent(callEvent)
        assertTrue(result is CallProcessingResult.Recorded)

        val call = (result as CallProcessingResult.Recorded).call
        assertTrue("Deferred call must have local_pending_call_ prefix", call.clientCallId.startsWith("local_pending_call_"))
        assertEquals("DEFERRED_IDENTITY", call.syncStatus)

        // Verify call is preserved in Room database!
        val dbCall = callDao.getById(call.id)
        assertNotNull("Call must not be dropped when identity is missing", dbCall)
        assertEquals("DEFERRED_IDENTITY", dbCall?.syncStatus)

        // Attempting to report without identity must defer safely
        val reportResult1 = callRepository.reportCallToBackend(call.id)
        assertTrue("Reporting without identity must fail with AuthError", reportResult1 is AppResult.Failure)
        assertEquals("DEFERRED_IDENTITY", callDao.getById(call.id)?.syncStatus)

        // Now resolve identity (e.g. login completes)
        tokenProvider.setAgencyAccountId("tenant_acme")
        tokenProvider.setDeviceIdOrInstallId("inst_778899")

        // Setup mock response
        val responseJson = json.encodeToString(
            CallReportResponse(
                success = true,
                callId = "srv_call_999",
                message = "Call report received successfully"
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        // Retrying report must now upgrade the canonical ID and succeed
        val reportResult2 = callRepository.reportCallToBackend(call.id)
        assertTrue("Reporting after identity resolution must succeed", reportResult2 is AppResult.Success)

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.body.readUtf8().contains("call_tenant_acme_inst_778899_native_log_456"))

        val finalDbCall = callDao.getById(call.id)
        assertEquals("SYNCED", finalDbCall?.syncStatus)
        assertEquals("call_tenant_acme_inst_778899_native_log_456", finalDbCall?.clientCallId)
    }
}
