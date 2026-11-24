package com.leads365.apk.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.FollowUpEntity
import com.leads365.apk.database.entity.LeadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomMigration6To7Test {

    private lateinit var context: Context
    private val testDbName = "migration_6_7_test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cleanupFiles()
    }

    @After
    fun teardown() {
        cleanupFiles()
    }

    private fun cleanupFiles() {
        val dbFile = context.getDatabasePath(testDbName)
        if (dbFile.exists()) dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    @Test
    fun testFreshInstall_createsVersion7Database() {
        val db = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()

        assertNotNull(db)
        val leadDao = db.leadDao()
        val callDao = db.callDao()
        val followUpDao = db.followUpDao()

        runBlocking {
            val lead = LeadEntity(
                id = "lead_fresh_1",
                name = "Fresh User",
                rawNumber = "9876543210",
                normalizedNumber = "+919876543210",
                workspaceId = "ws_tech_1"
            )
            leadDao.insert(lead)
            val retrieved = leadDao.getById("lead_fresh_1")
            assertNotNull(retrieved)
            assertEquals("ws_tech_1", retrieved?.workspaceId)

            val call = CallEntity(
                id = "call_fresh_1",
                normalizedNumber = "+919876543210",
                rawNumber = "9876543210",
                callType = 1,
                startTimeEpochMs = 1700000000000L,
                durationSeconds = 60,
                workspaceId = "ws_tech_1"
            )
            callDao.insert(call)
            val retrievedCall = callDao.getById("call_fresh_1")
            assertNotNull(retrievedCall)
            assertEquals("ws_tech_1", retrievedCall?.workspaceId)

            val followUp = FollowUpEntity(
                id = "fu_fresh_1",
                leadId = "lead_fresh_1",
                leadName = "Fresh User",
                leadPhone = "+919876543210",
                title = "Demo callback",
                scheduledAtEpochMs = 1700100000000L,
                workspaceId = "ws_tech_1"
            )
            followUpDao.insert(followUp)
            val retrievedFu = followUpDao.getById("fu_fresh_1")
            assertNotNull(retrievedFu)
            assertEquals("ws_tech_1", retrievedFu?.workspaceId)
        }

        db.close()
    }

    @Test
    fun testMigration6To7_preservesExistingDataAndAddsWorkspaceColumns() {
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()

        // 1. Create SQLite DB simulating Room schema at version 6 (without workspaceId)
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqliteDb.version = 6

        sqliteDb.execSQL(
            """
            CREATE TABLE leads (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                rawNumber TEXT NOT NULL,
                normalizedNumber TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'New',
                nextFollowUpEpochMs INTEGER DEFAULT NULL,
                isDoNotRecord INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                assignedAgentId TEXT DEFAULT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAtEpochMs INTEGER NOT NULL DEFAULT 0,
                updatedAtEpochMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        sqliteDb.execSQL("CREATE UNIQUE INDEX index_leads_normalizedNumber ON leads(normalizedNumber)")
        sqliteDb.execSQL("CREATE INDEX index_leads_updatedAtEpochMs ON leads(updatedAtEpochMs)")
        sqliteDb.execSQL("CREATE INDEX index_leads_status ON leads(status)")

        sqliteDb.execSQL(
            """
            CREATE TABLE calls (
                id TEXT NOT NULL PRIMARY KEY,
                clientCallId TEXT NOT NULL DEFAULT '',
                normalizedNumber TEXT NOT NULL,
                rawNumber TEXT NOT NULL,
                callType INTEGER NOT NULL,
                startTimeEpochMs INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                isLeadCall INTEGER NOT NULL DEFAULT 0,
                leadId TEXT DEFAULT NULL,
                simId TEXT DEFAULT NULL,
                callOutcome TEXT DEFAULT NULL,
                notes TEXT DEFAULT NULL,
                nextFollowUpEpochMs INTEGER DEFAULT NULL,
                syncStatus TEXT NOT NULL DEFAULT 'PENDING',
                syncErrorMessage TEXT DEFAULT NULL,
                hasRecording INTEGER NOT NULL DEFAULT 0,
                recordingUri TEXT DEFAULT NULL,
                recordingFileName TEXT DEFAULT NULL,
                recordingFileSizeBytes INTEGER DEFAULT NULL,
                createdAtEpochMs INTEGER NOT NULL DEFAULT 0,
                recordingOrigin TEXT NOT NULL DEFAULT 'sim_call',
                remoteRecordingUrl TEXT DEFAULT NULL,
                isDownloaded INTEGER NOT NULL DEFAULT 0,
                localFilePath TEXT DEFAULT NULL
            )
            """.trimIndent()
        )
        sqliteDb.execSQL("CREATE INDEX index_calls_normalizedNumber ON calls(normalizedNumber)")
        sqliteDb.execSQL("CREATE INDEX index_calls_startTimeEpochMs ON calls(startTimeEpochMs)")
        sqliteDb.execSQL("CREATE UNIQUE INDEX index_calls_clientCallId ON calls(clientCallId)")

        sqliteDb.execSQL(
            """
            CREATE TABLE follow_ups (
                id TEXT NOT NULL PRIMARY KEY,
                leadId TEXT DEFAULT NULL,
                leadName TEXT NOT NULL DEFAULT '',
                leadPhone TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'call',
                scheduledAtEpochMs INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                priority TEXT NOT NULL DEFAULT 'medium',
                notes TEXT NOT NULL DEFAULT '',
                assignedTo TEXT NOT NULL DEFAULT '',
                sourceCallId TEXT DEFAULT NULL,
                syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                updatedAtEpochMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        sqliteDb.execSQL("CREATE INDEX index_follow_ups_status ON follow_ups(status)")
        sqliteDb.execSQL("CREATE INDEX index_follow_ups_scheduledAtEpochMs ON follow_ups(scheduledAtEpochMs)")
        sqliteDb.execSQL("CREATE INDEX index_follow_ups_leadId ON follow_ups(leadId)")
        sqliteDb.execSQL("CREATE INDEX index_follow_ups_sourceCallId ON follow_ups(sourceCallId)")

        // Also create upload_tasks table which is part of schema
        sqliteDb.execSQL(
            """
            CREATE TABLE upload_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                callId TEXT DEFAULT NULL,
                leadId TEXT DEFAULT NULL,
                localFileUri TEXT NOT NULL,
                fileName TEXT NOT NULL,
                fileSizeBytes INTEGER NOT NULL,
                sha256Hash TEXT DEFAULT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                retryCount INTEGER NOT NULL DEFAULT 0,
                maxRetries INTEGER NOT NULL DEFAULT 3,
                lastError TEXT DEFAULT NULL,
                createdAtEpochMs INTEGER NOT NULL DEFAULT 0,
                updatedAtEpochMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Seed legacy records
        sqliteDb.execSQL(
            "INSERT INTO leads (id, name, rawNumber, normalizedNumber, status, notes) " +
            "VALUES ('legacy_lead_1', 'Priya Patel', '9876500001', '+919876500001', 'Interested', 'High value client')"
        )
        sqliteDb.execSQL(
            "INSERT INTO calls (id, clientCallId, normalizedNumber, rawNumber, callType, startTimeEpochMs, durationSeconds, isLeadCall, leadId) " +
            "VALUES ('legacy_call_1', 'client_call_legacy', '+919876500001', '9876500001', 2, 1699000000000, 120, 1, 'legacy_lead_1')"
        )
        sqliteDb.execSQL(
            "INSERT INTO follow_ups (id, leadId, leadName, leadPhone, title, scheduledAtEpochMs) " +
            "VALUES ('legacy_fu_1', 'legacy_lead_1', 'Priya Patel', '+919876500001', 'Send Proposal', 1699100000000)"
        )

        sqliteDb.close()

        // 2. Open via Room and trigger MIGRATION_6_7
        val upgradedDb = Room.databaseBuilder(context, Leads365Database::class.java, testDbName)
            .addMigrations(Leads365Database.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            // Verify existing records survived intact
            val lead = upgradedDb.leadDao().getById("legacy_lead_1")
            assertNotNull("Legacy lead must survive migration", lead)
            assertEquals("Priya Patel", lead?.name)
            assertEquals("+919876500001", lead?.normalizedNumber)
            assertNull("WorkspaceId must default to null for legacy records", lead?.workspaceId)

            val call = upgradedDb.callDao().getById("legacy_call_1")
            assertNotNull("Legacy call must survive migration", call)
            assertEquals(120L, call?.durationSeconds)
            assertEquals("legacy_lead_1", call?.leadId)
            assertNull("WorkspaceId must default to null for legacy calls", call?.workspaceId)

            val fu = upgradedDb.followUpDao().getById("legacy_fu_1")
            assertNotNull("Legacy follow-up must survive migration", fu)
            assertEquals("Send Proposal", fu?.title)
            assertNull("WorkspaceId must default to null for legacy follow-ups", fu?.workspaceId)

            // Verify newly inserted records in upgraded DB can write workspaceId
            val updatedLead = lead!!.copy(workspaceId = "ws_migrated_1")
            upgradedDb.leadDao().insert(updatedLead)
            val reReadLead = upgradedDb.leadDao().getById("legacy_lead_1")
            assertEquals("ws_migrated_1", reReadLead?.workspaceId)

            upgradedDb.callDao().updateOutcome("legacy_call_1", "Interested", "Migrated note", null, "SYNCED", "ws_migrated_1")
            val reReadCall = upgradedDb.callDao().getById("legacy_call_1")
            assertEquals("ws_migrated_1", reReadCall?.workspaceId)
            assertEquals("Interested", reReadCall?.callOutcome)
        }

        upgradedDb.close()
    }
}
