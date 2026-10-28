package com.leads365.apk.data.repository

import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.UploadTaskEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadQueueRepositoryImpl @Inject constructor(
    private val uploadTaskDao: UploadTaskDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UploadQueueRepository {

    override fun getAllTasksFlow(): Flow<List<UploadTaskEntity>> = uploadTaskDao.getAllTasksFlow()

    override suspend fun enqueueTask(task: UploadTaskEntity): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            uploadTaskDao.insert(task)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to enqueue upload task: ${e.message}", e))
        }
    }

    override suspend fun getPendingTasks(): AppResult<List<UploadTaskEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(uploadTaskDao.getPendingTasks())
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to get pending upload tasks: ${e.message}", e))
        }
    }

    override suspend fun getTasksByStatus(status: String): AppResult<List<UploadTaskEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(uploadTaskDao.getTasksByStatus(status))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to get tasks with status $status: ${e.message}", e))
        }
    }

    override suspend fun getTaskById(id: String): AppResult<UploadTaskEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(uploadTaskDao.getById(id))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to get upload task $id: ${e.message}", e))
        }
    }

    override suspend fun getTaskByCallId(callId: String): AppResult<UploadTaskEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(uploadTaskDao.getByCallId(callId))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to get upload task by callId $callId: ${e.message}", e))
        }
    }

    override suspend fun updateStatus(id: String, status: String, error: String?): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            uploadTaskDao.updateStatus(id, status, System.currentTimeMillis(), error)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update task $id status: ${e.message}", e))
        }
    }

    override suspend fun incrementRetry(id: String, error: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            uploadTaskDao.incrementRetry(id, error, System.currentTimeMillis())
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to increment retry for task $id: ${e.message}", e))
        }
    }

    override suspend fun deleteTaskById(id: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            uploadTaskDao.deleteById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to delete task $id: ${e.message}", e))
        }
    }

    override suspend fun clearCompleted(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            uploadTaskDao.clearCompleted()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to clear completed upload tasks: ${e.message}", e))
        }
    }

    override suspend fun cleanExpiredTerminalTasks(retentionPeriodMs: Long): AppResult<Int> = withContext(ioDispatcher) {
        try {
            val cutoff = System.currentTimeMillis() - retentionPeriodMs
            val deletedCount = uploadTaskDao.deleteExpiredTerminalTasks(cutoff)
            AppResult.Success(deletedCount)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to clean expired terminal upload tasks: ${e.message}", e))
        }
    }
}
