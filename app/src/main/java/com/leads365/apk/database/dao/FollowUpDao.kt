package com.leads365.apk.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leads365.apk.database.entity.FollowUpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowUpDao {

    @Query("SELECT * FROM follow_ups ORDER BY scheduledAtEpochMs ASC")
    fun getAllFollowUpsFlow(): Flow<List<FollowUpEntity>>

    @Query("SELECT * FROM follow_ups WHERE status = :status ORDER BY scheduledAtEpochMs ASC")
    fun getFollowUpsByStatusFlow(status: String = FollowUpEntity.STATUS_PENDING): Flow<List<FollowUpEntity>>

    @Query("SELECT * FROM follow_ups WHERE id = :id")
    suspend fun getById(id: String): FollowUpEntity?

    @Query("SELECT * FROM follow_ups WHERE leadId = :leadId ORDER BY scheduledAtEpochMs DESC")
    suspend fun getByLeadId(leadId: String): List<FollowUpEntity>

    @Query("SELECT * FROM follow_ups WHERE syncStatus != 'SYNCED'")
    suspend fun getPendingSyncFollowUps(): List<FollowUpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(followUp: FollowUpEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(followUps: List<FollowUpEntity>)

    @Update
    suspend fun update(followUp: FollowUpEntity)

    @Query("UPDATE follow_ups SET status = 'completed', syncStatus = :syncStatus, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun markCompleted(
        id: String,
        syncStatus: String = FollowUpEntity.SYNC_STATUS_PENDING_COMPLETE,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE follow_ups SET scheduledAtEpochMs = :newTimeMs, notes = :notes, syncStatus = :syncStatus, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun reschedule(
        id: String,
        newTimeMs: Long,
        notes: String,
        syncStatus: String = FollowUpEntity.SYNC_STATUS_PENDING_UPDATE,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM follow_ups WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM follow_ups WHERE status = 'pending'")
    suspend fun countPending(): Int

    @Query("DELETE FROM follow_ups WHERE id NOT IN (:validIds) AND syncStatus = 'SYNCED'")
    suspend fun deleteStaleSynced(validIds: List<String>): Int
}
