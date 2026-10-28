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

@Database(
    entities = [
        LeadEntity::class,
        CallEntity::class,
        UploadTaskEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class Leads365Database : RoomDatabase() {
    abstract fun leadDao(): LeadDao
    abstract fun callDao(): CallDao
    abstract fun uploadTaskDao(): UploadTaskDao

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
    }
}
