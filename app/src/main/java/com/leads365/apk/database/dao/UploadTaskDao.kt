package com.leads365.apk.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leads365.apk.database.entity.UploadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: UploadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<UploadTaskEntity>)

    @Update
    suspend fun update(task: UploadTaskEntity)

    @Query("SELECT * FROM upload_tasks WHERE id = :id")
    suspend fun getById(id: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE callId = :callId LIMIT 1")
    suspend fun getByCallId(callId: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE status = :status ORDER BY createdAtEpochMs ASC")
    suspend fun getTasksByStatus(status: String): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_tasks WHERE status = 'PENDING' ORDER BY createdAtEpochMs ASC")
    suspend fun getPendingTasks(): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_tasks ORDER BY updatedAtEpochMs DESC")
    fun getAllTasksFlow(): Flow<List<UploadTaskEntity>>

    @Query("UPDATE upload_tasks SET status = :status, updatedAtEpochMs = :timestamp, lastError = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, timestamp: Long = System.currentTimeMillis(), error: String? = null)

    @Query("UPDATE upload_tasks SET retryCount = retryCount + 1, lastError = :error, updatedAtEpochMs = :timestamp WHERE id = :id")
    suspend fun incrementRetry(id: String, error: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM upload_tasks WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM upload_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM upload_tasks WHERE status IN ('COMPLETED', 'UPLOADED', 'FAILED_PERMANENT', 'SKIPPED_DNR', 'SKIPPED_NOT_LEAD', 'REJECTED_BY_SERVER', 'CANCELLED') AND updatedAtEpochMs < :cutoffTimestampEpochMs")
    suspend fun deleteExpiredTerminalTasks(cutoffTimestampEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("DELETE FROM upload_tasks")
    suspend fun clearAll()
}
