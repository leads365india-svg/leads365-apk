package com.leads365.apk.data.repository

import com.leads365.apk.core.AppResult
import com.leads365.apk.database.entity.UploadTaskEntity
import kotlinx.coroutines.flow.Flow

interface UploadQueueRepository {
    fun getAllTasksFlow(): Flow<List<UploadTaskEntity>>
    suspend fun enqueueTask(task: UploadTaskEntity): AppResult<Unit>
    suspend fun getPendingTasks(): AppResult<List<UploadTaskEntity>>
    suspend fun getTasksByStatus(status: String): AppResult<List<UploadTaskEntity>>
    suspend fun getTaskById(id: String): AppResult<UploadTaskEntity?>
    suspend fun getTaskByCallId(callId: String): AppResult<UploadTaskEntity?>
    suspend fun updateStatus(id: String, status: String, error: String? = null): AppResult<Unit>
    suspend fun incrementRetry(id: String, error: String): AppResult<Unit>
    suspend fun deleteTaskById(id: String): AppResult<Unit>
    suspend fun clearCompleted(): AppResult<Unit>
}
