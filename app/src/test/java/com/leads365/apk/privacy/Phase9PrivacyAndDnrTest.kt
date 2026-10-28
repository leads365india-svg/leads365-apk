package com.leads365.apk.privacy

import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.CachedPolicy
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.PolicyRepositoryImpl
import com.leads365.apk.network.api.PolicyApiService
import com.leads365.apk.network.models.PauseSyncRequest
import com.leads365.apk.network.models.PauseSyncResponse
import com.leads365.apk.network.models.PolicyData
import com.leads365.apk.network.models.PolicyResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Phase9PrivacyAndDnrTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePolicyApi: FakePolicyApiService
    private lateinit var policyRepository: PolicyRepositoryImpl

    @Before
    fun setup() {
        fakePolicyApi = FakePolicyApiService()
        val context = RuntimeEnvironment.getApplication()
        // Clear any cached prefs from previous test runs
        context.getSharedPreferences("leads365_policy_prefs", 0).edit().clear().apply()
        policyRepository = PolicyRepositoryImpl(
            policyApiService = fakePolicyApi,
            context = context,
            dispatcher = testDispatcher
        )
    }

    // ── Policy Sync Tests ──

    @Test
    fun `syncPolicy returns success and updates state`() = runTest(testDispatcher) {
        fakePolicyApi.nextPolicyResponse = PolicyResponse(
            success = true,
            data = PolicyData(
                agencyId = "agency_123",
                agencyName = "Test Agency",
                requireConsentReminder = true,
                consentNoticeText = "Test notice text",
                allowRecordingPause = true,
                recordingSyncPaused = false,
                enforceStrictDnr = true
            )
        )

        val result = policyRepository.syncPolicy()
        assertTrue("syncPolicy should succeed", result is AppResult.Success)

        val data = (result as AppResult.Success).data
        assertEquals("Test Agency", data.agencyName)
        assertEquals("agency_123", data.agencyId)
        assertTrue(data.requireConsentReminder)
        assertTrue(data.enforceStrictDnr)
        assertFalse(data.recordingSyncPaused)
        assertEquals("Test notice text", data.consentNoticeText)

        // Verify cached state is updated
        val cached = policyRepository.policyState.value
        assertEquals("Test Agency", cached.agencyName)
        assertEquals("agency_123", cached.agencyId)
        assertTrue(cached.enforceStrictDnr)
        assertTrue(cached.lastSyncEpochMs > 0)
    }

    @Test
    fun `syncPolicy returns failure on API error`() = runTest(testDispatcher) {
        fakePolicyApi.nextPolicyResponse = PolicyResponse(
            success = false,
            error = "Server error"
        )

        val result = policyRepository.syncPolicy()
        assertTrue("syncPolicy should fail", result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error.message.contains("Server error") || error.message.contains("Policy sync failed"))
    }

    @Test
    fun `syncPolicy returns failure when API throws exception`() = runTest(testDispatcher) {
        fakePolicyApi.shouldThrow = true

        val result = policyRepository.syncPolicy()
        assertTrue("syncPolicy should fail on exception", result is AppResult.Failure)
    }

    // ── DNR Enforcement Tests ──

    @Test
    fun `strict DNR enforcement is reflected in repository state`() = runTest(testDispatcher) {
        fakePolicyApi.nextPolicyResponse = PolicyResponse(
            success = true,
            data = PolicyData(enforceStrictDnr = true)
        )

        policyRepository.syncPolicy()
        assertTrue(policyRepository.isStrictDnrEnforced())
    }

    @Test
    fun `strict DNR enforcement false when server says so`() = runTest(testDispatcher) {
        fakePolicyApi.nextPolicyResponse = PolicyResponse(
            success = true,
            data = PolicyData(enforceStrictDnr = false)
        )

        policyRepository.syncPolicy()
        assertFalse(policyRepository.isStrictDnrEnforced())
    }

    // ── Consent Acknowledgment Tests ──

    @Test
    fun `consent starts unacknowledged`() {
        assertFalse(policyRepository.isConsentAcknowledged())
        assertFalse(policyRepository.consentAcknowledged.value)
    }

    @Test
    fun `acknowledging consent persists the state`() {
        policyRepository.acknowledgeConsent()
        assertTrue(policyRepository.isConsentAcknowledged())
        assertTrue(policyRepository.consentAcknowledged.value)
    }

    @Test
    fun `consent acknowledgment survives re-creation`() {
        policyRepository.acknowledgeConsent()

        // Create a new repository instance with the same context (simulates app restart)
        val context = RuntimeEnvironment.getApplication()
        val newRepo = PolicyRepositoryImpl(
            policyApiService = fakePolicyApi,
            context = context,
            dispatcher = testDispatcher
        )
        assertTrue("Consent should be persisted across instances", newRepo.isConsentAcknowledged())
    }

    // ── Pause Sync Tests ──

    @Test
    fun `pause sync defaults to false`() {
        assertFalse(policyRepository.isRecordingSyncPaused())
    }

    @Test
    fun `setPauseSyncState pauses recording sync`() = runTest(testDispatcher) {
        fakePolicyApi.nextPauseResponse = PauseSyncResponse(
            success = true,
            recordingSyncPaused = true,
            message = "Recording synchronization paused."
        )

        val result = policyRepository.setPauseSyncState(true)
        assertTrue(result is AppResult.Success)
        assertTrue((result as AppResult.Success).data)
        assertTrue(policyRepository.isRecordingSyncPaused())
    }

    @Test
    fun `setPauseSyncState resumes recording sync`() = runTest(testDispatcher) {
        // First pause
        fakePolicyApi.nextPauseResponse = PauseSyncResponse(
            success = true,
            recordingSyncPaused = true,
            message = "Paused"
        )
        policyRepository.setPauseSyncState(true)

        // Then resume
        fakePolicyApi.nextPauseResponse = PauseSyncResponse(
            success = true,
            recordingSyncPaused = false,
            message = "Resumed"
        )
        val result = policyRepository.setPauseSyncState(false)
        assertTrue(result is AppResult.Success)
        assertFalse((result as AppResult.Success).data)
        assertFalse(policyRepository.isRecordingSyncPaused())
    }

    @Test
    fun `setPauseSyncState returns failure on API error`() = runTest(testDispatcher) {
        fakePolicyApi.nextPauseResponse = PauseSyncResponse(
            success = false,
            error = "Unauthorized"
        )

        val result = policyRepository.setPauseSyncState(true)
        assertTrue(result is AppResult.Failure)
    }

    // ── Policy State Flow Tests ──

    @Test
    fun `policyState emits updated values after sync`() = runTest(testDispatcher) {
        val initialPolicy = policyRepository.policyState.value
        assertEquals("Leads365 Agency", initialPolicy.agencyName)
        assertEquals(0L, initialPolicy.lastSyncEpochMs)

        fakePolicyApi.nextPolicyResponse = PolicyResponse(
            success = true,
            data = PolicyData(
                agencyName = "Updated Agency",
                recordingSyncPaused = true,
                enforceStrictDnr = false
            )
        )

        policyRepository.syncPolicy()

        val updatedPolicy = policyRepository.policyState.value
        assertEquals("Updated Agency", updatedPolicy.agencyName)
        assertTrue(updatedPolicy.recordingSyncPaused)
        assertFalse(updatedPolicy.enforceStrictDnr)
        assertTrue(updatedPolicy.lastSyncEpochMs > 0)
    }

    @Test
    fun `full policy lifecycle - sync, consent, pause, resume`() = runTest(testDispatcher) {
        // 1. Sync policy
        fakePolicyApi.nextPolicyResponse = PolicyResponse(
            success = true,
            data = PolicyData(
                agencyId = "lifecycle_agency",
                agencyName = "Lifecycle Test Agency",
                requireConsentReminder = true,
                consentNoticeText = "Please acknowledge this notice.",
                allowRecordingPause = true,
                recordingSyncPaused = false,
                enforceStrictDnr = true
            )
        )

        val syncResult = policyRepository.syncPolicy()
        assertTrue(syncResult is AppResult.Success)

        // 2. Acknowledge consent
        assertFalse(policyRepository.isConsentAcknowledged())
        policyRepository.acknowledgeConsent()
        assertTrue(policyRepository.isConsentAcknowledged())

        // 3. Pause sync
        fakePolicyApi.nextPauseResponse = PauseSyncResponse(
            success = true,
            recordingSyncPaused = true
        )
        val pauseResult = policyRepository.setPauseSyncState(true)
        assertTrue(pauseResult is AppResult.Success)
        assertTrue(policyRepository.isRecordingSyncPaused())

        // 4. Resume sync
        fakePolicyApi.nextPauseResponse = PauseSyncResponse(
            success = true,
            recordingSyncPaused = false
        )
        val resumeResult = policyRepository.setPauseSyncState(false)
        assertTrue(resumeResult is AppResult.Success)
        assertFalse(policyRepository.isRecordingSyncPaused())

        // 5. Verify strict DNR is still enforced
        assertTrue(policyRepository.isStrictDnrEnforced())
    }

    // ── Fake API Service ──

    private class FakePolicyApiService : PolicyApiService {
        var nextPolicyResponse: PolicyResponse = PolicyResponse(
            success = true,
            data = PolicyData()
        )
        var nextPauseResponse: PauseSyncResponse = PauseSyncResponse(
            success = true,
            recordingSyncPaused = false
        )
        var shouldThrow: Boolean = false

        override suspend fun getPolicy(): PolicyResponse {
            if (shouldThrow) throw java.io.IOException("Network error")
            return nextPolicyResponse
        }

        override suspend fun setPauseSyncState(request: PauseSyncRequest): PauseSyncResponse {
            if (shouldThrow) throw java.io.IOException("Network error")
            return nextPauseResponse
        }
    }
}
