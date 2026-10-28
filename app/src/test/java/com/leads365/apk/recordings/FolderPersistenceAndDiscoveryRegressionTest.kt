package com.leads365.apk.recordings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.recordings.di.RecordingsModule
import com.leads365.apk.ui.MainViewModel
import com.leads365.apk.ui.calls.CallsViewModel
import com.leads365.apk.ui.calls.CallFilterTab
import com.leads365.apk.ui.home.HomeViewModel
import com.leads365.apk.ui.navigation.NavDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class FolderPersistenceAndDiscoveryRegressionTest {

    private lateinit var context: Context

    private val sampleCandidates = (1..11).map { index ->
        RecordingCandidate(
            uriString = "content://com.android.externalstorage.documents/document/primary%3ARecord%2FCall%2Fcall_rec_$index.m4a",
            displayName = "call_rec_$index.m4a",
            sizeBytes = 150_000L + (index * 10_000L),
            lastModifiedEpochMs = 1700000000000L + (index * 60_000L),
            mimeType = "audio/m4a",
            durationSeconds = null,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
            isReadable = true
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // Clear canonical and wizard prefs
        context.getSharedPreferences(RecordingsModule.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("leads365_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testFolderSelectionPersistsInCanonicalPreferences() {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARecord%2FCall")
        val safSource = TestableSafRecordingSource(context, uriProvider = {
            context.getSharedPreferences(RecordingsModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RecordingsModule.KEY_SAF_URI, null)
        }, candidates = sampleCandidates)

        val mainViewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            safRecordingSource = safSource,
            mediaStoreRecordingSource = MediaStoreRecordingSource(context),
            matcher = TargetedRecordingMatcher(com.leads365.apk.core.PhoneNumberNormalizer())
        )

        // Select the folder
        mainViewModel.setSafTreeUri(testUri)

        // Check canonical prefs
        val canonicalPrefs = context.getSharedPreferences(RecordingsModule.PREFS_NAME, Context.MODE_PRIVATE)
        val savedCanonical = canonicalPrefs.getString(RecordingsModule.KEY_SAF_URI, null)
        assertNotNull("Canonical preferences must store SAF URI", savedCanonical)
        assertEquals(testUri.toString(), savedCanonical)

        // Check phase 1 wizard prefs compatibility
        val phase1Prefs = context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE)
        assertEquals(testUri.toString(), phase1Prefs.getString(RecordingsModule.KEY_SAF_URI, null))
    }

    @Test
    fun testObservedRegression_FolderSelectionThenNavigateAndReturn_Preserves11Recordings() = runBlocking {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARecord%2FCall")
        val safSource = TestableSafRecordingSource(context, uriProvider = {
            context.getSharedPreferences(RecordingsModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RecordingsModule.KEY_SAF_URI, null)
        }, candidates = sampleCandidates)

        val fakeCallRepo = FakeCallRepository()
        val fakeUploadRepo = FakeUploadQueueRepository()

        val callsViewModel = CallsViewModel(
            callRepository = fakeCallRepo,
            uploadQueueRepository = fakeUploadRepo,
            safRecordingSource = safSource,
            mediaStoreRecordingSource = MediaStoreRecordingSource(context),
            context = context
        )

        val mainViewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            safRecordingSource = safSource,
            mediaStoreRecordingSource = MediaStoreRecordingSource(context),
            matcher = TargetedRecordingMatcher(com.leads365.apk.core.PhoneNumberNormalizer())
        )

        // Step 1: Before folder is selected, recordingsCount is 0
        assertEquals(0, callsViewModel.recordingsCount.value)

        // Step 2: User opens Setup Wizard and picks Record/Call
        mainViewModel.setSafTreeUri(testUri)
        callsViewModel.refreshDiscoveredRecordings(Dispatchers.Unconfined)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Step 3: Verify CallsViewModel loaded all 11 candidates
        assertEquals(11, callsViewModel.recordingsCount.value)
        assertTrue(callsViewModel.isRecordingAccessAvailable.value)

        // Step 4: Simulate navigating to WITH_RECORDINGS tab
        callsViewModel.setFilter(CallFilterTab.WITH_RECORDINGS)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val withRecItems = callsViewModel.callItems.value
        assertEquals(11, withRecItems.size)
        assertTrue("All items must be candidate only", withRecItems.all { it.isCandidateOnly })
        assertTrue("All items must be Unmatched", withRecItems.all { it.discoveryState == "Unmatched" })

        // Step 5: Simulate navigating away (e.g. to sign in) and navigating back
        // In CallsScreen, LaunchedEffect(Unit) calls refreshDiscoveredRecordings()
        callsViewModel.refreshDiscoveredRecordings(Dispatchers.Unconfined)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Verify the 11 recordings are STILL present and folder access is STILL active!
        assertEquals("Recordings count must remain 11 after navigation", 11, callsViewModel.recordingsCount.value)
        assertTrue("Folder access must remain active after navigation", callsViewModel.isRecordingAccessAvailable.value)
        assertEquals(11, callsViewModel.callItems.value.size)
    }

    @Test
    fun testHomeViewModel_Reflects11DiscoveredRecordingsConsistently() = runBlocking {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARecord%2FCall")
        val safSource = TestableSafRecordingSource(context, uriProvider = {
            context.getSharedPreferences(RecordingsModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RecordingsModule.KEY_SAF_URI, null)
        }, candidates = sampleCandidates)

        val fakeCallRepo = FakeCallRepository()
        val fakeLeadRepo = FakeLeadRepository()
        val fakeUploadRepo = FakeUploadQueueRepository()
        val fakeAuthRepo = FakeAuthRepository()
        val fakeScheduler = com.leads365.apk.work.WorkManagerScheduler(context, WorkManager.getInstance(context))

        val homeViewModel = HomeViewModel(
            callRepository = fakeCallRepo,
            leadRepository = fakeLeadRepo,
            uploadQueueRepository = fakeUploadRepo,
            safRecordingSource = safSource,
            mediaStoreRecordingSource = MediaStoreRecordingSource(context),
            authRepository = fakeAuthRepo,
            workManagerScheduler = fakeScheduler,
            context = context
        )

        // Before selection
        assertEquals(0, homeViewModel.discoveredRecordingsCount.value)

        // Persist folder
        context.getSharedPreferences(RecordingsModule.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(RecordingsModule.KEY_SAF_URI, testUri.toString()).commit()
        homeViewModel.refreshReadiness(Dispatchers.Unconfined)

        // HomeViewModel must reflect 11 discovered recordings and active readiness
        assertEquals(11, homeViewModel.discoveredRecordingsCount.value)
        assertTrue(homeViewModel.isRecordingAccessAvailable.value)
    }

    @Test
    fun testNavigationDestinationsContainApprovedFourPrimaryTabs() {
        val items = NavDestination.items
        assertEquals(4, items.size)
        assertEquals(listOf(NavDestination.Home, NavDestination.Leads, NavDestination.Calls, NavDestination.Settings), items)
        assertEquals("home", NavDestination.Home.route)
        assertEquals("leads", NavDestination.Leads.route)
        assertEquals("calls", NavDestination.Calls.route)
        assertEquals("settings", NavDestination.Settings.route)
    }

    // Testable SAF Recording Source that returns injected sample candidates for Robolectric
    private class TestableSafRecordingSource(
        context: Context,
        val uriProvider: () -> String?,
        val candidates: List<RecordingCandidate>
    ) : SafRecordingSource(context, uriProvider, hasPersistedPermission = { true }) {

        override fun isConfigured(): Boolean = !uriProvider().isNullOrBlank()
        override fun isAccessible(): Boolean = !uriProvider().isNullOrBlank()

        override fun listDiscoveredRecordings(limit: Int): List<RecordingCandidate> {
            if (!isAccessible()) return emptyList()
            return candidates.take(limit)
        }

        override fun inspectFolderContent(): SafFolderInspection {
            if (!isAccessible()) {
                return SafFolderInspection(
                    isAccessible = false,
                    totalFilesCount = 0,
                    audioFilesCount = 0,
                    subfoldersCount = 0,
                    latestAudioFileName = null,
                    latestAudioModifiedMs = null,
                    messageEn = "No folder selected."
                )
            }
            return SafFolderInspection(
                isAccessible = true,
                totalFilesCount = candidates.size,
                audioFilesCount = candidates.size,
                subfoldersCount = 0,
                latestAudioFileName = candidates.firstOrNull()?.displayName,
                latestAudioModifiedMs = candidates.firstOrNull()?.lastModifiedEpochMs,
                messageEn = "Found ${candidates.size} audio files."
            )
        }
    }

    private class FakeCallRepository : CallRepository {
        val callsFlow = MutableStateFlow<List<CallEntity>>(emptyList())
        override fun getAllCallsFlow(): Flow<List<CallEntity>> = callsFlow
        override fun getLeadCallsFlow(): Flow<List<CallEntity>> = flowOf(emptyList())
        override fun getCallsForNumberFlow(normalizedNumber: String): Flow<List<CallEntity>> = flowOf(emptyList())
        override suspend fun getRecentCalls(limit: Int): com.leads365.apk.core.AppResult<List<CallEntity>> =
            com.leads365.apk.core.AppResult.Success(emptyList())
        override suspend fun getCallById(id: String): com.leads365.apk.core.AppResult<CallEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun getByClientCallId(clientCallId: String): com.leads365.apk.core.AppResult<CallEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun findCallBySignature(normalizedNumber: String, startTimeEpochMs: Long, toleranceMs: Long): com.leads365.apk.core.AppResult<CallEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun recordCall(call: CallEntity): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun updateCall(call: CallEntity): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun updateOutcome(callId: String, outcome: String, notes: String?, nextFollowUpEpochMs: Long?): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun updateRecordingMatch(callId: String, hasRecording: Boolean, recordingUri: String?, recordingFileName: String?, recordingFileSizeBytes: Long?): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun getPendingSyncCalls(): com.leads365.apk.core.AppResult<List<CallEntity>> =
            com.leads365.apk.core.AppResult.Success(emptyList())
        override suspend fun reportCallToBackend(callId: String): com.leads365.apk.core.AppResult<com.leads365.apk.network.models.CallReportResponse> =
            com.leads365.apk.core.AppResult.Failure(com.leads365.apk.core.AppError.NetworkError("Not mocked"))
        override suspend fun deleteCallById(id: String): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun clearAll(): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
    }

    private class FakeUploadQueueRepository : UploadQueueRepository {
        val tasksFlow = MutableStateFlow<List<UploadTaskEntity>>(emptyList())
        override fun getAllTasksFlow(): Flow<List<UploadTaskEntity>> = tasksFlow
        override suspend fun enqueueTask(task: UploadTaskEntity): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun getPendingTasks(): com.leads365.apk.core.AppResult<List<UploadTaskEntity>> =
            com.leads365.apk.core.AppResult.Success(emptyList())
        override suspend fun getTasksByStatus(status: String): com.leads365.apk.core.AppResult<List<UploadTaskEntity>> =
            com.leads365.apk.core.AppResult.Success(emptyList())
        override suspend fun getTaskById(id: String): com.leads365.apk.core.AppResult<UploadTaskEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun getTaskByCallId(callId: String): com.leads365.apk.core.AppResult<UploadTaskEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun updateStatus(id: String, status: String, error: String?): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun incrementRetry(id: String, error: String): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun deleteTaskById(id: String): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun clearCompleted(): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
    }

    private class FakeLeadRepository : LeadRepository {
        override fun getAllLeadsFlow(): Flow<List<com.leads365.apk.database.entity.LeadEntity>> = flowOf(emptyList())
        override fun searchLeadsFlow(query: String, status: String): Flow<List<com.leads365.apk.database.entity.LeadEntity>> = flowOf(emptyList())
        override suspend fun getAllLeads(): com.leads365.apk.core.AppResult<List<com.leads365.apk.database.entity.LeadEntity>> =
            com.leads365.apk.core.AppResult.Success(emptyList())
        override suspend fun getLeadById(id: String): com.leads365.apk.core.AppResult<com.leads365.apk.database.entity.LeadEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun getLeadByNormalizedNumber(normalizedNumber: String): com.leads365.apk.core.AppResult<com.leads365.apk.database.entity.LeadEntity?> =
            com.leads365.apk.core.AppResult.Success(null)
        override suspend fun saveLead(lead: com.leads365.apk.database.entity.LeadEntity): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun saveLeads(leads: List<com.leads365.apk.database.entity.LeadEntity>): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun deleteLeadById(id: String): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun clearAllLeads(): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override suspend fun syncLeads(forceFullSync: Boolean): com.leads365.apk.core.AppResult<com.leads365.apk.data.repository.LeadSyncResult> =
            com.leads365.apk.core.AppResult.Success(com.leads365.apk.data.repository.LeadSyncResult(0, 0, false, 0L))
        override fun getLastSyncTimestamp(): Long = 0L
    }

    private class FakeAuthRepository : com.leads365.apk.data.repository.AuthRepository {
        override val authState: MutableStateFlow<com.leads365.apk.data.repository.AuthState> =
            MutableStateFlow(com.leads365.apk.data.repository.AuthState.Unauthenticated)
        override fun isLoggedIn(): Boolean = false
        override fun getCurrentUser(): com.leads365.apk.network.models.UserDto? = null
        override fun getAgencyName(): String? = null
        override fun getDeviceId(): String? = null
        override suspend fun login(email: String, password: String): com.leads365.apk.core.AppResult<com.leads365.apk.network.models.AuthData> =
            com.leads365.apk.core.AppResult.Failure(com.leads365.apk.core.AppError.NetworkError("Not mocked"))
        override suspend fun registerDevice(
            deviceModel: String,
            manufacturer: String,
            osVersion: String,
            sdkInt: Int,
            appVersionCode: Int,
            appVersionName: String,
            fcmToken: String?
        ): com.leads365.apk.core.AppResult<com.leads365.apk.network.models.DeviceData> =
            com.leads365.apk.core.AppResult.Failure(com.leads365.apk.core.AppError.NetworkError("Not mocked"))
        override suspend fun updatePushToken(token: String): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override fun getSavedFcmToken(): String? = null
        override fun saveFcmToken(token: String) {}
        override suspend fun logout(preservePendingUploads: Boolean): com.leads365.apk.core.AppResult<Unit> =
            com.leads365.apk.core.AppResult.Success(Unit)
        override fun invalidateSession(reason: String?) {}
    }
}
