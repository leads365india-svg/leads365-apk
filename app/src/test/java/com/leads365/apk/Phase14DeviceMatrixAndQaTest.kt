package com.leads365.apk

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.recordings.TargetedRecordingMatcher
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
class Phase14DeviceMatrixAndQaTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var matcher: TargetedRecordingMatcher

    private val baseCallEvent = CallEvent(
        id = "call_qa_001",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        startTimeEpochMs = 1761633000000L,
        endTimeEpochMs = 1761633045000L, // 45 seconds call
        durationSeconds = 45L,
        direction = CallDirection.OUTGOING
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        normalizer = PhoneNumberNormalizer("IN")
        matcher = TargetedRecordingMatcher(
            normalizer = normalizer,
            minAcceptanceScore = 0.55,
            ambiguityDeltaThreshold = 0.15
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // 1. OEM Filename Resolution Matrix (Samsung, Xiaomi, Vivo, Oppo)
    // =========================================================================

    @Test
    fun matcher_resolvesSamsungDialerFilenameConvention() {
        // Samsung pattern: Call to [Name]_[Number]_[YYMMDD_HHMMSS].m4a
        val candidate = RecordingCandidate(
            uriString = "content://recordings/samsung_01",
            displayName = "Call to Rajesh Sharma_9876543210_261028_123045.m4a",
            sizeBytes = 350_000L,
            lastModifiedEpochMs = baseCallEvent.endTimeEpochMs + 2_000L,
            mimeType = "audio/m4a",
            durationSeconds = 45L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate), baseCallEvent)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals(candidate.displayName, result.matchedCandidate?.displayName)
        assertTrue("Samsung candidate should match with high confidence", result.confidenceScore >= 0.80)
    }

    @Test
    fun matcher_resolvesXiaomiHyperOsDialerFilenameConvention() {
        // Xiaomi pattern: [Name]([Number])_[YYYYMMDDHHMMSS].mp3
        val candidate = RecordingCandidate(
            uriString = "content://recordings/xiaomi_01",
            displayName = "Rajesh Sharma(9876543210)_20261028123045.mp3",
            sizeBytes = 400_000L,
            lastModifiedEpochMs = baseCallEvent.endTimeEpochMs + 3_000L,
            mimeType = "audio/mp3",
            durationSeconds = 45L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate), baseCallEvent)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals(candidate.displayName, result.matchedCandidate?.displayName)
        assertTrue("Xiaomi candidate should match with high confidence", result.confidenceScore >= 0.80)
    }

    @Test
    fun matcher_resolvesVivoFuntouchDialerFilenameConvention() {
        // Vivo pattern: [Number]_[YYYYMMDD_HHMMSS].m4a
        val candidate = RecordingCandidate(
            uriString = "content://recordings/vivo_01",
            displayName = "9876543210_20261028_123045.m4a",
            sizeBytes = 320_000L,
            lastModifiedEpochMs = baseCallEvent.endTimeEpochMs + 1_500L,
            mimeType = "audio/m4a",
            durationSeconds = 45L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate), baseCallEvent)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals(candidate.displayName, result.matchedCandidate?.displayName)
        assertTrue("Vivo candidate should match with high confidence", result.confidenceScore >= 0.80)
    }

    @Test
    fun matcher_resolvesOppoColorOsDialerFilenameConvention() {
        // Oppo / Realme / OnePlus pattern: REC_[Number]_[YYYYMMDD_HHMMSS].m4a
        val candidate = RecordingCandidate(
            uriString = "content://recordings/oppo_01",
            displayName = "REC_9876543210_20261028_123045.m4a",
            sizeBytes = 360_000L,
            lastModifiedEpochMs = baseCallEvent.endTimeEpochMs + 2_500L,
            mimeType = "audio/m4a",
            durationSeconds = 45L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate), baseCallEvent)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals(candidate.displayName, result.matchedCandidate?.displayName)
        assertTrue("Oppo candidate should match with high confidence", result.confidenceScore >= 0.80)
    }

    @Test
    fun matcher_rejectsAmbiguousCandidatesWithEqualConfidence() {
        // Two candidates recorded at nearly the same timestamp for the same number
        val candidate1 = RecordingCandidate(
            uriString = "content://recordings/ambig_01",
            displayName = "REC_9876543210_20261028_123045.m4a",
            sizeBytes = 350_000L,
            lastModifiedEpochMs = baseCallEvent.endTimeEpochMs + 2_000L,
            mimeType = "audio/m4a",
            durationSeconds = 45L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val candidate2 = RecordingCandidate(
            uriString = "content://recordings/ambig_02",
            displayName = "Call_9876543210_20261028_123046.m4a",
            sizeBytes = 355_000L,
            lastModifiedEpochMs = baseCallEvent.endTimeEpochMs + 2_500L,
            mimeType = "audio/m4a",
            durationSeconds = 45L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate1, candidate2), baseCallEvent)
        assertEquals("Matcher must flag AMBIGUOUS when two candidates are within threshold", MatchStatus.AMBIGUOUS, result.status)
        assertNull("Ambiguous match must not silently select a candidate", result.matchedCandidate)
    }

    // =========================================================================
    // 2. Lead Number Indexing & Reassignment Verification
    // =========================================================================

    @Test
    fun leadIndex_lookupNormalizesLocalAndInternationalFormats() = runBlocking {
        val leadDao = database.leadDao()
        val lead = LeadEntity(
            id = "lead_idx_01",
            name = "Priya Patel",
            rawNumber = "09876543210",
            normalizedNumber = "+919876543210",
            status = "New"
        )
        leadDao.insert(lead)

        // Lookup with exact normalized number
        val found = leadDao.getByNormalizedNumber("+919876543210")
        assertNotNull(found)
        assertEquals("lead_idx_01", found?.id)

        // Distinct number does not collide
        val notFound = leadDao.getByNormalizedNumber("+919876543211")
        assertNull(notFound)
    }

    @Test
    fun leadIndex_reassignedOrDeletedLeadImmediatelyLosesEligibility() = runBlocking {
        val leadDao = database.leadDao()
        val lead = LeadEntity(
            id = "lead_idx_02",
            name = "Amit Kumar",
            rawNumber = "9876543210",
            normalizedNumber = "+919876543210",
            status = "Assigned"
        )
        leadDao.insert(lead)
        assertNotNull(leadDao.getByNormalizedNumber("+919876543210"))

        // Reassign to different number
        val updatedLead = lead.copy(normalizedNumber = "+919999988888", rawNumber = "9999988888")
        leadDao.update(updatedLead)

        // Old number must not be eligible
        assertNull(leadDao.getByNormalizedNumber("+919876543210"))
        // New number must be eligible
        assertNotNull(leadDao.getByNormalizedNumber("+919999988888"))

        // Delete lead
        leadDao.delete(updatedLead)
        assertNull(leadDao.getByNormalizedNumber("+919999988888"))
    }

    // =========================================================================
    // 3. Queue Terminal Cleanup Safety (Never Deletes Active Work)
    // =========================================================================

    @Test
    fun queueCleanup_deletesExpiredTerminalTasks_butPreservesPendingAndRetryTasks() = runBlocking {
        val uploadTaskDao = database.uploadTaskDao()
        val now = System.currentTimeMillis()
        val oldCutoff = now - 86400000L // 24 hours ago
        val veryOld = now - (86400000L * 3) // 3 days ago

        // Old Terminal Tasks (should be deleted)
        uploadTaskDao.insert(
            UploadTaskEntity(
                id = "task_terminal_01",
                callId = "call_t1",
                localFileUri = "content://audio/1",
                fileName = "t1.m4a",
                fileSizeBytes = 1000L,
                status = UploadTaskEntity.STATUS_COMPLETED,
                updatedAtEpochMs = veryOld
            )
        )
        uploadTaskDao.insert(
            UploadTaskEntity(
                id = "task_terminal_02",
                callId = "call_t2",
                localFileUri = "content://audio/2",
                fileName = "t2.m4a",
                fileSizeBytes = 1000L,
                status = UploadTaskEntity.STATE_SKIPPED_DNR,
                updatedAtEpochMs = veryOld
            )
        )

        // Old Active / In-Flight Tasks (MUST NEVER BE DELETED)
        uploadTaskDao.insert(
            UploadTaskEntity(
                id = "task_active_pending",
                callId = "call_a1",
                localFileUri = "content://audio/3",
                fileName = "a1.m4a",
                fileSizeBytes = 1000L,
                status = UploadTaskEntity.STATUS_PENDING,
                updatedAtEpochMs = veryOld
            )
        )
        uploadTaskDao.insert(
            UploadTaskEntity(
                id = "task_active_retry_wait",
                callId = "call_a2",
                localFileUri = "content://audio/4",
                fileName = "a2.m4a",
                fileSizeBytes = 1000L,
                status = UploadTaskEntity.STATE_RETRY_WAIT,
                updatedAtEpochMs = veryOld
            )
        )
        uploadTaskDao.insert(
            UploadTaskEntity(
                id = "task_active_uploading",
                callId = "call_a3",
                localFileUri = "content://audio/5",
                fileName = "a3.m4a",
                fileSizeBytes = 1000L,
                status = UploadTaskEntity.STATE_UPLOADING,
                updatedAtEpochMs = veryOld
            )
        )

        // Execute cleanup
        val deletedCount = uploadTaskDao.deleteExpiredTerminalTasks(oldCutoff)
        assertEquals("Exactly 2 terminal tasks should be deleted", 2, deletedCount)

        // Verify active tasks survived untouched
        assertNotNull("Pending task must survive cleanup", uploadTaskDao.getById("task_active_pending"))
        assertNotNull("Retry wait task must survive cleanup", uploadTaskDao.getById("task_active_retry_wait"))
        assertNotNull("Uploading task must survive cleanup", uploadTaskDao.getById("task_active_uploading"))

        // Verify terminal tasks were removed
        assertNull("Completed task must be purged", uploadTaskDao.getById("task_terminal_01"))
        assertNull("Skipped DNR task must be purged", uploadTaskDao.getById("task_terminal_02"))
    }
}
