package com.leads365.apk.followups

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.leads365.apk.data.repository.FollowUpRepositoryImpl
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.FollowUpDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.FollowUpEntity
import com.leads365.apk.network.api.FollowUpApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase17FollowUpAndReminderTest {

    private lateinit var context: Context
    private lateinit var db: Leads365Database
    private lateinit var followUpDao: FollowUpDao
    private lateinit var leadDao: LeadDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        followUpDao = db.followUpDao()
        leadDao = db.leadDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // =========================================================================
    // 1. FollowUpDao CRUD & Query Operations
    // =========================================================================

    @Test
    fun followUpDao_insertAndQueryByStatus_preservesFields() = runBlocking {
        val now = System.currentTimeMillis()
        val followUp = FollowUpEntity(
            id = "fu_test_01",
            leadId = "lead_01",
            leadName = "Sunil Kumar",
            leadPhone = "+919876543210",
            title = "Callback regarding pricing",
            scheduledAtEpochMs = now + 3600_000L,
            status = FollowUpEntity.STATUS_PENDING,
            priority = FollowUpEntity.PRIORITY_HIGH,
            notes = "Customer requested brochure",
            syncStatus = FollowUpEntity.SYNC_STATUS_PENDING_CREATE
        )

        followUpDao.insert(followUp)

        val retrieved = followUpDao.getById("fu_test_01")
        assertNotNull("Inserted follow-up must be retrievable", retrieved)
        assertEquals("Sunil Kumar", retrieved?.leadName)
        assertEquals(FollowUpEntity.STATUS_PENDING, retrieved?.status)
        assertEquals(FollowUpEntity.PRIORITY_HIGH, retrieved?.priority)

        // Mark Completed
        followUpDao.markCompleted("fu_test_01", FollowUpEntity.SYNC_STATUS_PENDING_COMPLETE)
        val completed = followUpDao.getById("fu_test_01")
        assertEquals(FollowUpEntity.STATUS_COMPLETED, completed?.status)
        assertEquals(FollowUpEntity.SYNC_STATUS_PENDING_COMPLETE, completed?.syncStatus)

        // Reschedule
        val newTime = now + 7200_000L
        followUpDao.reschedule("fu_test_01", newTime, "Rescheduled by client")
        val rescheduled = followUpDao.getById("fu_test_01")
        assertEquals(newTime, rescheduled?.scheduledAtEpochMs)
        assertEquals("Rescheduled by client", rescheduled?.notes)
    }

    // =========================================================================
    // 2. Today, Upcoming, and Overdue Segregation
    // =========================================================================

    @Test
    fun followUpDao_segregatesTodayUpcomingAndOverdueAccurately() = runBlocking {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfToday = cal.timeInMillis

        // 1. Follow-up due today (in 2 hours or middle of today)
        val todayTime = (startOfToday + endOfToday) / 2
        followUpDao.insert(
            FollowUpEntity(
                id = "fu_today",
                leadName = "Today Lead",
                scheduledAtEpochMs = todayTime,
                status = FollowUpEntity.STATUS_PENDING
            )
        )

        // 2. Follow-up upcoming (tomorrow noon)
        val upcomingTime = endOfToday + 3600_000L * 12
        followUpDao.insert(
            FollowUpEntity(
                id = "fu_upcoming",
                leadName = "Upcoming Lead",
                scheduledAtEpochMs = upcomingTime,
                status = FollowUpEntity.STATUS_PENDING
            )
        )

        // 3. Follow-up overdue (yesterday)
        val overdueTime = startOfToday - 3600_000L * 5
        followUpDao.insert(
            FollowUpEntity(
                id = "fu_overdue",
                leadName = "Overdue Lead",
                scheduledAtEpochMs = overdueTime,
                status = FollowUpEntity.STATUS_PENDING
            )
        )

        val allPending = followUpDao.getFollowUpsByStatusFlow(FollowUpEntity.STATUS_PENDING).first()
        assertEquals(3, allPending.size)

        val todayList = allPending.filter { it.scheduledAtEpochMs in startOfToday..endOfToday }
        assertEquals(1, todayList.size)
        assertEquals("fu_today", todayList[0].id)

        val upcomingList = allPending.filter { it.scheduledAtEpochMs > endOfToday }
        assertEquals(1, upcomingList.size)
        assertEquals("fu_upcoming", upcomingList[0].id)

        val overdueList = allPending.filter { it.scheduledAtEpochMs < startOfToday }
        assertEquals(1, overdueList.size)
        assertEquals("fu_overdue", overdueList[0].id)
    }

    // =========================================================================
    // 3. Completed Follow-ups Do Not Appear in Outstanding Lists
    // =========================================================================

    @Test
    fun completedFollowUps_areFilteredFromPendingQueries() = runBlocking {
        val now = System.currentTimeMillis()
        followUpDao.insert(
            FollowUpEntity(
                id = "fu_done",
                leadName = "Done Lead",
                scheduledAtEpochMs = now,
                status = FollowUpEntity.STATUS_COMPLETED
            )
        )

        val pending = followUpDao.getFollowUpsByStatusFlow(FollowUpEntity.STATUS_PENDING).first()
        assertTrue("Completed follow-ups must not appear in pending flow", pending.none { it.id == "fu_done" })
    }

    // =========================================================================
    // 4. Offline Pending Sync Status Tracking
    // =========================================================================

    @Test
    fun offlinePendingSyncTracking_identifiesPendingMutations() = runBlocking {
        followUpDao.insert(
            FollowUpEntity(
                id = "fu_offline_1",
                leadName = "Offline 1",
                scheduledAtEpochMs = System.currentTimeMillis(),
                syncStatus = FollowUpEntity.SYNC_STATUS_PENDING_CREATE
            )
        )
        followUpDao.insert(
            FollowUpEntity(
                id = "fu_synced",
                leadName = "Synced 2",
                scheduledAtEpochMs = System.currentTimeMillis(),
                syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED
            )
        )

        val pendingSync = followUpDao.getPendingSyncFollowUps()
        assertEquals(1, pendingSync.size)
        assertEquals("fu_offline_1", pendingSync[0].id)
    }
}
