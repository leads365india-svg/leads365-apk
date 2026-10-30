package com.leads365.apk.data.repository

import android.content.Context
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.models.LeadSyncData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LeadRepositoryImpl(
    private val leadDao: LeadDao,
    private val leadApiService: LeadApiService,
    @ApplicationContext private val context: Context,
    private val normalizer: PhoneNumberNormalizer,
    private val callRepositoryProvider: Provider<CallRepository>? = null,
    directCallRepository: CallRepository? = null
) : LeadRepository {

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var callRepo: CallRepository? = directCallRepository

    @Inject
    constructor(
        leadDao: LeadDao,
        leadApiService: LeadApiService,
        @ApplicationContext context: Context,
        normalizer: PhoneNumberNormalizer,
        callRepositoryProvider: Provider<CallRepository>
    ) : this(leadDao, leadApiService, context, normalizer, callRepositoryProvider, null)

    constructor(
        leadDao: LeadDao,
        leadApiService: LeadApiService,
        context: Context,
        normalizer: PhoneNumberNormalizer = PhoneNumberNormalizer("IN")
    ) : this(leadDao, leadApiService, context, normalizer, null, null)

    constructor(
        leadDao: LeadDao,
        leadApiService: LeadApiService,
        context: Context,
        normalizer: PhoneNumberNormalizer = PhoneNumberNormalizer("IN"),
        dispatcher: CoroutineDispatcher
    ) : this(leadDao, leadApiService, context, normalizer, null, null) {
        this.ioDispatcher = dispatcher
    }

    constructor(
        leadDao: LeadDao,
        leadApiService: LeadApiService,
        context: Context,
        normalizer: PhoneNumberNormalizer = PhoneNumberNormalizer("IN"),
        dispatcher: CoroutineDispatcher,
        callRepository: CallRepository?
    ) : this(leadDao, leadApiService, context, normalizer, null, callRepository) {
        this.ioDispatcher = dispatcher
    }

    override fun getAllLeadsFlow(): Flow<List<LeadEntity>> {
        return leadDao.getAllLeadsFlow()
    }

    override fun searchLeadsFlow(query: String, status: String): Flow<List<LeadEntity>> {
        val normalizedStatus = if (status.isBlank() || status.equals("all", ignoreCase = true)) "ALL" else status.trim()
        return leadDao.searchLeadsFlow(query.trim(), normalizedStatus)
    }

    override suspend fun getAllLeads(): AppResult<List<LeadEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(leadDao.getAllLeads())
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch leads: ${e.message}", e))
        }
    }

    override suspend fun getLeadById(id: String): AppResult<LeadEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(leadDao.getById(id))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch lead $id: ${e.message}", e))
        }
    }

    override suspend fun getLeadByNormalizedNumber(normalizedNumber: String): AppResult<LeadEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(leadDao.getByNormalizedNumber(normalizedNumber))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to lookup lead by number: ${e.message}", e))
        }
    }

    override suspend fun saveLead(lead: LeadEntity): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            leadDao.insert(lead)
            try {
                val repo = callRepo ?: callRepositoryProvider?.get()
                repo?.reconcileCallsForLead(lead)
            } catch (e: Exception) {
                // Non-fatal reconciliation error
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to save lead: ${e.message}", e))
        }
    }

    override suspend fun saveLeads(leads: List<LeadEntity>): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            leadDao.insertAll(leads)
            try {
                val repo = callRepo ?: callRepositoryProvider?.get()
                if (repo != null) {
                    for (lead in leads) {
                        repo.reconcileCallsForLead(lead)
                    }
                }
            } catch (e: Exception) {
                // Non-fatal reconciliation error
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to save leads list: ${e.message}", e))
        }
    }

    override suspend fun deleteLeadById(id: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            leadDao.deleteById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to delete lead $id: ${e.message}", e))
        }
    }

    override suspend fun clearAllLeads(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            leadDao.clearAll()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to clear leads: ${e.message}", e))
        }
    }

    override suspend fun syncLeads(forceFullSync: Boolean): AppResult<LeadSyncResult> = withContext(ioDispatcher) {
        val prefs = context.getSharedPreferences(PREFS_LEAD_SYNC, Context.MODE_PRIVATE)
        val lastSyncTimestamp = if (forceFullSync) null else {
            val ts = prefs.getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
            if (ts > 0L) ts else null
        }

        try {
            val response = leadApiService.syncLeads(sinceTimestampEpochMs = lastSyncTimestamp)
            if (!response.success) {
                return@withContext AppResult.Failure(
                    AppError.NetworkError(response.error ?: "Lead synchronization failed.")
                )
            }

            val data = response.data ?: LeadSyncData()
            val isFull = lastSyncTimestamp == null || data.fullSyncRequired

            // Map DTOs to Room LeadEntity
            val leadEntities = data.leads.map { dto ->
                val normalized = if (!dto.normalizedPhone.isNullOrBlank()) {
                    dto.normalizedPhone
                } else {
                    normalizer.normalizeToE164(dto.phone) ?: dto.phone
                }
                LeadEntity(
                    id = dto.id,
                    name = dto.name,
                    rawNumber = dto.phone,
                    normalizedNumber = normalized,
                    status = dto.status,
                    nextFollowUpEpochMs = dto.nextFollowUpAt,
                    isDoNotRecord = dto.isDoNotRecord,
                    notes = dto.notes ?: "",
                    assignedAgentId = dto.assignedToAgentId,
                    enabled = true,
                    createdAtEpochMs = dto.updatedAt,
                    updatedAtEpochMs = dto.updatedAt
                )
            }

            if (isFull) {
                // Full reconciliation: retain only active assigned leads returned by the server
                val activeIds = leadEntities.map { it.id }
                if (activeIds.isNotEmpty()) {
                    leadDao.retainOnlyActiveLeads(activeIds)
                } else {
                    leadDao.clearAll()
                }
            } else {
                // Delta sync: delete explicitly deleted or reassigned leads
                if (data.deletedLeadIds.isNotEmpty()) {
                    leadDao.deleteByIds(data.deletedLeadIds)
                }
            }

            // Insert or update leads
            if (leadEntities.isNotEmpty()) {
                leadDao.insertAll(leadEntities)
                try {
                    val repo = callRepo ?: callRepositoryProvider?.get()
                    if (repo != null) {
                        for (lead in leadEntities) {
                            repo.reconcileCallsForLead(lead)
                        }
                    }
                } catch (e: Exception) {
                    // Non-fatal reconciliation error during sync
                }
            }

            val syncTime = response.serverTime
            prefs.edit().putLong(KEY_LAST_SYNC_EPOCH_MS, syncTime).apply()

            AppResult.Success(
                LeadSyncResult(
                    insertedOrUpdatedCount = leadEntities.size,
                    deletedCount = data.deletedLeadIds.size,
                    isFullSync = isFull,
                    lastSyncTimestampEpochMs = syncTime
                )
            )
        } catch (e: HttpException) {
            val code = e.code()
            if (code == 429) {
                val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull() ?: 60L
                AppResult.Failure(
                    AppError.RateLimitError(
                        message = "Server rate limit reached. Retry after $retryAfter seconds.",
                        retryAfterSeconds = retryAfter,
                        cause = e
                    )
                )
            } else if (code == 401 || code == 403) {
                AppResult.Failure(
                    AppError.AuthError(
                        message = "Session expired or unauthorized to access leads.",
                        cause = e
                    )
                )
            } else {
                AppResult.Failure(
                    AppError.NetworkError(
                        message = "Server returned error $code: ${e.message()}",
                        httpCode = code,
                        cause = e
                    )
                )
            }
        } catch (e: IOException) {
            AppResult.Failure(
                AppError.NetworkError(
                    message = "Network unavailable. Offline leads preserved.",
                    cause = e
                )
            )
        } catch (e: Exception) {
            AppResult.Failure(
                AppError.UnknownError(
                    message = "Unexpected sync error: ${e.message}",
                    cause = e
                )
            )
        }
    }

    override fun getLastSyncTimestamp(): Long {
        return context.getSharedPreferences(PREFS_LEAD_SYNC, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
    }

    companion object {
        private const val PREFS_LEAD_SYNC = "leads365_lead_sync_prefs"
        private const val KEY_LAST_SYNC_EPOCH_MS = "key_last_sync_epoch_ms"
    }
}
