package com.leads365.apk.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leads365.apk.database.entity.LeadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LeadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lead: LeadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(leads: List<LeadEntity>)

    @Update
    suspend fun update(lead: LeadEntity)

    @Delete
    suspend fun delete(lead: LeadEntity)

    @Query("SELECT * FROM leads WHERE id = :id")
    suspend fun getById(id: String): LeadEntity?

    @Query("SELECT * FROM leads WHERE normalizedNumber = :normalizedNumber LIMIT 1")
    suspend fun getByNormalizedNumber(normalizedNumber: String): LeadEntity?

    @Query("SELECT * FROM leads ORDER BY updatedAtEpochMs DESC")
    fun getAllLeadsFlow(): Flow<List<LeadEntity>>

    @Query("SELECT * FROM leads ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllLeads(): List<LeadEntity>

    @Query("""
        SELECT * FROM leads 
        WHERE (:status = 'ALL' OR status = :status)
        AND (:query = '' OR name LIKE '%' || :query || '%' OR rawNumber LIKE '%' || :query || '%' OR normalizedNumber LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%')
        ORDER BY updatedAtEpochMs DESC
    """)
    fun searchLeadsFlow(query: String, status: String): Flow<List<LeadEntity>>

    @Query("DELETE FROM leads WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM leads WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM leads WHERE id NOT IN (:activeIds)")
    suspend fun retainOnlyActiveLeads(activeIds: List<String>): Int

    @Query("SELECT MAX(updatedAtEpochMs) FROM leads")
    suspend fun getLatestUpdatedAt(): Long?

    @Query("DELETE FROM leads")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM leads")
    suspend fun count(): Int
}
