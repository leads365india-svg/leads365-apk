package com.leads365.apk.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity

import com.leads365.apk.database.dao.FollowUpDao
import com.leads365.apk.database.entity.FollowUpEntity

@Database(
    entities = [
        LeadEntity::class,
        CallEntity::class,
        UploadTaskEntity::class,
        FollowUpEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class Leads365Database : RoomDatabase() {
    abstract fun leadDao(): LeadDao
    abstract fun callDao(): CallDao
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun followUpDao(): FollowUpDao

    companion object {
        const val DATABASE_NAME = "leads365.db"

        /**
         * Migration from version 1 to version 2 adding Phase 5 lead sync fields
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE leads ADD COLUMN status TEXT NOT NULL DEFAULT 'New'")
                db.execSQL("ALTER TABLE leads ADD COLUMN nextFollowUpEpochMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE leads ADD COLUMN assignedAgentId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_leads_status ON leads(status)")
            }
        }

        /**
         * Migration from version 2 to version 3 adding Phase 6 call reporting fields
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN clientCallId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE calls SET clientCallId = id WHERE clientCallId = ''")
                db.execSQL("ALTER TABLE calls ADD COLUMN simId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE calls ADD COLUMN callOutcome TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE calls ADD COLUMN notes TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE calls ADD COLUMN nextFollowUpEpochMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE calls ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE calls ADD COLUMN syncErrorMessage TEXT DEFAULT NULL")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_calls_clientCallId ON calls(clientCallId)")
            }
        }

        /**
         * Migration from version 3 to version 4 adding Phase 17 follow-up tracking
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS follow_ups (
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
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        updatedAtEpochMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_follow_ups_status ON follow_ups(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_follow_ups_scheduledAtEpochMs ON follow_ups(scheduledAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_follow_ups_leadId ON follow_ups(leadId)")
            }
        }

        /**
         * Migration from version 4 to version 5 adding sourceCallId to follow_ups
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE follow_ups ADD COLUMN sourceCallId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_follow_ups_sourceCallId ON follow_ups(sourceCallId)")
            }
        }
    }
}
