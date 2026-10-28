package com.leads365.apk.telephony

import android.content.Context
import android.util.Log
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudTelephonyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: CloudTelephonyApiService,
    private val callDao: CallDao
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _callState = MutableStateFlow<CloudCallUiState>(CloudCallUiState.Idle)
    val callState: StateFlow<CloudCallUiState> = _callState.asStateFlow()

    // Idempotency tracking to prevent duplicate in-flight requests from multiple taps
    private val activeCalls = ConcurrentHashMap<String, Boolean>()

    fun getCallMode(): TelephonyCallMode {
        val stored = prefs.getString(KEY_CALL_MODE, TelephonyCallMode.NATIVE_DIALER.name)
        return try {
            TelephonyCallMode.valueOf(stored ?: TelephonyCallMode.NATIVE_DIALER.name)
        } catch (e: Exception) {
            TelephonyCallMode.NATIVE_DIALER
        }
    }

    fun setCallMode(mode: TelephonyCallMode) {
        prefs.edit().putString(KEY_CALL_MODE, mode.name).apply()
    }

    fun getAgentPhoneNumber(): String {
        return prefs.getString(KEY_AGENT_PHONE, "") ?: ""
    }

    fun setAgentPhoneNumber(phone: String) {
        prefs.edit().putString(KEY_AGENT_PHONE, phone.trim()).apply()
    }

    /**
     * Initiates a cloud-bridged call for an authorized lead.
     * Respects Do-Not-Record (DNR) by instructing the provider not to record if flagged.
     */
    suspend fun initiateCloudCall(
        lead: LeadEntity,
        overrideAgentPhone: String? = null
    ): AppResult<CallEntity> = withContext(Dispatchers.IO) {
        val leadId = lead.id
        if (leadId.isBlank()) {
            val err = "Cannot place cloud call: Lead identifier is missing."
            _callState.value = CloudCallUiState.Error(err)
            return@withContext AppResult.Failure(AppError.ValidationError(err))
        }

        // Deduplication: prevent repeated taps for the same lead while in-flight
        if (activeCalls.putIfAbsent(leadId, true) != null) {
            Log.w(TAG, "Cloud call initiation already in progress for lead $leadId; ignoring duplicate request.")
            return@withContext AppResult.Failure(AppError.ValidationError("Call initiation already in progress."))
        }

        try {
            val clientCallId = UUID.randomUUID().toString()
            val agentPhone = overrideAgentPhone?.ifBlank { null } ?: getAgentPhoneNumber()
            val leadPhone = lead.normalizedNumber.ifBlank { lead.rawNumber }

            // DNR enforcement: do not request server recording if lead is flagged DNR
            val shouldRecord = !lead.isDoNotRecord

            _callState.value = CloudCallUiState.Initiating(clientCallId, lead.name)

            val request = InitiateCloudCallRequest(
                clientCallId = clientCallId,
                leadId = leadId,
                leadPhone = leadPhone,
                agentPhone = agentPhone.ifBlank { null },
                recordCall = shouldRecord
            )

            val response = try {
                apiService.initiateCall(request)
            } catch (e: Exception) {
                Log.e(TAG, "Network failure contacting cloud telephony bridge: ${e.message}")
                val err = "Failed to connect to cloud telephony service. Check network connection."
                _callState.value = CloudCallUiState.Error(err)
                return@withContext AppResult.Failure(AppError.NetworkError(err, cause = e))
            }

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val serverCallId = body.callId ?: clientCallId
                val statusMsg = body.message ?: "Cloud call initiated. Your phone will ring shortly."

                _callState.value = CloudCallUiState.RingingAgent(
                    callId = serverCallId,
                    leadName = lead.name,
                    message = statusMsg
                )

                // Persist call entity in Room with source = 'cloud'
                val callEntity = CallEntity(
                    id = serverCallId,
                    clientCallId = clientCallId,
                    normalizedNumber = lead.normalizedNumber,
                    rawNumber = lead.rawNumber,
                    callType = 2, // Outgoing
                    startTimeEpochMs = System.currentTimeMillis(),
                    durationSeconds = body.durationSeconds,
                    isLeadCall = true,
                    leadId = lead.id,
                    simId = "cloud_telephony",
                    syncStatus = "SYNCED",
                    hasRecording = false // Set to true only when recording is server-confirmed ready
                )

                callDao.insert(callEntity)
                return@withContext AppResult.Success(callEntity)
            } else {
                val httpCode = response.code()
                val isUnconfigured = httpCode == 404 || httpCode == 501 || httpCode == 400
                val errMsg = if (isUnconfigured) {
                    "Cloud telephony bridge is not configured or unavailable on the server. Falling back to native dialer."
                } else {
                    response.body()?.error ?: "Cloud telephony service returned HTTP $httpCode"
                }

                Log.w(TAG, "Cloud telephony rejected request: code=$httpCode, msg=$errMsg")
                _callState.value = CloudCallUiState.Error(errMsg, isProviderUnconfigured = isUnconfigured)
                return@withContext AppResult.Failure(AppError.NetworkError(errMsg, httpCode = httpCode))
            }
        } finally {
            activeCalls.remove(leadId)
        }
    }

    fun resetCallState() {
        _callState.value = CloudCallUiState.Idle
    }

    companion object {
        private const val TAG = "CloudTelephonyManager"
        const val PREFS_NAME = "com.leads365.apk.preferences"
        const val KEY_CALL_MODE = "key_telephony_call_mode"
        const val KEY_AGENT_PHONE = "key_telephony_agent_phone"
    }
}
