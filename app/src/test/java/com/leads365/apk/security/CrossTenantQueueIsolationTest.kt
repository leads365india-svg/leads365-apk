package com.leads365.apk.security

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.AuthRepositoryImpl
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.auth.AccessTokenInterceptor
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.recordings.RecordingUploadManager
import com.leads365.apk.recordings.UploadExecutionResult
import com.leads365.apk.calls.CallProcessingManager
import com.leads365.apk.calls.CallProcessingResult
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class CrossTenantQueueIsolationTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var workManager: WorkManager
    private lateinit var mockWebServer: MockWebServer
    private lateinit var authApiService: AuthApiService
    private lateinit var callApiService: CallApiService
    private lateinit var leadApiService: LeadApiService
    private lateinit var encryptedTokenProvider: EncryptedTokenProvider
    private lateinit var normalizer: PhoneNumberNormalizer
    private val capturedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private var nextLoginTenant = "ACC-TENANT-A"
    private var nextLoginWorkspace = "WS-A1"
    private var nextLoginToken = "tok_tenant_a"
    private var failNetworkCalls = false
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        // Restore network while Tenant B tries to sync/upload -> must still send ZERO HTTP requests for Tenant A
        failNetworkCalls = false
        capturedRequests.clear()
        failNetworkCalls = false

        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                capturedRequests.add(request)
                val path = request.path ?: ""
                return when {
                    path.endsWith("/auth/login") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "data": {
                            "accessToken": "$nextLoginToken",
                            "refreshToken": "ref_$nextLoginToken",
                            "user": { "id": "user_${nextLoginTenant}", "name": "Agent", "email": "agent@${nextLoginTenant.lowercase()}.test", "role": "agent" },
                            "agency": { "id": "ag_${nextLoginTenant}", "name": "Agency $nextLoginTenant", "accountId": "$nextLoginTenant" },
                            "workspaces": [{ "id": "$nextLoginWorkspace", "name": "Primary", "isDefault": true }],
                            "activeWorkspaceId": "$nextLoginWorkspace"
                          }
                        }
                        """.trimIndent()
                    )
                    path.endsWith("/auth/logout") -> MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    failNetworkCalls -> MockResponse().setResponseCode(503).setBody("""{"success":false,"error":"offline"}""")
                    path.contains("/recording") -> MockResponse().setResponseCode(200).setBody("""{"success":true,"recordingUrl":"/uploads/a1.m4a"}""")
                    path.endsWith("/calls") -> MockResponse().setResponseCode(200).setBody("""{"success":true,"call_id":"srv_call_1","message":"ok"}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                }
            }
        }
        mockWebServer.start()

        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()

        encryptedTokenProvider = EncryptedTokenProvider(context)
        encryptedTokenProvider.clearAllSession()
        normalizer = PhoneNumberNormalizer("IN")

        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(encryptedTokenProvider))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        authApiService = retrofit.create(AuthApiService::class.java)
        callApiService = retrofit.create(CallApiService::class.java)
        leadApiService = retrofit.create(LeadApiService::class.java)
    }

    @After
    fun teardown() {
        encryptedTokenProvider.clearAllSession()
        database.close()
        mockWebServer.shutdown()
    }

    @Test
    fun `tenant A offline calls and uploads are quarantined under tenant B and recover cleanly when tenant A returns`() = runBlocking {
        val uploadRepo = UploadQueueRepositoryImpl(database.uploadTaskDao(), Dispatchers.Unconfined)
        val scheduler = WorkManagerScheduler(
            context = context,
            workManager = workManager,
            uploadQueueRepository = uploadRepo,
            policyRepository = null,
            tokenProvider = encryptedTokenProvider
        )
        val authRepo = AuthRepositoryImpl(
            authApiService = authApiService,
            encryptedTokenProvider = encryptedTokenProvider,
            leadDao = database.leadDao(),
            callDao = database.callDao(),
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined,
            context = context,
            workManagerScheduler = scheduler
        )
        val callRepo = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = callApiService,
            tokenProvider = encryptedTokenProvider,
            uploadTaskDao = database.uploadTaskDao(),
            recordingDiscoveryCoordinatorProvider = null,
            context = context,
            leadDao = database.leadDao()
        )
        val leadRepo = LeadRepositoryImpl(
            leadDao = database.leadDao(),
            leadApiService = leadApiService,
            context = context,
            normalizer = normalizer,
            callRepositoryProvider = null,
            directCallRepository = callRepo,
            tokenProvider = encryptedTokenProvider
        )
        val cpm = CallProcessingManager(
            leadRepository = leadRepo,
            callRepository = callRepo,
            pendingCallManager = PendingCallManager(context, normalizer),
            normalizer = normalizer,
            recordingDiscoveryCoordinator = null,
            tokenProvider = encryptedTokenProvider
        )

        // 1. Login as Tenant A (WS-A1)
        nextLoginTenant = "ACC-TENANT-A"
        nextLoginWorkspace = "WS-A1"
        nextLoginToken = "tok_tenant_a"
        val loginA = authRepo.login("a@alpha.test", "passA")
        assertTrue(loginA is AppResult.Success)
        assertEquals("ACC-TENANT-A", encryptedTokenProvider.getAgencyAccountId())
        assertEquals("WS-A1", encryptedTokenProvider.getActiveWorkspaceId())

        // 2. Tenant A goes offline, receives call & queues pending recording upload task
        failNetworkCalls = true
        val leadA = LeadEntity(
            id = "lead_a1",
            name = "Lead Alpha",
            rawNumber = "+919800011122",
            normalizedNumber = "+919800011122",
            tenantId = "ACC-TENANT-A",
            workspaceId = "WS-A1"
        )
        database.leadDao().insert(leadA)

        val callRes = cpm.processCallEvent(
            CallEvent(
                id = "native_a_1",
                rawNumber = "+919800011122",
                normalizedNumber = "+919800011122",
                startTimeEpochMs = 1710000000000L,
                endTimeEpochMs = 1710000045000L,
                durationSeconds = 45L,
                direction = CallDirection.OUTGOING
            )
        )
        assertTrue(callRes is CallProcessingResult.Recorded)
        val callA = (callRes as CallProcessingResult.Recorded).call
        assertEquals("ACC-TENANT-A", callA.tenantId)
        assertEquals("WS-A1", callA.workspaceId)

        val audioBytes = "TENANT_A_PRIVATE_AUDIO".toByteArray(Charsets.UTF_8)
        val taskA = UploadTaskEntity(
            id = "task_a1",
            callId = callA.clientCallId,
            leadId = "lead_a1",
            localFileUri = "content://media/audio/a1.m4a",
            fileName = "a1.m4a",
            fileSizeBytes = audioBytes.size.toLong(),
            status = UploadTaskEntity.STATUS_PENDING,
            tenantId = "ACC-TENANT-A",
            workspaceId = "WS-A1"
        )
        database.uploadTaskDao().insert(taskA)

        // 3. Tenant A logs out (preserving recoverable uploads)
        val logoutRes = authRepo.logout(preservePendingUploads = true)
        assertTrue(logoutRes is AppResult.Success)
        assertEquals(0, database.leadDao().count())

        val taskAfterLogout = database.uploadTaskDao().getById("task_a1")
        assertNotNull("Offline recording task must be preserved after logout", taskAfterLogout)
        assertEquals("content://media/audio/a1.m4a", taskAfterLogout?.localFileUri)
        assertEquals("ACC-TENANT-A", taskAfterLogout?.tenantId)
        assertEquals("WS-A1", taskAfterLogout?.workspaceId)
        assertEquals(UploadTaskEntity.STATE_TENANT_MISMATCH, taskAfterLogout?.status)

        // 4. Tenant B logs in
        nextLoginTenant = "ACC-TENANT-B"
        nextLoginWorkspace = "WS-B1"
        nextLoginToken = "tok_tenant_b"
        val loginB = authRepo.login("b@beta.test", "passB")
        assertTrue(loginB is AppResult.Success)
        // Restore network while Tenant B tries to sync/upload -> must still send ZERO HTTP requests for Tenant A
        failNetworkCalls = false
        capturedRequests.clear()

        // 5. Verify WorkManager, CallRepository, and RecordingUploadManager ALL block Tenant A's data under Tenant B
        val resumedByB = scheduler.resumePendingUploads()
        assertEquals("Tenant B must schedule 0 Tenant A uploads", 0, resumedByB)

        val syncCallsB = callRepo.syncPendingCallsToBackend()
        assertEquals(0, (syncCallsB as AppResult.Success).data)

        val reportDirectB = callRepo.reportCallToBackend(callA.clientCallId)
        assertTrue("Direct report under Tenant B must fail", reportDirectB is AppResult.Failure)

        val uploader = object : RecordingUploadManager(
            context = context,
            okHttpClient = OkHttpClient.Builder().addInterceptor(AccessTokenInterceptor(encryptedTokenProvider)).build(),
            tokenProvider = encryptedTokenProvider,
            baseUrl = mockWebServer.url("/").toString()
        ) {
            override fun openStream(uriString: String): InputStream = ByteArrayInputStream(audioBytes)
        }
        val uploadResultB = uploader.uploadRecording(database.uploadTaskDao().getById("task_a1")!!)
        assertTrue("RecordingUploadManager must quarantine cross-tenant upload", uploadResultB is UploadExecutionResult.QuarantinedTenantMismatch)

        // CRITICAL PROOF: ZERO calls or recording uploads sent under Tenant B!
        assertEquals("Zero HTTP requests allowed for Tenant A data under Tenant B", 0, capturedRequests.size)
        assertEquals(UploadTaskEntity.STATE_TENANT_MISMATCH, database.uploadTaskDao().getById("task_a1")?.status)

        // 6. Tenant B logs out and Tenant A logs back in -> quarantined call & audio recover cleanly!
        assertTrue(authRepo.logout(preservePendingUploads = true) is AppResult.Success)

        nextLoginTenant = "ACC-TENANT-A"
        nextLoginWorkspace = "WS-A1"
        nextLoginToken = "tok_tenant_a_2"
        val reLoginA = authRepo.login("a@alpha.test", "passA")
        assertTrue(reLoginA is AppResult.Success)

        val restoredTask = database.uploadTaskDao().getById("task_a1")!!
        val restoredCall = database.callDao().getById(callA.id)!!
        assertEquals("Tenant A's task must be restored to PENDING", UploadTaskEntity.STATUS_PENDING, restoredTask.status)
        assertEquals("Tenant A's call must be restored to PENDING", "PENDING", restoredCall.syncStatus)

        // Restore network while Tenant B tries to sync/upload -> must still send ZERO HTTP requests for Tenant A
        failNetworkCalls = false
        capturedRequests.clear()
        val uploadResultA = uploader.uploadRecording(restoredTask)
        assertTrue("Restored upload must succeed for Tenant A", uploadResultA is UploadExecutionResult.Success)

        val putReq = capturedRequests.firstOrNull { it.method == "PUT" }
        assertNotNull("Expected PUT upload request after Tenant A re-login", putReq)
        assertEquals("Bearer tok_tenant_a_2", putReq?.getHeader("Authorization"))
        assertEquals("WS-A1", putReq?.getHeader("X-Workspace-Id"))
    }

    @Test
    fun `invalidateSession async cleanup never races with subsequent login`() = runBlocking {
        val uploadRepo = UploadQueueRepositoryImpl(database.uploadTaskDao(), Dispatchers.Unconfined)
        val scheduler = WorkManagerScheduler(context, workManager, uploadRepo, null, encryptedTokenProvider)
        val authRepo = AuthRepositoryImpl(
            authApiService = authApiService,
            encryptedTokenProvider = encryptedTokenProvider,
            leadDao = database.leadDao(),
            callDao = database.callDao(),
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined,
            context = context,
            workManagerScheduler = scheduler
        )

        database.leadDao().insert(LeadEntity(id = "stale_a", name = "Old A", rawNumber = "+919000000001", normalizedNumber = "+919000000001", tenantId = "ACC-TENANT-A"))
        encryptedTokenProvider.saveSessionInfo("u_a", "A", "a@a.test", "A", "ACC-TENANT-A")
        encryptedTokenProvider.saveTokens("tok_old", "ref_old")

        authRepo.invalidateSession("Revoked")
        nextLoginTenant = "ACC-TENANT-B"
        nextLoginWorkspace = "WS-B1"
        nextLoginToken = "tok_b"
        val loginB = authRepo.login("b@b.test", "passB")
        assertTrue(loginB is AppResult.Success)
        assertEquals("ACC-TENANT-B", encryptedTokenProvider.getAgencyAccountId())
        assertEquals("tok_b", encryptedTokenProvider.getAccessToken())
        assertEquals(0, database.leadDao().count())
    }

    @Test
    fun `legacy null-tenant calls never attribute to Tenant B and recover only unambiguous canonical Tenant A records`() = runBlocking {
        val uploadRepo = UploadQueueRepositoryImpl(database.uploadTaskDao(), Dispatchers.Unconfined)
        val scheduler = WorkManagerScheduler(context, workManager, uploadRepo, null, encryptedTokenProvider)
        val callRepo = CallRepositoryImpl(
            callDao = database.callDao(),
            callApiService = callApiService,
            tokenProvider = encryptedTokenProvider,
            uploadTaskDao = database.uploadTaskDao(),
            recordingDiscoveryCoordinatorProvider = null,
            context = context,
            leadDao = database.leadDao()
        )
        val authRepo = AuthRepositoryImpl(
            authApiService = authApiService,
            encryptedTokenProvider = encryptedTokenProvider,
            leadDao = database.leadDao(),
            callDao = database.callDao(),
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined,
            context = context,
            workManagerScheduler = scheduler
        )

        // 1. Seed legacy post-v7->v8 rows with tenantId = null:
        //    (a) ambiguous DEFERRED_IDENTITY call + upload task (cannot safely prove tenant)
        //    (b) canonical call_<TENANT_A>_... call + linked upload task (unambiguously owned by Tenant A)
        database.callDao().insert(
            com.leads365.apk.database.entity.CallEntity(
                id = "ambig_1",
                clientCallId = "local_pending_call_ambig_1",
                normalizedNumber = "+919811111111",
                rawNumber = "+919811111111",
                callType = 1,
                startTimeEpochMs = 1700000100000L,
                durationSeconds = 30L,
                isLeadCall = false,
                syncStatus = "DEFERRED_IDENTITY",
                hasRecording = true,
                recordingUri = "content://media/legacy_ambig_1.m4a",
                tenantId = null
            )
        )
        database.uploadTaskDao().insert(
            UploadTaskEntity(
                id = "task_ambig_1",
                callId = "ambig_1",
                localFileUri = "content://media/legacy_ambig_1.m4a",
                fileName = "legacy_ambig_1.m4a",
                fileSizeBytes = 1024L,
                status = UploadTaskEntity.STATUS_PENDING,
                tenantId = null
            )
        )
        database.callDao().insert(
            com.leads365.apk.database.entity.CallEntity(
                id = "canon_a_1",
                clientCallId = "call_ACC-TENANT-A_dev1_999",
                normalizedNumber = "+919822222222",
                rawNumber = "+919822222222",
                callType = 2,
                startTimeEpochMs = 1700000200000L,
                durationSeconds = 45L,
                isLeadCall = true,
                leadId = "lead_canon_a",
                syncStatus = "PENDING",
                hasRecording = true,
                recordingUri = "content://media/legacy_canon_a.m4a",
                tenantId = null
            )
        )
        database.uploadTaskDao().insert(
            UploadTaskEntity(
                id = "task_canon_a_1",
                callId = "canon_a_1",
                leadId = "lead_canon_a",
                localFileUri = "content://media/legacy_canon_a.m4a",
                fileName = "legacy_canon_a.m4a",
                fileSizeBytes = 2048L,
                status = UploadTaskEntity.STATUS_PENDING,
                tenantId = null
            )
        )

        // 2. Unauthenticated scheduler resume must schedule 0 uploads
        assertEquals(0, scheduler.resumePendingUploads())
        assertEquals(0, (callRepo.syncPendingCallsToBackend() as AppResult.Success).data)

        // 3. Tenant B logs in -> must quarantine BOTH without deleting URIs and upload ZERO items
        nextLoginTenant = "ACC-TENANT-B"
        nextLoginWorkspace = "WS-B1"
        nextLoginToken = "tok_tenant_b"
        assertTrue(authRepo.login("b@beta.test", "passB") is AppResult.Success)
        capturedRequests.clear()

        assertEquals(0, scheduler.resumePendingUploads())
        assertEquals(0, (callRepo.syncPendingCallsToBackend() as AppResult.Success).data)
        assertEquals(0, capturedRequests.size)

        val ambigAfterB = database.callDao().getById("ambig_1")!!
        val taskAmbigAfterB = database.uploadTaskDao().getById("task_ambig_1")!!
        assertEquals(com.leads365.apk.database.entity.CallEntity.SYNC_STATUS_QUARANTINED, ambigAfterB.syncStatus)
        assertEquals(UploadTaskEntity.STATE_TENANT_MISMATCH, taskAmbigAfterB.status)
        assertEquals("content://media/legacy_ambig_1.m4a", ambigAfterB.recordingUri)
        assertEquals("content://media/legacy_ambig_1.m4a", taskAmbigAfterB.localFileUri)

        // 4. Tenant A logs in -> canonical Tenant A record recovers safely; ambiguous null-tenant stays quarantined!
        assertTrue(authRepo.logout(preservePendingUploads = true) is AppResult.Success)
        nextLoginTenant = "ACC-TENANT-A"
        nextLoginWorkspace = "WS-A1"
        nextLoginToken = "tok_tenant_a"
        assertTrue(authRepo.login("a@alpha.test", "passA") is AppResult.Success)

        val canonAfterA = database.callDao().getById("canon_a_1")!!
        val taskCanonAfterA = database.uploadTaskDao().getById("task_canon_a_1")!!
        assertEquals("ACC-TENANT-A", canonAfterA.tenantId)
        assertEquals("PENDING", canonAfterA.syncStatus)
        assertEquals("ACC-TENANT-A", taskCanonAfterA.tenantId)
        assertEquals(UploadTaskEntity.STATUS_PENDING, taskCanonAfterA.status)

        val ambigAfterA = database.callDao().getById("ambig_1")!!
        val taskAmbigAfterA = database.uploadTaskDao().getById("task_ambig_1")!!
        assertNull("Ambiguous call must retain null tenantId", ambigAfterA.tenantId)
        assertEquals(com.leads365.apk.database.entity.CallEntity.SYNC_STATUS_QUARANTINED, ambigAfterA.syncStatus)
        assertEquals(UploadTaskEntity.STATE_TENANT_MISMATCH, taskAmbigAfterA.status)
    }
}
