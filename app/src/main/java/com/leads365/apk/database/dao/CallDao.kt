package com.leads365.apk.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leads365.apk.database.entity.CallEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(call: CallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(calls: List<CallEntity>)

    @Update
    suspend fun update(call: CallEntity)

    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun getById(id: String): CallEntity?

    @Query("SELECT * FROM calls WHERE clientCallId = :clientCallId LIMIT 1")
    suspend fun getByClientCallId(clientCallId: String): CallEntity?

    @Query("""
        SELECT * FROM calls 
        WHERE normalizedNumber = :normalizedNumber 
        AND startTimeEpochMs BETWEEN :minStartEpochMs AND :maxStartEpochMs 
        LIMIT 1
    """)
    suspend fun findCallBySignature(normalizedNumber: String, minStartEpochMs: Long, maxStartEpochMs: Long): CallEntity?

    @Query("""
        SELECT * FROM calls 
        WHERE normalizedNumber = :normalizedNumber 
        AND startTimeEpochMs BETWEEN :minStartEpochMs AND :maxStartEpochMs
    """)
    suspend fun findCandidatesBySignature(normalizedNumber: String, minStartEpochMs: Long, maxStartEpochMs: Long): List<CallEntity>

    @Query("SELECT * FROM calls ORDER BY startTimeEpochMs DESC")
    fun getAllCallsFlow(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE isLeadCall = 1 ORDER BY startTimeEpochMs DESC")
    fun getLeadCallsFlow(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE normalizedNumber = :normalizedNumber ORDER BY startTimeEpochMs DESC")
    fun getCallsForNumberFlow(normalizedNumber: String): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls ORDER BY startTimeEpochMs DESC LIMIT :limit")
    suspend fun getRecentCalls(limit: Int): List<CallEntity>

    @Query("SELECT * FROM calls WHERE syncStatus = 'PENDING' AND isLeadCall = 1 ORDER BY startTimeEpochMs ASC")
    suspend fun getPendingSyncCalls(): List<CallEntity>

    @Query("""
        SELECT * FROM calls 
        WHERE (isLeadCall = 0 OR leadId IS NULL OR leadId = '') 
        AND (normalizedNumber = :normalizedNumber OR rawNumber = :rawNumber OR rawNumber = :normalizedNumber OR normalizedNumber = :rawNumber)
        ORDER BY startTimeEpochMs DESC
    """)
    suspend fun getUnassignedCallsForNumber(normalizedNumber: String, rawNumber: String): List<CallEntity>

    @Query("""
        UPDATE calls 
        SET leadId = :leadId, isLeadCall = 1, syncStatus = :syncStatus 
        WHERE id = :callId OR clientCallId = :callId
    """)
    suspend fun associateCallToLead(callId: String, leadId: String, syncStatus: String = "PENDING"): Int

    @Query("""
        UPDATE calls 
        SET callOutcome = :outcome, notes = :notes, nextFollowUpEpochMs = :nextFollowUp, syncStatus = :syncStatus 
        WHERE id = :callId OR clientCallId = :callId
    """)
    suspend fun updateOutcome(callId: String, outcome: String, notes: String?, nextFollowUp: Long?, syncStatus: String = "PENDING"): Int

    @Query("UPDATE calls SET syncStatus = :status, syncErrorMessage = :errorMessage WHERE id = :callId OR clientCallId = :callId")
    suspend fun updateSyncStatus(callId: String, status: String, errorMessage: String?): Int

    @Query("""
        UPDATE calls 
        SET hasRecording = :hasRecording, recordingUri = :recordingUri, recordingFileName = :recordingFileName, recordingFileSizeBytes = :recordingFileSizeBytes 
        WHERE id = :callId OR clientCallId = :callId
    """)
    suspend fun updateRecording(callId: String, hasRecording: Boolean, recordingUri: String?, recordingFileName: String?, recordingFileSizeBytes: Long?): Int

    @Query("DELETE FROM calls WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM calls")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM calls")
    suspend fun count(): Int
}
