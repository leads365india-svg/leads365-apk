package com.leads365.apk.data.repository

import com.leads365.apk.core.AppResult
import com.leads365.apk.database.entity.FollowUpEntity
import kotlinx.coroutines.flow.Flow

interface FollowUpRepository {

    fun getAllFollowUpsFlow(): Flow<List<FollowUpEntity>>

    fun getPendingFollowUpsFlow(): Flow<List<FollowUpEntity>>

    suspend fun getTodayFollowUps(): List<FollowUpEntity>

    suspend fun getUpcomingFollowUps(): List<FollowUpEntity>

    suspend fun getOverdueFollowUps(): List<FollowUpEntity>

    suspend fun createFollowUp(
        leadId: String?,
        leadName: String,
        leadPhone: String,
        title: String,
        scheduledAtEpochMs: Long,
        priority: String = FollowUpEntity.PRIORITY_MEDIUM,
        notes: String = ""
    ): AppResult<FollowUpEntity>

    suspend fun completeFollowUp(
        id: String,
        completionNotes: String = ""
    ): AppResult<Unit>

    suspend fun rescheduleFollowUp(
        id: String,
        newScheduledAtEpochMs: Long,
        notes: String = ""
    ): AppResult<Unit>

    suspend fun syncFollowUps(): AppResult<Int>
}
