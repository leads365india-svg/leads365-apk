package com.leads365.apk.data.repository

import android.provider.CallLog
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import android.util.Log
import com.leads365.apk.network.auth.TokenProvider
import retrofit2.HttpException
import java.io.IOException
import android.content.Context
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.recordings.DiscoveryResult
import com.leads365.apk.recordings.RecordingDiscoveryCoordinator
import com.leads365.apk.work.RecordingUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.recordings.TargetedRecordingMatcher
import java.util.UUID
import javax.inject.Provider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl(
    private val callDao: CallDao,
    private val callApiService: CallApiService,
    private val tokenProvider: TokenProvider? = null,
    private val uploadTaskDao: UploadTaskDao? = null,
    private val recordingDiscoveryCoordinatorProvider: Provider<RecordingDiscoveryCoordinator>? = null,
    private val context: Context? = null,
    directCoordinator: RecordingDiscoveryCoordinator? = null,
    private val leadDao: LeadDao? = null
) : CallRepository {

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var directRecordingDiscoveryCoordinator: RecordingDiscoveryCoordinator? = directCoordinator

    @Inject
    constructor(
        callDao: CallDao,
        callApiService: CallApiService,
        tokenProvider: TokenProvider,
        uploadTaskDao: UploadTaskDao,
        recordingDiscoveryCoordinatorProvider: Provider<RecordingDiscoveryCoordinator>,
        @ApplicationContext context: Context,
        leadDao: LeadDao
    ) : this(
        callDao = callDao,
        callApiService = callApiService,
        tokenProvider = tokenProvider,
        uploadTaskDao = uploadTaskDao,
        recordingDiscoveryCoordinatorProvider = recordingDiscoveryCoordinatorProvider,
        context = context,
        directCoordinator = null,
        leadDao = leadDao
    )

    constructor(
        callDao: CallDao,
        callApiService: CallApiService,
        tokenProvider: TokenProvider? = null
    ) : this(callDao, callApiService, tokenProvider, null, null, null, null)

    constructor(
        callDao: CallDao,
        callApiService: CallApiService,
        dispatcher: CoroutineDispatcher,
        tokenProvider: TokenProvider? = null
    ) : this(callDao, callApiService, tokenProvider, null, null, null, null) {
        this.ioDispatcher = dispatcher
    }

    constructor(
        callDao: CallDao,
        callApiService: CallApiService,
        dispatcher: CoroutineDispatcher,
        tokenProvider: TokenProvider? = null,
        uploadTaskDao: UploadTaskDao? = null,
        recordingDiscoveryCoordinator: RecordingDiscoveryCoordinator? = null,
        context: Context? = null
    ) : this(callDao, callApiService, tokenProvider, uploadTaskDao, null, context) {
        this.ioDispatcher = dispatcher
        this.directRecordingDiscoveryCoordinator = recordingDiscoveryCoordinator
    }

    override fun getAllCallsFlow(): Flow<List<CallEntity>> = callDao.getAllCallsFlow()

    override fun getLeadCallsFlow(): Flow<List<CallEntity>> = callDao.getLeadCallsFlow()

    override fun getCallsForNumberFlow(normalizedNumber: String): Flow<List<CallEntity>> =
        callDao.getCallsForNumberFlow(normalizedNumber)

    override suspend fun getRecentCalls(limit: Int): AppResult<List<CallEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getRecentCalls(limit))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch recent calls: ${e.message}", e))
        }
    }

    override suspend fun getCallById(id: String): AppResult<CallEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getById(id))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch call $id: ${e.message}", e))
        }
    }

    override suspend fun getByClientCallId(clientCallId: String): AppResult<CallEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getByClientCallId(clientCallId))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch call by clientCallId $clientCallId: ${e.message}", e))
        }
    }

    override suspend fun findCallBySignature(
        normalizedNumber: String,
        startTimeEpochMs: Long,
        toleranceMs: Long
    ): AppResult<CallEntity?> = withContext(ioDispatcher) {
        try {
            val minStart = startTimeEpochMs - toleranceMs
            val maxStart = startTimeEpochMs + toleranceMs
            AppResult.Success(callDao.findCallBySignature(normalizedNumber, minStart, maxStart))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to search call by signature: ${e.message}", e))
        }
    }

    override suspend fun recordCall(call: CallEntity): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.insert(call)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to record call: ${e.message}", e))
        }
    }

    override suspend fun updateCall(call: CallEntity): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.update(call)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update call: ${e.message}", e))
        }
    }

    override suspend fun updateOutcome(
        callId: String,
        outcome: String,
        notes: String?,
        nextFollowUpEpochMs: Long?,
        workspaceId: String?
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.updateOutcome(callId, outcome, notes, nextFollowUpEpochMs, "PENDING", workspaceId)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update call outcome: ${e.message}", e))
        }
    }

    override suspend fun updateRecordingMatch(
        callId: String,
        hasRecording: Boolean,
        recordingUri: String?,
        recordingFileName: String?,
        recordingFileSizeBytes: Long?
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.updateRecording(
                callId = callId,
                hasRecording = hasRecording,
                recordingUri = recordingUri,
                recordingFileName = recordingFileName,
                recordingFileSizeBytes = recordingFileSizeBytes
            )
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update call recording match: ${e.message}", e))
        }
    }

    override suspend fun getPendingSyncCalls(): AppResult<List<CallEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getPendingSyncCalls())
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch pending calls: ${e.message}", e))
        }
    }

    override suspend fun reportCallToBackend(callId: String): AppResult<CallReportResponse> = withContext(ioDispatcher) {
        val initialCall = try {
            callDao.getById(callId) ?: callDao.getByClientCallId(callId)
        } catch (e: Exception) {
            return@withContext AppResult.Failure(AppError.StorageError("Failed to find call $callId", e))
        }

        if (initialCall == null) {
            return@withContext AppResult.Failure(AppError.StorageError("Call $callId not found in database"))
        }

        var call = initialCall
        val activeTenantId = tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }
        val callTenantId = call.tenantId?.trim()?.ifEmpty { null }

        // Quarantine if call is already marked quarantined or has missing/mismatched tenant identity when a session is active
        if (call.syncStatus == CallEntity.SYNC_STATUS_QUARANTINED) {
            if (activeTenantId != null && callTenantId == activeTenantId) {
                // Legitimate owner re-logged in; continue recovery
            } else {
                return@withContext AppResult.Failure(
                    AppError.AuthError("Call $callId is quarantined for tenant '${callTenantId ?: "unbound"}' and cannot be uploaded under '${activeTenantId ?: "unauthenticated"}'")
                )
            }
        }

        if (activeTenantId != null) {
            val prefixTenantMismatch = call.clientCallId.startsWith("call_") &&
                !call.clientCallId.startsWith("call_${activeTenantId}_")
            if (callTenantId.isNullOrBlank() || callTenantId != activeTenantId || prefixTenantMismatch) {
                val quarantineMsg = "Cross-tenant quarantine: callTenant='${callTenantId ?: "unbound"}', sessionTenant='$activeTenantId'"
                Log.w(TAG, "$quarantineMsg (callId=${call.id}). Preserving local audio/metadata without attribution.")
                callDao.updateSyncStatus(call.id, CallEntity.SYNC_STATUS_QUARANTINED, quarantineMsg)
                uploadTaskDao?.let { dao ->
                    val t1 = dao.getByCallId(call.id)
                    val t2 = if (call.clientCallId != call.id) dao.getByCallId(call.clientCallId) else null
                    listOfNotNull(t1, t2).distinctBy { it.id }.forEach { t ->
                        dao.updateStatus(t.id, UploadTaskEntity.STATE_TENANT_MISMATCH, System.currentTimeMillis(), quarantineMsg)
                    }
                }
                return@withContext AppResult.Failure(AppError.AuthError(quarantineMsg))
            }
        } else if (tokenProvider != null) {
            Log.w(TAG, "Call sync safely deferred for ${call.id}: active tenant session is not available")
            if (call.syncStatus != "DEFERRED_IDENTITY") {
                callDao.updateSyncStatus(call.id, "DEFERRED_IDENTITY", "Awaiting tenant/device identity")
            }
            return@withContext AppResult.Failure(AppError.AuthError("Synchronization deferred: active tenant identity not available"))
        }

        if (call.syncStatus == "DEFERRED_IDENTITY" || call.clientCallId.startsWith("local_pending_call_")) {
            // Strictly require immutable tenantId == activeTenantId; NEVER dynamically claim an unbound or foreign call!
            val boundTenantId = callTenantId ?: return@withContext AppResult.Failure(
                AppError.AuthError("Cannot upgrade DEFERRED_IDENTITY call without bound tenantId")
            )
            val installId = tokenProvider?.getDeviceIdOrInstallId()?.trim()?.ifEmpty { null }
            if (activeTenantId == null || activeTenantId != boundTenantId || installId.isNullOrBlank()) {
                Log.w(TAG, "Call sync safely deferred for ${call.id}: matching tenant or device identity not available")
                callDao.updateSyncStatus(call.id, "DEFERRED_IDENTITY", "Awaiting tenant/device identity")
                return@withContext AppResult.Failure(AppError.AuthError("Synchronization deferred: matching tenant or device identity not available"))
            }

            // Same-tenant identity verified: upgrade to canonical clientCallId
            val suffix = call.clientCallId.removePrefix("local_pending_call_")
            val canonicalClientCallId = "call_${boundTenantId}_${installId}_$suffix"
            val upgraded = call.copy(clientCallId = canonicalClientCallId, syncStatus = "PENDING", tenantId = boundTenantId)
            callDao.update(upgraded)
            call = upgraded
        }

        val directionStr = when (call.callType) {
            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            CallLog.Calls.REJECTED_TYPE -> "MISSED"
            else -> if (call.callOutcome.equals("Missed", ignoreCase = true)) "MISSED" else "OUTGOING"
        }

        val isUnlinkedOrAuto = call.leadId.isNullOrBlank() ||
            call.leadId == "unlinked" ||
            call.leadId?.startsWith("lead_auto_") == true ||
            call.notes?.contains("Auto-captured") == true ||
            call.notes?.contains("Unlinked") == true ||
            call.notes?.contains("Missed") == true

        val isAutoLead = isUnlinkedOrAuto && (
            call.callType == CallLog.Calls.INCOMING_TYPE ||
            call.callType == CallLog.Calls.MISSED_TYPE ||
            call.callType == CallLog.Calls.REJECTED_TYPE ||
            call.callOutcome.equals("Missed", ignoreCase = true)
        )

        val resolvedOutcome = call.callOutcome ?: if (call.callType == CallLog.Calls.MISSED_TYPE || call.callType == CallLog.Calls.REJECTED_TYPE) "Missed" else null

        val isWhatsAppVoip = call.recordingOrigin == "whatsapp_voip"

        val request = CallReportRequest(
            clientCallId = call.clientCallId,
            leadId = call.leadId ?: "",
            phoneNumber = call.normalizedNumber,
            callDirection = directionStr,
            startTimeMs = call.startTimeEpochMs,
            durationSeconds = call.durationSeconds,
            callOutcome = resolvedOutcome,
            notes = call.notes,
            nextFollowUpAt = call.nextFollowUpEpochMs,
            simId = call.simId,
            autoCreateLead = if (isAutoLead) true else null,
            callType = call.callType,
            workspaceId = call.workspaceId,
            source = if (isWhatsAppVoip) "whatsapp_voip" else "android_sync",
            contactName = if (isWhatsAppVoip && call.rawNumber.isNotBlank()) call.rawNumber else null,
            sourceApp = if (isWhatsAppVoip) {
                if (call.notes?.contains("Business", ignoreCase = true) == true) "com.whatsapp.w4b" else "com.whatsapp"
            } else null
        )

        try {
            val response = callApiService.reportCall(request)
            if (response.success) {
                callDao.updateSyncStatus(call.id, "SYNCED", null)
                callDao.updateSyncStatus(call.clientCallId, "SYNCED", null)

                // If backend returned canonical lead_id and this call was an auto-created provisional lead:
                val serverLeadId = response.leadId
                val oldLeadId = call.leadId
                if (!serverLeadId.isNullOrBlank() && oldLeadId != serverLeadId) {
                    try {
                        callDao.associateCallToLead(call.id, serverLeadId, "SYNCED")
                        callDao.associateCallToLead(call.clientCallId, serverLeadId, "SYNCED")
                        uploadTaskDao?.updateLeadIdForCall(call.id, serverLeadId)
                        uploadTaskDao?.updateLeadIdForCall(call.clientCallId, serverLeadId)
                        if (leadDao != null) {
                            val provisional = if (!oldLeadId.isNullOrBlank() && oldLeadId != "unlinked") leadDao.getById(oldLeadId) else null
                            if (provisional != null && !oldLeadId.isNullOrBlank()) {
                                val canonicalLead = provisional.copy(
                                    id = serverLeadId,
                                    tenantId = provisional.tenantId ?: activeTenantId,
                                    workspaceId = provisional.workspaceId ?: call.workspaceId ?: tokenProvider?.getActiveWorkspaceId()?.trim()?.ifEmpty { null }
                                )
                                leadDao.insert(canonicalLead)
                                leadDao.deleteById(oldLeadId)
                            } else {
                                val newLead = LeadEntity(
                                    id = serverLeadId,
                                    name = "Unknown Caller - ${call.normalizedNumber}",
                                    rawNumber = call.rawNumber,
                                    normalizedNumber = call.normalizedNumber,
                                    status = "New",
                                    isDoNotRecord = false,
                                    notes = call.notes ?: "Auto-captured from answered incoming call",
                                    createdAtEpochMs = call.startTimeEpochMs,
                                    updatedAtEpochMs = System.currentTimeMillis(),
                                    workspaceId = call.workspaceId ?: tokenProvider?.getActiveWorkspaceId()?.trim()?.ifEmpty { null },
                                    tenantId = call.tenantId ?: activeTenantId
                                )
                                leadDao.insert(newLead)
                            }
                        }
                        Log.i(TAG, "Upgraded call ${call.id} to canonical server lead $serverLeadId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating local call/task with canonical server lead_id: ${e.message}")
                    }
                }

                // If this call has a recording upload task belonging to current tenant, trigger upload now!
                if (uploadTaskDao != null && context != null) {
                    try {
                        val task = uploadTaskDao.getByCallId(call.clientCallId)
                            ?: uploadTaskDao.getByCallId(call.id)
                        val taskTenantMatches = activeTenantId == null || task?.tenantId == activeTenantId
                        if (task != null &&
                            taskTenantMatches &&
                            task.status != UploadTaskEntity.STATE_UPLOADED &&
                            task.status != UploadTaskEntity.STATUS_COMPLETED &&
                            task.status != UploadTaskEntity.STATE_TENANT_MISMATCH
                        ) {
                            if (!serverLeadId.isNullOrBlank()) {
                                uploadTaskDao.updateLeadIdForCall(call.id, serverLeadId)
                                uploadTaskDao.updateLeadIdForCall(call.clientCallId, serverLeadId)
                            }
                            RecordingUploadWorker.enqueueUniqueUpload(context, task.id, call.clientCallId)
                            Log.i(TAG, "Triggered RecordingUploadWorker for call ${call.clientCallId} with task ${task.id}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enqueue RecordingUploadWorker post-sync: ${e.message}")
                    }
                }

                AppResult.Success(response)
            } else {
                val errorMsg = response.error ?: response.message ?: "Failed to report call"
                callDao.updateSyncStatus(call.id, "FAILED", errorMsg)
                AppResult.Failure(AppError.NetworkError(errorMsg))
            }
        } catch (e: HttpException) {
            val code = e.code()
            val errorMsg = "Server error $code: ${e.message()}"
            callDao.updateSyncStatus(call.id, "FAILED", errorMsg)
            AppResult.Failure(AppError.NetworkError(errorMsg, httpCode = code, cause = e))
        } catch (e: IOException) {
            val errorMsg = "Network unavailable: offline call preserved"
            callDao.updateSyncStatus(call.id, "PENDING", errorMsg)
            AppResult.Failure(AppError.NetworkError(errorMsg, cause = e))
        } catch (e: Exception) {
            val errorMsg = "Unexpected error reporting call: ${e.message}"
            callDao.updateSyncStatus(call.id, "FAILED", errorMsg)
            AppResult.Failure(AppError.UnknownError(errorMsg, cause = e))
        }
    }

    override suspend fun syncPendingCallsToBackend(): AppResult<Int> = withContext(ioDispatcher) {
        try {
            val activeTenantId = tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }
                ?: return@withContext AppResult.Success(0)

            callDao.recoverUnambiguousLegacyCallsForTenant(activeTenantId)
            callDao.quarantineMismatchedCalls(
                activeTenantId = activeTenantId,
                reason = "Quarantined legacy or cross-tenant call before pending sync (activeTenant='$activeTenantId')"
            )

            val pendingCalls = callDao.getPendingSyncCallsForTenant(activeTenantId).toMutableList()
            val recentCalls = callDao.getRecentCalls(100)
            for (c in recentCalls) {
                if (c.tenantId == activeTenantId &&
                    c.syncStatus != "SYNCED" &&
                    c.syncStatus != CallEntity.SYNC_STATUS_QUARANTINED &&
                    pendingCalls.none { it.id == c.id || it.clientCallId == c.clientCallId }
                ) {
                    pendingCalls.add(c)
                }
            }

            if (pendingCalls.isEmpty()) return@withContext AppResult.Success(0)

            var synced = 0
            for (call in pendingCalls) {
                try {
                    if (call.tenantId != activeTenantId) {
                        callDao.updateSyncStatus(
                            call.id,
                            CallEntity.SYNC_STATUS_QUARANTINED,
                            "Quarantined during pending sync: callTenant='${call.tenantId}', activeTenant='$activeTenantId'"
                        )
                        continue
                    }
                    val result = reportCallToBackend(call.clientCallId)
                    if (result is AppResult.Success) {
                        synced++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed syncing pending call ${call.id}: ${e.message}")
                }
            }
            AppResult.Success(synced)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed syncing pending calls: ${e.message}", e))
        }
    }

    override suspend fun syncCallsFromBackend(): AppResult<Int> = withContext(ioDispatcher) {
        try {
            val activeTenantId = tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }
            val activeWorkspaceId = tokenProvider?.getActiveWorkspaceId()?.trim()?.ifEmpty { null }
            val response = callApiService.getCalls()
            if (!response.success) {
                return@withContext AppResult.Failure(AppError.NetworkError(response.error ?: "Failed to fetch calls from server"))
            }

            var insertedCount = 0
            for (dto in response.data) {
                val callId = dto.clientCallId?.ifBlank { null } ?: dto.id?.ifBlank { null } ?: continue
                val existing = callDao.getByClientCallId(callId) ?: callDao.getById(callId)
                val rawPhone = dto.phoneNumber ?: dto.number ?: ""
                val serverOutcome = dto.callOutcome ?: dto.outcome
                val hasRec = dto.hasRecording || (dto.recordingFileId != null)
                val effectiveTenantId = dto.tenantId?.trim()?.ifEmpty { null } ?: activeTenantId
                val effectiveWorkspaceId = dto.workspaceId?.trim()?.ifEmpty { null } ?: activeWorkspaceId

                val canAdoptNullLegacy = existing?.tenantId == null &&
                    effectiveTenantId != null &&
                    existing?.clientCallId?.startsWith("call_${effectiveTenantId}_") == true
                if (existing != null && (existing.tenantId == effectiveTenantId || canAdoptNullLegacy)) {
                    // Update existing canonical call: retain local outcome if server outcome is pending/null
                    val updated = existing.copy(
                        callOutcome = serverOutcome ?: existing.callOutcome,
                        notes = dto.notes ?: existing.notes,
                        hasRecording = hasRec || existing.hasRecording,
                        syncStatus = "SYNCED",
                        tenantId = existing.tenantId ?: effectiveTenantId,
                        workspaceId = existing.workspaceId ?: effectiveWorkspaceId
                    )
                    callDao.update(updated)
                } else if (existing == null) {
                    // Fallback matching: signature search (phone + start time within 5000ms) scoped to matching/unset tenant
                    val startTime = dto.startTimeMs ?: System.currentTimeMillis()
                    val candidates = callDao.findCandidatesBySignature(
                        normalizedNumber = rawPhone,
                        minStartEpochMs = startTime - 5000L,
                        maxStartEpochMs = startTime + 5000L
                    ).filter { effectiveTenantId == null || it.tenantId == effectiveTenantId }

                    if (candidates.size == 1) {
                        // Unambiguous candidate match: merge server attributes into canonical existing entity
                        val candidate = candidates.first()
                        val updated = candidate.copy(
                            clientCallId = callId,
                            callOutcome = serverOutcome ?: candidate.callOutcome,
                            notes = dto.notes ?: candidate.notes,
                            hasRecording = hasRec || candidate.hasRecording,
                            syncStatus = "SYNCED",
                            tenantId = candidate.tenantId ?: effectiveTenantId,
                            workspaceId = candidate.workspaceId ?: effectiveWorkspaceId
                        )
                        callDao.update(updated)
                    } else if (candidates.isEmpty()) {
                        // Genuinely new call record
                        val directionInt = when (dto.callDirection ?: dto.direction) {
                            "INCOMING", "inbound" -> CallLog.Calls.INCOMING_TYPE
                            "OUTGOING", "outbound" -> CallLog.Calls.OUTGOING_TYPE
                            "MISSED" -> CallLog.Calls.MISSED_TYPE
                            "REJECTED" -> CallLog.Calls.REJECTED_TYPE
                            else -> CallLog.Calls.OUTGOING_TYPE
                        }
                        val entity = CallEntity(
                            id = callId,
                            clientCallId = callId,
                            normalizedNumber = rawPhone,
                            rawNumber = rawPhone,
                            callType = directionInt,
                            startTimeEpochMs = startTime,
                            durationSeconds = dto.durationSeconds ?: dto.duration ?: 0L,
                            isLeadCall = !dto.leadId.isNullOrBlank() && !dto.isUnlinked,
                            leadId = dto.leadId,
                            simId = dto.simId,
                            callOutcome = serverOutcome,
                            notes = dto.notes,
                            hasRecording = hasRec,
                            recordingOrigin = if (dto.source == "whatsapp_voip") "whatsapp_voip" else "sim_call",
                            syncStatus = "SYNCED",
                            createdAtEpochMs = startTime,
                            workspaceId = effectiveWorkspaceId,
                            tenantId = effectiveTenantId
                        )
                        callDao.insert(entity)
                        insertedCount++
                    } else {
                        android.util.Log.w("CallRepository", "Ambiguous call candidates found (${candidates.size}) for phone=$rawPhone, start=$startTime. Skipping merge.")
                    }
                }
            }
            AppResult.Success(insertedCount)
        } catch (e: Exception) {
            AppResult.Failure(AppError.NetworkError("Call sync failed: ${e.message}", cause = e))
        }
    }

    override suspend fun getUnassignedCallsForNumber(normalizedNumber: String, rawNumber: String): AppResult<List<CallEntity>> = withContext(ioDispatcher) {
        try {
            val calls = callDao.getUnassignedCallsForNumber(normalizedNumber, rawNumber)
            AppResult.Success(calls)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch unassigned calls: ${e.message}", e))
        }
    }

    override suspend fun associateCallToLead(callId: String, leadId: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.associateCallToLead(callId, leadId, "PENDING")
            uploadTaskDao?.updateLeadIdForCall(callId, leadId)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to associate call to lead: ${e.message}", e))
        }
    }

    override suspend fun reconcileCallsForLead(lead: LeadEntity): AppResult<Int> = withContext(ioDispatcher) {
        try {
            val normPhone = lead.normalizedNumber.trim()
            val rawPhone = lead.rawNumber.trim()
            if (normPhone.isBlank() && rawPhone.isBlank()) {
                return@withContext AppResult.Success(0)
            }

            // 1. Find all unassigned calls in Room matching this lead's numbers
            val unassignedCalls = callDao.getUnassignedCallsForNumber(normPhone, rawPhone)
            if (unassignedCalls.isEmpty()) {
                return@withContext AppResult.Success(0)
            }

            var reconciledCount = 0
            val discoveryCoordinator = try {
                recordingDiscoveryCoordinatorProvider?.get() ?: directRecordingDiscoveryCoordinator
            } catch (e: Exception) {
                null
            }

            for (call in unassignedCalls) {
                val activeTenantId = tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }
                val effectiveLeadTenant = lead.tenantId?.trim()?.ifEmpty { null } ?: activeTenantId
                if (effectiveLeadTenant != null && call.tenantId != null && call.tenantId != effectiveLeadTenant) {
                    continue
                }
                if (call.syncStatus == CallEntity.SYNC_STATUS_QUARANTINED) {
                    continue
                }
                // Safety check: Never silently reassign a call already linked to a different lead
                if (!call.leadId.isNullOrBlank() && call.leadId != lead.id) {
                    continue
                }

                // 2. Associate canonical CallEntity to the new lead in Room
                callDao.associateCallToLead(call.clientCallId, lead.id, "PENDING")
                reconciledCount++

                // 3. Update existing UploadTaskEntity if one exists for this call
                uploadTaskDao?.updateLeadIdForCall(call.clientCallId, lead.id)
                if (call.id != call.clientCallId) {
                    uploadTaskDao?.updateLeadIdForCall(call.id, lead.id)
                }
                val existingTask = uploadTaskDao?.getByCallId(call.clientCallId)
                    ?: uploadTaskDao?.getByCallId(call.id)

                if (existingTask != null) {
                    // If upload task exists and is not yet completed/uploaded, queue upload
                    val isTerminal = existingTask.status in listOf(
                        UploadTaskEntity.STATE_UPLOADED,
                        UploadTaskEntity.STATUS_COMPLETED
                    )
                    if (!isTerminal && context != null) {
                        try {
                            RecordingUploadWorker.enqueueUniqueUpload(context, existingTask.id, call.clientCallId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to enqueue upload worker for reconciled call ${call.clientCallId}: ${e.message}")
                        }
                    }
                } else if (!call.hasRecording && discoveryCoordinator != null) {
                    // 4. If no recording was found yet, trigger targeted discovery on authorized sources
                    try {
                        val callEvent = com.leads365.apk.core.CallEvent(
                            id = call.clientCallId,
                            rawNumber = call.rawNumber,
                            normalizedNumber = call.normalizedNumber,
                            startTimeEpochMs = call.startTimeEpochMs,
                            endTimeEpochMs = call.startTimeEpochMs + (call.durationSeconds * 1000L),
                            durationSeconds = call.durationSeconds,
                            direction = when (call.callType) {
                                CallLog.Calls.INCOMING_TYPE -> com.leads365.apk.core.CallDirection.INCOMING
                                CallLog.Calls.OUTGOING_TYPE -> com.leads365.apk.core.CallDirection.OUTGOING
                                CallLog.Calls.MISSED_TYPE -> com.leads365.apk.core.CallDirection.MISSED
                                else -> com.leads365.apk.core.CallDirection.INCOMING
                            }
                        )
                        val discoveryResult = discoveryCoordinator.discoverAndMatchRecording(
                            callEvent = callEvent,
                            lead = lead,
                            callId = call.clientCallId
                        )
                        if (discoveryResult is DiscoveryResult.Matched) {
                            callDao.updateRecording(
                                callId = call.clientCallId,
                                hasRecording = true,
                                recordingUri = discoveryResult.candidate.uriString,
                                recordingFileName = discoveryResult.candidate.displayName,
                                recordingFileSizeBytes = discoveryResult.candidate.sizeBytes
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Targeted discovery failed during call reconciliation: ${e.message}")
                    }
                }

                // 5. Synchronize associated call to backend
                try {
                    reportCallToBackend(call.clientCallId)
                } catch (e: Exception) {
                    Log.w(TAG, "Backend report deferred for reconciled call ${call.clientCallId}: ${e.message}")
                }
            }

            AppResult.Success(reconciledCount)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Call reconciliation failed: ${e.message}", e))
        }
    }

    override suspend fun reconcileUnmatchedRecordings(candidates: List<RecordingCandidate>?): AppResult<Int> = withContext(ioDispatcher) {
        try {
            val discoveryCoordinator = directRecordingDiscoveryCoordinator 
                ?: recordingDiscoveryCoordinatorProvider?.get()

            // 1. Find calls in Room that do not have an audio recording linked
            val unmatchedCalls = callDao.getCallsWithoutRecordings(100)
            if (unmatchedCalls.isEmpty()) {
                return@withContext AppResult.Success(0)
            }

            // 2. Resolve candidates from argument or coordinator scan
            val candidateList = candidates ?: run {
                val dummyEvent = com.leads365.apk.core.CallEvent(
                    id = "unmatched_scan",
                    rawNumber = "",
                    normalizedNumber = "",
                    startTimeEpochMs = System.currentTimeMillis() - 86400000L,
                    endTimeEpochMs = System.currentTimeMillis(),
                    durationSeconds = 0,
                    direction = com.leads365.apk.core.CallDirection.INCOMING
                )
                discoveryCoordinator?.scanSourcesForCandidates(dummyEvent, "") ?: emptyList()
            }

            if (candidateList.isEmpty()) {
                return@withContext AppResult.Success(0)
            }

            // 3. Collect URIs and names already linked to other calls in Room
            val allCalls = callDao.getRecentCalls(200)
            val assignedUris = allCalls.mapNotNull { it.recordingUri }.toMutableSet()
            val assignedNames = allCalls.mapNotNull { it.recordingFileName }.toMutableSet()

            var reconciledCount = 0
            val matcher = TargetedRecordingMatcher()

            val activeTenantId = tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }
            for (call in unmatchedCalls) {
                if (call.syncStatus == CallEntity.SYNC_STATUS_QUARANTINED) continue
                if (activeTenantId != null && (call.tenantId.isNullOrBlank() || call.tenantId != activeTenantId)) continue
                val availableCandidates = candidateList.filter { 
                    it.uriString !in assignedUris && it.displayName !in assignedNames 
                }
                if (availableCandidates.isEmpty()) break

                val callEvent = com.leads365.apk.core.CallEvent(
                    id = call.clientCallId,
                    rawNumber = call.rawNumber,
                    normalizedNumber = call.normalizedNumber,
                    startTimeEpochMs = call.startTimeEpochMs,
                    endTimeEpochMs = call.startTimeEpochMs + (call.durationSeconds * 1000L),
                    durationSeconds = call.durationSeconds,
                    direction = when (call.callType) {
                        CallLog.Calls.INCOMING_TYPE -> com.leads365.apk.core.CallDirection.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> com.leads365.apk.core.CallDirection.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> com.leads365.apk.core.CallDirection.MISSED
                        else -> com.leads365.apk.core.CallDirection.INCOMING
                    }
                )

                val matchResult = matcher.match(availableCandidates, callEvent)

                if (matchResult.status == MatchStatus.FILE_FOUND && matchResult.matchedCandidate != null) {
                    val matched = matchResult.matchedCandidate!!
                    Log.i(TAG, "Reconciled recording '${matched.displayName}' to call ${call.clientCallId}")

                    // Update call in Room
                    callDao.updateRecording(
                        callId = call.id,
                        hasRecording = true,
                        recordingUri = matched.uriString,
                        recordingFileName = matched.displayName,
                        recordingFileSizeBytes = matched.sizeBytes
                    )
                    callDao.updateRecording(
                        callId = call.clientCallId,
                        hasRecording = true,
                        recordingUri = matched.uriString,
                        recordingFileName = matched.displayName,
                        recordingFileSizeBytes = matched.sizeBytes
                    )

                    assignedUris.add(matched.uriString)
                    assignedNames.add(matched.displayName)

                    // Ensure persistent UploadTaskEntity in Room
                    if (uploadTaskDao != null) {
                        val existingTask = uploadTaskDao.getByCallId(call.clientCallId)
                            ?: uploadTaskDao.getByCallId(call.id)
                            ?: uploadTaskDao.getByLocalFileUri(matched.uriString)

                        val finalTask = if (existingTask == null) {
                            val newTask = UploadTaskEntity(
                                id = UUID.randomUUID().toString(),
                                callId = call.clientCallId,
                                leadId = call.leadId ?: "unlinked",
                                localFileUri = matched.uriString,
                                fileName = matched.displayName,
                                fileSizeBytes = matched.sizeBytes,
                                status = UploadTaskEntity.STATUS_PENDING,
                                tenantId = call.tenantId ?: activeTenantId,
                                workspaceId = call.workspaceId ?: tokenProvider?.getActiveWorkspaceId()?.trim()?.ifEmpty { null }
                            )
                            uploadTaskDao.insert(newTask)
                            newTask
                        } else {
                            val updatedTask = existingTask.copy(
                                callId = call.clientCallId,
                                leadId = call.leadId ?: existingTask.leadId ?: "unlinked",
                                localFileUri = matched.uriString,
                                fileName = matched.displayName,
                                fileSizeBytes = matched.sizeBytes,
                                tenantId = existingTask.tenantId ?: call.tenantId ?: activeTenantId,
                                workspaceId = existingTask.workspaceId ?: call.workspaceId ?: tokenProvider?.getActiveWorkspaceId()?.trim()?.ifEmpty { null }
                            )
                            uploadTaskDao.update(updatedTask)
                            updatedTask
                        }

                        // If call belongs to an assigned Lead or is an incoming call, automatically trigger upload worker
                        val isEligibleForUpload = (call.isLeadCall && !call.leadId.isNullOrBlank() && call.leadId != "unlinked") ||
                            call.callType == CallLog.Calls.INCOMING_TYPE ||
                            call.notes?.contains("incoming", ignoreCase = true) == true
                        if (isEligibleForUpload && context != null) {
                            try {
                                RecordingUploadWorker.enqueueUniqueUpload(
                                    context = context,
                                    taskId = finalTask.id,
                                    callId = call.clientCallId
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to enqueue RecordingUploadWorker for reconciled task: ${e.message}")
                            }
                        }
                    }

                    reconciledCount++
                }
            }

            AppResult.Success(reconciledCount)
        } catch (e: Exception) {
            Log.e(TAG, "Error in reconcileUnmatchedRecordings: ${e.message}", e)
            AppResult.Failure(AppError.StorageError("Failed to reconcile unmatched recordings: ${e.message}", e))
        }
    }

    override suspend fun deleteCallById(id: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.deleteById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to delete call $id: ${e.message}", e))
        }
    }

    override suspend fun getClaimedRecordingUris(excludeCallId: String): Set<String> = withContext(ioDispatcher) {
        try {
            callDao.getClaimedRecordingUris(excludeCallId).filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get claimed recording URIs: ${e.message}")
            emptySet()
        }
    }

    override suspend fun clearAll(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.clearAll()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to clear calls: ${e.message}", e))
        }
    }

    companion object {
        private const val TAG = "CallRepository"
    }
}
