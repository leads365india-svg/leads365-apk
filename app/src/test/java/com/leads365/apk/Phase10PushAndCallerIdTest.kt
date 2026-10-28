package com.leads365.apk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.calls.CallerIdentificationManager
import com.leads365.apk.calls.CallerOverlayManager
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.network.auth.InMemoryTokenProvider
import com.leads365.apk.notifications.LeadsNotificationManager
import com.leads365.apk.notifications.PushMessageProcessor
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase10PushAndCallerIdTest {

    private lateinit var context: Context
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var pendingCallManager: PendingCallManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        normalizer = PhoneNumberNormalizer("IN")
        pendingCallManager = PendingCallManager(context, normalizer)
        LeadsNotificationManager.initChannels(context)
    }

    // -------------------------------------------------------------
    // Test 1: Push Message Processing - LEAD_ASSIGNED triggers sync
    // -------------------------------------------------------------
    @Test
    fun testLeadAssignedPushTriggersSyncAndDisplaysNotification() {
        var syncTriggeredCount = 0

        val fakeScheduler = object : WorkManagerScheduler(
            context,
            androidx.work.WorkManager.getInstance(context)
        ) {
            override fun triggerOneTimeLeadSync(forceFullSync: Boolean) {
                syncTriggeredCount++
            }
        }

        val processor = PushMessageProcessor(fakeScheduler)

        val payload = mapOf(
            "eventType" to "LEAD_ASSIGNED",
            "leadId" to "lead_test_101",
            "name" to "Rohan Sharma",
            "status" to "New",
            "notes" to "Interested in health policy"
        )

        val handled = processor.processPushPayload(context, payload)
        assertTrue("Processor must handle LEAD_ASSIGNED payload", handled)
        assertEquals("Lead assigned event must trigger background lead sync", 1, syncTriggeredCount)

        // Deduplication test: identical event within dedupe window is ignored
        val duplicateHandled = processor.processPushPayload(context, payload)
        assertFalse("Duplicate push event within window must be ignored", duplicateHandled)
        assertEquals("Duplicate push must not trigger redundant sync", 1, syncTriggeredCount)
    }

    // -------------------------------------------------------------
    // Test 2: Push Message Processing - LEAD_REASSIGNED_AWAY triggers sync
    // -------------------------------------------------------------
    @Test
    fun testLeadReassignedAwayTriggersSyncToClearTombstone() {
        var syncTriggeredCount = 0

        val fakeScheduler = object : WorkManagerScheduler(
            context,
            androidx.work.WorkManager.getInstance(context)
        ) {
            override fun triggerOneTimeLeadSync(forceFullSync: Boolean) {
                syncTriggeredCount++
            }
        }

        val processor = PushMessageProcessor(fakeScheduler)

        val payload = mapOf(
            "eventType" to "LEAD_REASSIGNED_AWAY",
            "leadId" to "lead_test_102"
        )

        val handled = processor.processPushPayload(context, payload)
        assertTrue("Processor must handle LEAD_REASSIGNED_AWAY payload", handled)
        assertEquals("Reassigned away event must trigger sync to process tombstone", 1, syncTriggeredCount)
    }

    // -------------------------------------------------------------
    // Test 3: Caller ID resolves assigned lead and shows overlay when permitted
    // -------------------------------------------------------------
    @Test
    fun testCallerIdShowsOverlayWhenPermissionGranted() = runTest {
        val assignedLead = LeadEntity(
            id = "lead_assigned_1",
            name = "Ananya Desai",
            rawNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            status = "Engaged",
            notes = "Spoke on Tuesday. Follow up on quote."
        )

        val fakeLeadRepo = object : FakeLeadRepository(listOf(assignedLead)) {}

        var overlayShown = false
        var overlayLeadId: String? = null

        val fakeOverlayManager = object : CallerOverlayManager() {
            override fun canDrawOverlays(ctx: Context): Boolean = true
            override fun showCallerOverlay(
                ctx: Context,
                leadId: String,
                leadName: String,
                leadStatus: String,
                lastNote: String?,
                normalizedNumber: String
            ): Boolean {
                overlayShown = true
                overlayLeadId = leadId
                return true
            }
            override fun dismissOverlay() {
                overlayShown = false
            }
        }

        val callerIdManager = CallerIdentificationManager(
            leadRepository = fakeLeadRepo,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            callerOverlayManager = fakeOverlayManager,
            scope = this
        )

        // Incoming call from assigned lead
        callerIdManager.onCallStarted(context, "+919876543210")
        testScheduler.advanceUntilIdle()

        assertEquals("Caller ID must resolve assigned lead", assignedLead, callerIdManager.getActiveCallerLead())
        assertTrue("Overlay must be displayed when overlay permission is granted", overlayShown)
        assertEquals("lead_assigned_1", overlayLeadId)

        // Call terminates
        callerIdManager.onCallEnded(context)
        assertNull("Active caller lead must be null after call ends", callerIdManager.getActiveCallerLead())
        assertFalse("Overlay must be dismissed when call terminates", overlayShown)
    }

    // -------------------------------------------------------------
    // Test 4: Caller ID falls back to heads-up notification when overlay denied
    // -------------------------------------------------------------
    @Test
    fun testCallerIdFallsBackToHeadsUpWhenOverlayPermissionDenied() = runTest {
        val assignedLead = LeadEntity(
            id = "lead_assigned_2",
            name = "Kavita Reddy",
            rawNumber = "+919123456789",
            normalizedNumber = "+919123456789",
            status = "Proposal Sent",
            notes = "Premium client"
        )

        val fakeLeadRepo = object : FakeLeadRepository(listOf(assignedLead)) {}

        var overlayAttempted = false
        val fakeOverlayManager = object : CallerOverlayManager() {
            override fun canDrawOverlays(ctx: Context): Boolean = false
            override fun showCallerOverlay(
                ctx: Context,
                leadId: String,
                leadName: String,
                leadStatus: String,
                lastNote: String?,
                normalizedNumber: String
            ): Boolean {
                overlayAttempted = true
                return false
            }
        }

        val callerIdManager = CallerIdentificationManager(
            leadRepository = fakeLeadRepo,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            callerOverlayManager = fakeOverlayManager,
            scope = this
        )

        // Incoming call from assigned lead with overlay permission denied
        callerIdManager.onCallStarted(context, "+919123456789")
        testScheduler.advanceUntilIdle()

        assertEquals("Assigned lead must be resolved even when overlay permission is denied", assignedLead, callerIdManager.getActiveCallerLead())
        assertFalse("Overlay must NOT be displayed when permission is denied", overlayAttempted)

        // Clean up
        callerIdManager.onCallEnded(context)
    }

    // -------------------------------------------------------------
    // Test 5: Caller ID ignores non-lead numbers to preserve privacy
    // -------------------------------------------------------------
    @Test
    fun testCallerIdIgnoresNonLeadsToPreservePrivacy() = runTest {
        val fakeLeadRepo = object : FakeLeadRepository(emptyList()) {}

        var overlayTriggered = false
        val fakeOverlayManager = object : CallerOverlayManager() {
            override fun canDrawOverlays(ctx: Context): Boolean = true
            override fun showCallerOverlay(
                ctx: Context,
                leadId: String,
                leadName: String,
                leadStatus: String,
                lastNote: String?,
                normalizedNumber: String
            ): Boolean {
                overlayTriggered = true
                return true
            }
        }

        val callerIdManager = CallerIdentificationManager(
            leadRepository = fakeLeadRepo,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            callerOverlayManager = fakeOverlayManager,
            scope = this
        )

        // Call from unknown personal contact
        callerIdManager.onCallStarted(context, "+919999900000")
        testScheduler.advanceUntilIdle()

        assertNull("Non-lead must not produce an active caller lead", callerIdManager.getActiveCallerLead())
        assertFalse("Overlay must NOT trigger for non-lead numbers", overlayTriggered)
    }

    // -------------------------------------------------------------
    // Test 6: Outgoing call resolves lead from pendingCallManager
    // -------------------------------------------------------------
    @Test
    fun testOutgoingCallResolvesLeadFromPendingCallManager() = runTest {
        val outgoingLead = LeadEntity(
            id = "lead_outgoing_1",
            name = "Amitabh Bachchan",
            rawNumber = "+919811122233",
            normalizedNumber = "+919811122233",
            status = "New"
        )

        pendingCallManager.setPendingCall(outgoingLead)

        val fakeLeadRepo = object : FakeLeadRepository(listOf(outgoingLead)) {}

        var overlayShown = false
        val fakeOverlayManager = object : CallerOverlayManager() {
            override fun canDrawOverlays(ctx: Context): Boolean = true
            override fun showCallerOverlay(
                ctx: Context,
                leadId: String,
                leadName: String,
                leadStatus: String,
                lastNote: String?,
                normalizedNumber: String
            ): Boolean {
                overlayShown = true
                return true
            }
        }

        val callerIdManager = CallerIdentificationManager(
            leadRepository = fakeLeadRepo,
            pendingCallManager = pendingCallManager,
            normalizer = normalizer,
            callerOverlayManager = fakeOverlayManager,
            scope = this
        )

        // Outgoing call starts with rawNumber null (e.g. dialer restriction)
        callerIdManager.onCallStarted(context, null)
        testScheduler.advanceUntilIdle()

        assertEquals("Outgoing call must resolve lead from pendingCallManager", outgoingLead, callerIdManager.getActiveCallerLead())
        assertTrue("Overlay must show for outgoing lead call", overlayShown)

        callerIdManager.onCallEnded(context)
    }
}

open class FakeLeadRepository(private val leads: List<LeadEntity>) : LeadRepository {
    override fun getAllLeadsFlow(): kotlinx.coroutines.flow.Flow<List<LeadEntity>> = flowOf(leads)
    override fun searchLeadsFlow(query: String, status: String): kotlinx.coroutines.flow.Flow<List<LeadEntity>> = flowOf(leads)
    override suspend fun getAllLeads(): AppResult<List<LeadEntity>> = AppResult.Success(leads)
    override suspend fun getLeadById(id: String): AppResult<LeadEntity?> =
        AppResult.Success(leads.find { it.id == id })
    override suspend fun getLeadByNormalizedNumber(normalizedNumber: String): AppResult<LeadEntity?> =
        AppResult.Success(leads.find { it.normalizedNumber == normalizedNumber })
    override suspend fun saveLead(lead: LeadEntity): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun saveLeads(leads: List<LeadEntity>): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteLeadById(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun clearAllLeads(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun syncLeads(forceFullSync: Boolean): AppResult<com.leads365.apk.data.repository.LeadSyncResult> =
        AppResult.Success(com.leads365.apk.data.repository.LeadSyncResult(0, 0, false, 0L))
    override fun getLastSyncTimestamp(): Long = 0L
}
