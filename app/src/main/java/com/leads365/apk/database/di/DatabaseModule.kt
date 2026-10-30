package com.leads365.apk.database.di

import android.content.Context
import androidx.room.Room
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.dao.UploadTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import com.leads365.apk.database.security.DatabaseKeyManager
import com.leads365.apk.database.security.DatabaseSecurityMigrator
import net.sqlcipher.database.SupportFactory

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabaseKeyManager(
        @ApplicationContext context: Context
    ): DatabaseKeyManager = DatabaseKeyManager(context)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        databaseKeyManager: DatabaseKeyManager
    ): Leads365Database {
        val passphrase = databaseKeyManager.getOrCreatePassphrase()

        // Safely migrate existing plaintext database to SQLCipher if present
        DatabaseSecurityMigrator.ensureEncrypted(
            context = context,
            databaseName = Leads365Database.DATABASE_NAME,
            passphrase = passphrase
        )

        val supportFactory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            Leads365Database::class.java,
            Leads365Database.DATABASE_NAME
        )
            .openHelperFactory(supportFactory)
            .addMigrations(
                Leads365Database.MIGRATION_1_2,
                Leads365Database.MIGRATION_2_3,
                Leads365Database.MIGRATION_3_4,
                Leads365Database.MIGRATION_4_5
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideLeadDao(database: Leads365Database): LeadDao {
        return database.leadDao()
    }

    @Provides
    @Singleton
    fun provideCallDao(database: Leads365Database): CallDao {
        return database.callDao()
    }

    @Provides
    @Singleton
    fun provideUploadTaskDao(database: Leads365Database): UploadTaskDao {
        return database.uploadTaskDao()
    }

    @Provides
    @Singleton
    fun provideFollowUpDao(database: Leads365Database): com.leads365.apk.database.dao.FollowUpDao {
        return database.followUpDao()
    }
}
