package com.leads365.apk.recordings

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.auth.TokenProvider
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 — WhatsApp VoIP recording enqueue helper.
 *
 * Validates the recorded audio file, resolves the caller to a lead, enforces
 * Do-Not-Record and policy-pause gates, persists a [CallEntity] + [UploadTaskEntity]
 * via the existing Room/WorkManager pipeline, and schedules the upload worker.
 *
 * This helper is [Singleton] so it can be injected into the foreground
 * [VoipRecordingService] via Hilt.
 */
@Singleton
class VoipCallEnqueueHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callRepository: CallRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val leadDao: LeadDao,
    private val policyRepository: PolicyRepository,
    private val tokenProvider: TokenProvider,
    private val workManagerScheduler: WorkManagerScheduler,
    private val phoneNumberNormalizer: PhoneNumberNormalizer
) {

    /**
     * Validates and enqueues a WhatsApp VoIP recording for upload.
     *
     * @param callerIdentifier Phone number or display name from the accessibility event.
     * @param sourceApp        Package name of the calling app (com.whatsapp or com.whatsapp.w4b).
     * @param file             The recorded audio file (must be inside filesDir/voip_recordings).
     * @param startTimeMs      Epoch-ms when the VoIP call started.
     * @param durationSec      Call duration in seconds (rounded up to at least 1).
     * @return true if the recording was successfully enqueued; false if it was rejected/deleted.
     */
    suspend fun enqueueVoipRecording(
        callerIdentifier: String,
        sourceApp: String,
        file: File,
        startTimeMs: Long,
        durationSec: Int
    ): Boolean = withContext(Dispatchers.IO) {

        // ── 1. File sanity check ─────────────────────────────────────────────
        if (!file.exists() || file.length() <= MIN_FILE_BYTES) {
            runCatching { file.delete() }
            return@withContext false
        }

        // ── 2. Policy-pause gate ─────────────────────────────────────────────
        if (policyRepository.isRecordingSyncPaused()) {
            runCatching { file.delete() }
            return@withContext false
        }

        // ── 3. Caller & lead resolution ──────────────────────────────────────
        val tenantId  = tokenProvider.getAgencyAccountId()?.trim()?.ifEmpty { null }
        val installId = tokenProvider.getDeviceIdOrInstallId()?.trim()?.ifEmpty { null }

        val trimmedCaller = callerIdentifier.trim().ifBlank { "WhatsApp Contact" }
        var resolvedPhone: String? = null
        val tenantLeads = leadDao.getAllLeads().filter { lead ->
            tenantId == null || lead.tenantId == null || lead.tenantId == tenantId
        }
        var matchedLead: LeadEntity? = null

        if (phoneNumberNormalizer.isValid(trimmedCaller)) {
            resolvedPhone = phoneNumberNormalizer.normalizeToE164(trimmedCaller) ?: trimmedCaller
            matchedLead = leadDao.getByNormalizedNumber(resolvedPhone)
                ?.takeIf { tenantId == null || it.tenantId == null || it.tenantId == tenantId }
            if (matchedLead == null) {
                val last10 = phoneNumberNormalizer.extractLast10Digits(resolvedPhone)
                matchedLead = tenantLeads.firstOrNull { lead ->
                    phoneNumberNormalizer.extractLast10Digits(lead.normalizedNumber) == last10
                }
            }
        } else {
            // Name-based lookup first
            matchedLead = tenantLeads
                .firstOrNull { it.name.equals(trimmedCaller, ignoreCase = true) }
            if (matchedLead != null && resolvedPhone == null && matchedLead.normalizedNumber.isNotBlank()) {
                resolvedPhone = matchedLead.normalizedNumber
            }

            // Contacts fallback
            if (matchedLead == null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                    arrayOf(trimmedCaller),
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val rawNumber = it.getString(0)
                        val norm = phoneNumberNormalizer.normalizeToE164(rawNumber)
                        if (norm != null) {
                            resolvedPhone = norm
                            matchedLead = leadDao.getByNormalizedNumber(norm)
                                ?.takeIf { lead -> tenantId == null || lead.tenantId == null || lead.tenantId == tenantId }
                        }
                    }
                }
            }
        }

        // ── 4. Do-Not-Record gate ────────────────────────────────────────────
        if (matchedLead?.isDoNotRecord == true) {
            runCatching { file.delete() }
            return@withContext false
        }

        // ── 5. Build canonical IDs ───────────────────────────────────────────
        val clientCallId = if (!tenantId.isNullOrBlank() && !installId.isNullOrBlank()) {
            "wa_voip_${tenantId}_${installId}_$startTimeMs"
        } else {
            "local_pending_call_wa_$startTimeMs"
        }

        val syncStatus  = if (clientCallId.startsWith("local_pending_call_")) "DEFERRED_IDENTITY" else "PENDING"
        val workspaceId = matchedLead?.workspaceId?.trim()?.ifEmpty { null }
            ?: tokenProvider.getActiveWorkspaceId()?.trim()?.ifEmpty { null }

        // ── 6. Build human-readable note ─────────────────────────────────────
        val appLabel    = if (sourceApp == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
        val fileUriStr  = Uri.fromFile(file).toString()

        // ── 7. Persist CallEntity ────────────────────────────────────────────
        val callEntity = CallEntity(
            id                    = clientCallId,
            clientCallId          = clientCallId,
            normalizedNumber      = resolvedPhone ?: trimmedCaller,
            rawNumber             = trimmedCaller,
            callType              = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs      = startTimeMs,
            durationSeconds       = durationSec.coerceAtLeast(1).toLong(),
            isLeadCall            = matchedLead != null,
            leadId                = matchedLead?.id,
            syncStatus            = syncStatus,
            hasRecording          = true,
            recordingUri          = fileUriStr,
            recordingFileName     = file.name,
            recordingFileSizeBytes= file.length(),
            recordingOrigin       = "whatsapp_voip",
            localFilePath         = file.absolutePath,
            workspaceId           = workspaceId,
            tenantId              = tenantId,
            notes                 = "$appLabel Call • $trimmedCaller"
        )
        callRepository.recordCall(callEntity)

        // ── 8. Persist UploadTaskEntity ──────────────────────────────────────
        val task = UploadTaskEntity(
            id            = UUID.randomUUID().toString(),
            callId        = clientCallId,
            leadId        = matchedLead?.id ?: "unlinked",
            localFileUri  = fileUriStr,
            fileName      = file.name,
            fileSizeBytes = file.length(),
            status        = UploadTaskEntity.STATE_FILE_FOUND,
            tenantId      = tenantId,
            workspaceId   = workspaceId
        )
        uploadQueueRepository.enqueueTask(task)

        // ── 9. Schedule upload worker ────────────────────────────────────────
        workManagerScheduler.scheduleUploadTask(task.id, fileUriStr, clientCallId)

        true
    }

    private companion object {
        /** Reject files ≤ 2 KB — almost certainly silence / corrupt. */
        const val MIN_FILE_BYTES = 2048L
    }
}
