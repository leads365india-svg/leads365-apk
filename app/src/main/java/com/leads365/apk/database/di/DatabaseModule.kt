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

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): Leads365Database {
        return Room.databaseBuilder(
            context,
            Leads365Database::class.java,
            Leads365Database.DATABASE_NAME
        )
            .addMigrations(
                Leads365Database.MIGRATION_1_2,
                Leads365Database.MIGRATION_2_3
            )
            .fallbackToDestructiveMigration()
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
}
