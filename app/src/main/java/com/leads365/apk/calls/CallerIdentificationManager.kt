package com.leads365.apk.calls

import android.content.Context
import android.util.Log
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.notifications.LeadsNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallerIdentificationManager(
    private val leadRepository: LeadRepository,
    private val pendingCallManager: PendingCallManager,
    private val normalizer: PhoneNumberNormalizer,
    private val callerOverlayManager: CallerOverlayManager,
    private val scope: CoroutineScope
) {

    @Inject
    constructor(
        leadRepository: LeadRepository,
        pendingCallManager: PendingCallManager,
        normalizer: PhoneNumberNormalizer,
        callerOverlayManager: CallerOverlayManager
    ) : this(
        leadRepository,
        pendingCallManager,
        normalizer,
        callerOverlayManager,
        CoroutineScope(Dispatchers.IO)
    )

    // Tracks currently active caller ID display
    private var activeLead: LeadEntity? = null

    /**
     * Called when a call starts (RINGING or OFFHOOK).
     * Resolves incoming/outgoing number against assigned leads,
     * displaying an overlay card if permitted, or falling back to a heads-up notification.
     */
    fun onCallStarted(context: Context, rawNumber: String?) {
        scope.launch {
            try {
                // 1. Resolve candidate number (from telephony broadcast or pending call initiated in app)
                val candidateNumber = if (!rawNumber.isNullOrBlank()) {
                    rawNumber
                } else {
                    pendingCallManager.getPendingCall()?.rawNumber
                }

                if (candidateNumber.isNullOrBlank()) {
                    Log.d(TAG, "Unknown or private caller number. Skipping caller identification.")
                    return@launch
                }

                // 2. Normalize candidate number
                val normalizedNumber = normalizer.normalizeToE164(candidateNumber)
                    ?: candidateNumber.trim()

                Log.d(TAG, "Resolving caller ID for candidate: ${PrivacyRedactor.redactPhoneNumber(normalizedNumber)}")

                // 3. Resolve against assigned leads in Room DB
                val resolvedLead: LeadEntity? = when (val result = leadRepository.getLeadByNormalizedNumber(normalizedNumber)) {
                    is AppResult.Success -> result.data
                    else -> null
                } ?: run {
                    // Check if pending call in app was initiated for this lead
                    val pending = pendingCallManager.getPendingCall()
                    if (pending != null && (pending.normalizedNumber == normalizedNumber || pending.rawNumber == candidateNumber)) {
                        when (val res = leadRepository.getLeadById(pending.leadId)) {
                            is AppResult.Success -> res.data
                            else -> null
                        }
                    } else {
                        null
                    }
                }

                if (resolvedLead == null) {
                    Log.d(TAG, "Number ${PrivacyRedactor.redactPhoneNumber(normalizedNumber)} is not an assigned lead. Ignoring.")
                    return@launch
                }

                activeLead = resolvedLead

                val leadNotes = if (resolvedLead.notes.isNotBlank()) resolvedLead.notes else null

                // 4. Determine display method: Overlay or Heads-Up Notification fallback
                val hasOverlayPerm = callerOverlayManager.canDrawOverlays(context)
                if (hasOverlayPerm) {
                    val shown = callerOverlayManager.showCallerOverlay(
                        context = context,
                        leadId = resolvedLead.id,
                        leadName = resolvedLead.name,
                        leadStatus = resolvedLead.status,
                        lastNote = leadNotes,
                        normalizedNumber = PrivacyRedactor.redactPhoneNumber(resolvedLead.normalizedNumber)
                    )
                    if (!shown) {
                        // Fallback to heads-up notification if overlay display failed
                        showHeadsUpFallback(context, resolvedLead)
                    }
                } else {
                    // Overlay permission denied or unavailable -> Use designed heads-up fallback
                    showHeadsUpFallback(context, resolvedLead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during caller identification: ${e.message}", e)
            }
        }
    }

    /**
     * Called when a call terminates (CALL_STATE_IDLE).
     * Cleans up any active overlay or heads-up notification.
     */
    fun onCallEnded(context: Context) {
        try {
            activeLead = null
            callerOverlayManager.dismissOverlay()
            LeadsNotificationManager.dismissCallerIdNotification(context)
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up caller ID: ${e.message}")
        }
    }

    fun getActiveCallerLead(): LeadEntity? = activeLead

    private fun showHeadsUpFallback(context: Context, lead: LeadEntity) {
        LeadsNotificationManager.showCallerIdHeadsUpNotification(
            context = context,
            leadId = lead.id,
            leadName = lead.name,
            leadStatus = lead.status,
            lastNote = lead.notes.ifBlank { null },
            normalizedNumber = PrivacyRedactor.redactPhoneNumber(lead.normalizedNumber)
        )
    }

    companion object {
        const val TAG = "CallerIdManager"
    }
}
