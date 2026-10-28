package com.leads365.apk.data.repository

import com.leads365.apk.core.AppResult
import com.leads365.apk.database.entity.LeadEntity
import kotlinx.coroutines.flow.Flow

data class LeadSyncResult(
    val insertedOrUpdatedCount: Int,
    val deletedCount: Int,
    val isFullSync: Boolean,
    val lastSyncTimestampEpochMs: Long
)

interface LeadRepository {
    fun getAllLeadsFlow(): Flow<List<LeadEntity>>
    fun searchLeadsFlow(query: String, status: String): Flow<List<LeadEntity>>
    suspend fun getAllLeads(): AppResult<List<LeadEntity>>
    suspend fun getLeadById(id: String): AppResult<LeadEntity?>
    suspend fun getLeadByNormalizedNumber(normalizedNumber: String): AppResult<LeadEntity?>
    suspend fun saveLead(lead: LeadEntity): AppResult<Unit>
    suspend fun saveLeads(leads: List<LeadEntity>): AppResult<Unit>
    suspend fun deleteLeadById(id: String): AppResult<Unit>
    suspend fun clearAllLeads(): AppResult<Unit>
    suspend fun syncLeads(forceFullSync: Boolean = false): AppResult<LeadSyncResult>
    fun getLastSyncTimestamp(): Long
}
