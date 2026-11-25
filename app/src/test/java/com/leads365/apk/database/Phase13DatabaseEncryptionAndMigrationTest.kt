package com.leads365.apk.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.database.security.DatabaseKeyManager
import com.leads365.apk.database.security.DatabaseSecurityMigrator
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase13DatabaseEncryptionAndMigrationTest {

    private lateinit var context: Context
    private lateinit var keyManager: DatabaseKeyManager
    private val testDbName = "mig_test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyManager = DatabaseKeyManager(context)
        keyManager.clearPassphrase()
        cleanupFiles()
    }

    @After
    fun teardown() {
        cleanupFiles()
        keyManager.clearPassphrase()
    }

    private fun cleanupFiles() {
        val dbFile = context.getDatabasePath(testDbName)
        if (dbFile.exists()) dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        File("${dbFile.absolutePath}_tmp.db").delete()
        File("${dbFile.absolutePath}.plaintext_bak").delete()
    }

    // =========================================================================
    // 1. DatabaseKeyManager Tests
    // =========================================================================

    @Test
    fun databaseKeyManager_generatesAndPersists256BitPassphraseIdempotently() {
        assertFalse("Key should not exist initially", keyManager.hasPassphrase())

        val key1 = keyManager.getOrCreatePassphrase()
        assertNotNull("Generated key must not be null", key1)
        assertEquals("Key size must be exactly 32 bytes (256 bits)", 32, key1.size)
        assertTrue("hasPassphrase must be true after generation", keyManager.hasPassphrase())

        // Subsequent call must return identical key bytes
        val key2 = keyManager.getOrCreatePassphrase()
        assertArrayEquals("Subsequent key retrieval must be identical", key1, key2)

        // Clear passphrase
        keyManager.clearPassphrase()
        assertFalse("Key must be cleared", keyManager.hasPassphrase())
    }

    // =========================================================================
    // 2. Plaintext Header Detection Tests
    // =========================================================================

    @Test
    fun isPlaintextSqlite_identifiesStandardSqliteHeaderVsEncrypted() {
        val testFile = File(context.cacheDir, "sample_sqlite.db")
        if (testFile.exists()) testFile.delete()

        // Write standard SQLite 3 magic header
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        FileOutputStream(testFile).use { it.write(header); it.write(ByteArray(100)) }

        assertTrue("Must detect standard SQLite plaintext header", DatabaseSecurityMigrator.isPlaintextSqlite(testFile))

        // Overwrite with pseudorandom ciphertext bytes
        val encryptedHeader = ByteArray(16) { (it * 7 + 13).toByte() }
        FileOutputStream(testFile).use { it.write(encryptedHeader); it.write(ByteArray(100)) }

        assertFalse("Encrypted file must NOT be detected as plaintext SQLite", DatabaseSecurityMigrator.isPlaintextSqlite(testFile))

        testFile.delete()
    }

    // =========================================================================
    // 3. Fresh Database Creation (Phase 13: Encrypted from Day One)
    // =========================================================================

    @Test
    fun ensureEncrypted_whenDatabaseDoesNotExist_returnsFreshCreationEncrypted() {
        val dbFile = context.getDatabasePath("non_existent_db.db")
        if (dbFile.exists()) dbFile.delete()

        val passphrase = keyManager.getOrCreatePassphrase()
        val result = DatabaseSecurityMigrator.ensureEncrypted(context, "non_existent_db.db", passphrase)

        assertEquals(DatabaseSecurityMigrator.MigrationResult.FreshCreationEncrypted, result)
    }

    // =========================================================================
    // 4. End-to-End Plaintext to Encrypted Safe Migration
    // =========================================================================

    @Test
    fun migratesPlaintextPreservingUploads() {
        val passphrase = keyManager.getOrCreatePassphrase()
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()

        // 1. Seed a legacy plaintext SQLite database with Room schema version 3
        val plaintextDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        plaintextDb.execSQL(
            """
            CREATE TABLE leads (
                id TEXT NOT NULL PRIMARY KEY,
                normalizedNumber TEXT NOT NULL,
                rawNumber TEXT NOT NULL,
                name TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'New',
                nextFollowUpEpochMs INTEGER DEFAULT NULL,
                assignedAgentId TEXT DEFAULT NULL,
                notes TEXT DEFAULT NULL,
                isDoNotRecord INTEGER NOT NULL DEFAULT 0,
                updatedAtEpochMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        plaintextDb.execSQL(
            """
            CREATE TABLE calls (
                id TEXT NOT NULL PRIMARY KEY,
                clientCallId TEXT NOT NULL,
                normalizedNumber TEXT NOT NULL,
                rawNumber TEXT NOT NULL,
                callType INTEGER NOT NULL,
                startTimeEpochMs INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                isLeadCall INTEGER NOT NULL,
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
                createdAtEpochMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        plaintextDb.execSQL(
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

        // Insert sample lead
        plaintextDb.execSQL("INSERT INTO leads (id, normalizedNumber, rawNumber, name, status) VALUES ('lead_01', '+919876543210', '09876543210', 'Rahul Sharma', 'Interested')")

        // Insert sample call
        plaintextDb.execSQL("INSERT INTO calls (id, clientCallId, normalizedNumber, rawNumber, callType, startTimeEpochMs, durationSeconds, isLeadCall, leadId) VALUES ('call_01', 'client_call_01', '+919876543210', '09876543210', 2, 1698480000000, 45, 1, 'lead_01')")

        // Insert sample pending upload task
        plaintextDb.execSQL("INSERT INTO upload_tasks (id, callId, leadId, localFileUri, fileName, fileSizeBytes, status) VALUES ('task_01', 'call_01', 'lead_01', 'content://media/rec.m4a', 'rec.m4a', 2048, 'PENDING')")

        plaintextDb.close()

        // Verify initial state is plaintext
        assertTrue("Seed database must be detected as plaintext SQLite", DatabaseSecurityMigrator.isPlaintextSqlite(dbFile))

        // 2. Execute Migration to SQLCipher
        val migrationResult = DatabaseSecurityMigrator.ensureEncrypted(context, testDbName, passphrase)

        assertTrue("Migration must succeed", migrationResult is DatabaseSecurityMigrator.MigrationResult.MigratedSuccessfully)
        val success = migrationResult as DatabaseSecurityMigrator.MigrationResult.MigratedSuccessfully
        assertEquals("Must migrate 1 lead", 1, success.leadCount)
        assertEquals("Must migrate 1 call", 1, success.callCount)
        assertEquals("Must preserve 1 pending upload", 1, success.pendingUploadsPreserved)

        // 3. Verify destination database header
        if (DatabaseSecurityMigrator.isNativeSqlcipherAvailable()) {
            assertFalse("Migrated database file must NOT have plaintext SQLite header on Android runtime", DatabaseSecurityMigrator.isPlaintextSqlite(dbFile))
        }

        // 4. Verify reopening database with Room succeeds and reads exact records
        val builder = Room.databaseBuilder(context, Leads365Database::class.java, testDbName)
            .addMigrations(Leads365Database.MIGRATION_1_2, Leads365Database.MIGRATION_2_3)
            .allowMainThreadQueries()

        if (DatabaseSecurityMigrator.isNativeSqlcipherAvailable()) {
            builder.openHelperFactory(SupportFactory(passphrase))
        }

        val reopenedDb = builder.build()

        runBlocking {
            val leads = reopenedDb.leadDao().getAllLeads()
            assertEquals(1, leads.size)
            assertEquals("lead_01", leads[0].id)
            assertEquals("+919876543210", leads[0].normalizedNumber)
            assertEquals("Rahul Sharma", leads[0].name)

            val callsCount = reopenedDb.callDao().count()
            assertEquals(1, callsCount)

            val pendingTasks = reopenedDb.uploadTaskDao().getPendingTasks()
            assertEquals(1, pendingTasks.size)
            assertEquals("task_01", pendingTasks[0].id)
            assertEquals("PENDING", pendingTasks[0].status)
            assertEquals(2048L, pendingTasks[0].fileSizeBytes)
        }

        reopenedDb.close()

        // 5. Verify Idempotency: Re-running ensureEncrypted when encrypted returns AlreadyEncrypted
        if (DatabaseSecurityMigrator.isNativeSqlcipherAvailable()) {
            val idempotentResult = DatabaseSecurityMigrator.ensureEncrypted(context, testDbName, passphrase)
            assertEquals(DatabaseSecurityMigrator.MigrationResult.AlreadyEncrypted, idempotentResult)
        }
    }

    // =========================================================================
    // 5. Room MIGRATION_7_8 Test (Preserves Pending Offline Recordings)
    // =========================================================================

    @Test
    fun migration_7_to_8_addsTenantAndWorkspaceColumnsPreservingOfflineRecordings() {
        val migDbName = "mig_v7_v8_test.db"
        val dbFile = context.getDatabasePath(migDbName)
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        val factory = FrameworkSQLiteOpenHelperFactory()

        // 1. Create a schema version 7 database and seed offline call/lead/upload task rows
        val v7Helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migDbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE leads (
                                id TEXT NOT NULL PRIMARY KEY,
                                normalizedNumber TEXT NOT NULL,
                                rawNumber TEXT NOT NULL,
                                name TEXT NOT NULL,
                                status TEXT NOT NULL DEFAULT 'New',
                                nextFollowUpEpochMs INTEGER DEFAULT NULL,
                                assignedAgentId TEXT DEFAULT NULL,
                                notes TEXT DEFAULT NULL,
                                isDoNotRecord INTEGER NOT NULL DEFAULT 0,
                                updatedAtEpochMs INTEGER NOT NULL DEFAULT 0,
                                workspaceId TEXT DEFAULT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE calls (
                                id TEXT NOT NULL PRIMARY KEY,
                                clientCallId TEXT NOT NULL,
                                normalizedNumber TEXT NOT NULL,
                                rawNumber TEXT NOT NULL,
                                callType INTEGER NOT NULL,
                                startTimeEpochMs INTEGER NOT NULL,
                                durationSeconds INTEGER NOT NULL,
                                isLeadCall INTEGER NOT NULL,
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
                                localFilePath TEXT DEFAULT NULL,
                                workspaceId TEXT DEFAULT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
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
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        v7Helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO leads (id, normalizedNumber, rawNumber, name, workspaceId) VALUES ('lead_v7', '+919800000001', '9800000001', 'Legacy Lead', 'ws_legacy')"
            )
            db.execSQL(
                "INSERT INTO calls (id, clientCallId, normalizedNumber, rawNumber, callType, startTimeEpochMs, durationSeconds, isLeadCall, leadId, hasRecording, recordingUri, workspaceId) " +
                    "VALUES ('call_v7', 'client_v7', '+919800000001', '9800000001', 1, 1700000000000, 60, 1, 'lead_v7', 1, 'content://media/v7_offline.m4a', 'ws_legacy')"
            )
            db.execSQL(
                "INSERT INTO upload_tasks (id, callId, leadId, localFileUri, fileName, fileSizeBytes, status) " +
                    "VALUES ('task_v7', 'call_v7', 'lead_v7', 'content://media/v7_offline.m4a', 'v7_offline.m4a', 4096, 'PENDING')"
            )
        }
        v7Helper.close()

        // 2. Upgrade 7 -> 8 using real Leads365Database.MIGRATION_7_8
        val v8Helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migDbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(7, oldVersion)
                        assertEquals(8, newVersion)
                        Leads365Database.MIGRATION_7_8.migrate(db)
                    }
                })
                .build()
        )

        v8Helper.writableDatabase.use { db ->
            fun tableColumns(table: String): Set<String> {
                val cols = mutableSetOf<String>()
                db.query("PRAGMA table_info($table)").use { c ->
                    val nameIdx = c.getColumnIndexOrThrow("name")
                    while (c.moveToNext()) cols.add(c.getString(nameIdx))
                }
                return cols
            }

            assertTrue("calls must have tenantId column after 7->8", tableColumns("calls").contains("tenantId"))
            assertTrue("leads must have tenantId column after 7->8", tableColumns("leads").contains("tenantId"))
            assertTrue("upload_tasks must have tenantId column after 7->8", tableColumns("upload_tasks").contains("tenantId"))
            assertTrue("upload_tasks must have workspaceId column after 7->8", tableColumns("upload_tasks").contains("workspaceId"))

            // Verify legacy offline recording & pending upload rows are preserved intact with null tenantId
            db.query("SELECT id, recordingUri, syncStatus, tenantId, workspaceId FROM calls WHERE id = 'call_v7'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("content://media/v7_offline.m4a", c.getString(1))
                assertEquals("PENDING", c.getString(2))
                assertTrue(c.isNull(3))
                assertEquals("ws_legacy", c.getString(4))
            }
            db.query("SELECT id, localFileUri, status, tenantId, workspaceId FROM upload_tasks WHERE id = 'task_v7'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("content://media/v7_offline.m4a", c.getString(1))
                assertEquals("PENDING", c.getString(2))
                assertTrue(c.isNull(3))
                assertTrue(c.isNull(4))
            }

            // Insert new v8 tenant-pinned task row
            db.execSQL(
                "INSERT INTO upload_tasks (id, callId, leadId, localFileUri, fileName, fileSizeBytes, status, tenantId, workspaceId) " +
                    "VALUES ('task_v8', 'call_v7', 'lead_v7', 'content://media/v8.m4a', 'v8.m4a', 8192, 'PENDING', 'TENANT_A', 'WS_A')"
            )
            db.query("SELECT tenantId, workspaceId FROM upload_tasks WHERE id = 'task_v8'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("TENANT_A", c.getString(0))
                assertEquals("WS_A", c.getString(1))
            }
        }
        v8Helper.close()
        dbFile.delete()
    }
}
