package com.leads365.apk.calls

import android.content.Context
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.database.entity.LeadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PendingCallContext(
    val leadId: String,
    val leadName: String,
    val normalizedNumber: String,
    val rawNumber: String,
    val initiatedEpochMs: Long = System.currentTimeMillis()
)

@Singleton
class PendingCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val normalizer: PhoneNumberNormalizer
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun setPendingCall(lead: LeadEntity) {
        prefs.edit()
            .putString(KEY_LEAD_ID, lead.id)
            .putString(KEY_LEAD_NAME, lead.name)
            .putString(KEY_NORMALIZED_NUMBER, lead.normalizedNumber)
            .putString(KEY_RAW_NUMBER, lead.rawNumber)
            .putLong(KEY_INITIATED_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun getPendingCall(): PendingCallContext? {
        val leadId = prefs.getString(KEY_LEAD_ID, null) ?: return null
        val leadName = prefs.getString(KEY_LEAD_NAME, "") ?: ""
        val normalizedNumber = prefs.getString(KEY_NORMALIZED_NUMBER, "") ?: ""
        val rawNumber = prefs.getString(KEY_RAW_NUMBER, "") ?: ""
        val initiatedEpochMs = prefs.getLong(KEY_INITIATED_EPOCH_MS, 0L)

        // Expire pending calls older than 15 minutes
        val now = System.currentTimeMillis()
        if (now - initiatedEpochMs > MAX_PENDING_WINDOW_MS) {
            clearPendingCall()
            return null
        }

        return PendingCallContext(
            leadId = leadId,
            leadName = leadName,
            normalizedNumber = normalizedNumber,
            rawNumber = rawNumber,
            initiatedEpochMs = initiatedEpochMs
        )
    }

    @Synchronized
    fun clearPendingCall() {
        prefs.edit().clear().apply()
    }

    /**
     * Resolves and consumes the pending call context if the completed call's
     * normalized number matches the pending call and is within the 15-minute window.
     */
    @Synchronized
    fun consumePendingCallForNumber(
        completedNormalizedNumber: String,
        windowMs: Long = MAX_PENDING_WINDOW_MS
    ): PendingCallContext? {
        val pending = getPendingCall() ?: return null
        val now = System.currentTimeMillis()

        if (now - pending.initiatedEpochMs > windowMs) {
            clearPendingCall()
            return null
        }

        val completedLast10 = normalizer.extractLast10Digits(completedNormalizedNumber)
        val pendingLast10 = normalizer.extractLast10Digits(pending.normalizedNumber)

        val isMatch = pending.normalizedNumber == completedNormalizedNumber ||
                (completedLast10.isNotEmpty() && completedLast10 == pendingLast10)

        return if (isMatch) {
            clearPendingCall()
            pending
        } else {
            null
        }
    }

    fun findTestLead(number: String): LeadEntity? {
        try {
            val p = context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE)
            val json = p.getString("key_saved_test_leads_json", null) ?: return null
            val array = org.json.JSONArray(json)
            val targetNorm = normalizer.normalizeToE164(number) ?: number
            val targetLast10 = normalizer.extractLast10Digits(targetNorm)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val raw = obj.optString("rawNumber")
                val norm = obj.optString("normalizedNumber")
                val last10 = normalizer.extractLast10Digits(norm)
                val isDnr = obj.optBoolean("isDoNotRecord", false)
                val id = obj.optString("id", "lead_${System.currentTimeMillis()}")
                val name = obj.optString("name", "Test Lead")

                if (norm == targetNorm || (targetLast10.isNotEmpty() && targetLast10 == last10)) {
                    return LeadEntity(
                        id = id,
                        name = name,
                        rawNumber = raw,
                        normalizedNumber = norm,
                        isDoNotRecord = isDnr,
                        status = "ASSIGNED"
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return null
    }

    companion object {
        private const val PREFS_NAME = "leads365_pending_call_prefs"
        private const val KEY_LEAD_ID = "key_lead_id"
        private const val KEY_LEAD_NAME = "key_lead_name"
        private const val KEY_NORMALIZED_NUMBER = "key_normalized_number"
        private const val KEY_RAW_NUMBER = "key_raw_number"
        private const val KEY_INITIATED_EPOCH_MS = "key_initiated_epoch_ms"
        private const val MAX_PENDING_WINDOW_MS = 900_000L // 15 minutes
    }
}
