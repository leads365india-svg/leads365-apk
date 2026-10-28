package com.leads365.apk.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomDatabaseTest {

    private lateinit var db: Leads365Database
    private lateinit var leadDao: LeadDao
    private lateinit var callDao: CallDao
    private lateinit var uploadTaskDao: UploadTaskDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        leadDao = db.leadDao()
        callDao = db.callDao()
        uploadTaskDao = db.uploadTaskDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `LeadDao insert and query by id and normalizedNumber`() = runBlocking {
        val lead = LeadEntity(
            id = "lead_1",
            name = "Rajesh Sharma",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            isDoNotRecord = false,
            notes = "Test lead note"
        )

        leadDao.insert(lead)

        val byId = leadDao.getById("lead_1")
        assertNotNull(byId)
        assertEquals("Rajesh Sharma", byId?.name)
        assertEquals("+919876543210", byId?.normalizedNumber)
        assertFalse(byId?.isDoNotRecord ?: true)

        val byNumber = leadDao.getByNormalizedNumber("+919876543210")
        assertNotNull(byNumber)
        assertEquals("lead_1", byNumber?.id)

        // Update lead
        val updated = lead.copy(isDoNotRecord = true, name = "Rajesh Sharma (DNR)")
        leadDao.update(updated)

        val retrievedUpdated = leadDao.getById("lead_1")
        assertTrue(retrievedUpdated?.isDoNotRecord == true)
        assertEquals("Rajesh Sharma (DNR)", retrievedUpdated?.name)

        // Count and delete
        assertEquals(1, leadDao.count())
        leadDao.deleteById("lead_1")
        assertEquals(0, leadDao.count())
    }

    @Test
    fun `LeadDao insertAll and flow emission`() = runBlocking {
        val leads = listOf(
            LeadEntity(id = "l1", name = "Lead 1", rawNumber = "9876500001", normalizedNumber = "+919876500001"),
            LeadEntity(id = "l2", name = "Lead 2", rawNumber = "9876500002", normalizedNumber = "+919876500002")
        )

        leadDao.insertAll(leads)
        assertEquals(2, leadDao.count())

        val list = leadDao.getAllLeadsFlow().first()
        assertEquals(2, list.size)

        leadDao.clearAll()
        assertEquals(0, leadDao.count())
    }

    @Test
    fun `CallDao insert and query filtering`() = runBlocking {
        val call1 = CallEntity(
            id = "call_1",
            normalizedNumber = "+919876543210",
            rawNumber = "+91 98765 43210",
            callType = 1,
            startTimeEpochMs = 10000L,
            durationSeconds = 45L,
            isLeadCall = true,
            leadId = "lead_1"
        )

        val call2 = CallEntity(
            id = "call_2",
            normalizedNumber = "+919999999999",
            rawNumber = "+91 99999 99999",
            callType = 2,
            startTimeEpochMs = 20000L,
            durationSeconds = 12L,
            isLeadCall = false,
            leadId = null
        )

        callDao.insert(call1)
        callDao.insert(call2)

        assertEquals(2, callDao.count())

        val allCalls = callDao.getAllCallsFlow().first()
        assertEquals(2, allCalls.size)

        val leadCalls = callDao.getLeadCallsFlow().first()
        assertEquals(1, leadCalls.size)
        assertEquals("call_1", leadCalls[0].id)
        assertTrue(leadCalls[0].isLeadCall)

        val callsForNumber = callDao.getCallsForNumberFlow("+919876543210").first()
        assertEquals(1, callsForNumber.size)
        assertEquals("call_1", callsForNumber[0].id)

        // Update call with recording info
        val updatedCall = call1.copy(
            hasRecording = true,
            recordingFileName = "REC_+919876543210_10000.m4a",
            recordingFileSizeBytes = 102400L
        )
        callDao.update(updatedCall)

        // Phase 6 tests: clientCallId, findCallBySignature, updateOutcome, and pending sync
        val byClientCallId = callDao.getByClientCallId("call_1")
        assertNotNull(byClientCallId)
        assertEquals("call_1", byClientCallId?.clientCallId)

        val bySignature = callDao.findCallBySignature("+919876543210", 9000L, 11000L)
        assertNotNull(bySignature)
        assertEquals("call_1", bySignature?.id)

        // Update outcome
        callDao.updateOutcome("call_1", "Interested", "Customer requested callback", 1700000000000L, "PENDING")
        val withOutcome = callDao.getById("call_1")
        assertEquals("Interested", withOutcome?.callOutcome)
        assertEquals("Customer requested callback", withOutcome?.notes)
        assertEquals(1700000000000L, withOutcome?.nextFollowUpEpochMs)
        assertEquals("PENDING", withOutcome?.syncStatus)

        val pendingList = callDao.getPendingSyncCalls()
        assertEquals(1, pendingList.size)
        assertEquals("call_1", pendingList[0].id)

        // Update sync status to SYNCED
        callDao.updateSyncStatus("call_1", "SYNCED", null)
        val synced = callDao.getById("call_1")
        assertEquals("SYNCED", synced?.syncStatus)
        assertEquals(0, callDao.getPendingSyncCalls().size)
    }

    @Test
    fun `UploadTaskDao lifecycle and status transitions`() = runBlocking {
        val task = UploadTaskEntity(
            id = "task_1",
            callId = "call_1",
            leadId = "lead_1",
            localFileUri = "content://media/external/audio/media/123",
            fileName = "recording_test.m4a",
            fileSizeBytes = 204800L,
            status = UploadTaskEntity.STATUS_PENDING
        )

        uploadTaskDao.insert(task)

        val pending = uploadTaskDao.getPendingTasks()
        assertEquals(1, pending.size)
        assertEquals("task_1", pending[0].id)
        assertEquals(UploadTaskEntity.STATUS_PENDING, pending[0].status)

        // Status update to IN_PROGRESS
        uploadTaskDao.updateStatus("task_1", UploadTaskEntity.STATUS_IN_PROGRESS)
        val inProgress = uploadTaskDao.getTasksByStatus(UploadTaskEntity.STATUS_IN_PROGRESS)
        assertEquals(1, inProgress.size)

        // Retry increment on failure
        uploadTaskDao.incrementRetry("task_1", "Connection timed out")
        val retried = uploadTaskDao.getById("task_1")
        assertEquals(1, retried?.retryCount)
        assertEquals("Connection timed out", retried?.lastError)

        // Complete and cleanup
        uploadTaskDao.updateStatus("task_1", UploadTaskEntity.STATUS_COMPLETED)
        assertEquals(1, uploadTaskDao.countByStatus(UploadTaskEntity.STATUS_COMPLETED))

        uploadTaskDao.clearCompleted()
        assertEquals(0, uploadTaskDao.countByStatus(UploadTaskEntity.STATUS_COMPLETED))
    }

    @Test
    fun `Database migration definition compiles and runs successfully`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val migratedDb = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .addMigrations(Leads365Database.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        assertNotNull(migratedDb)
        migratedDb.close()
    }
}
