package com.leads365.apk.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.leads365.apk.core.CallEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class CallMonitoringState {
    object IdleWaiting : CallMonitoringState()
    object Ringing : CallMonitoringState()
    object CallActive : CallMonitoringState()
    data class CallEndedQueryingLog(val attempt: Int) : CallMonitoringState()
    data class CallDetected(val call: CallEvent) : CallMonitoringState()
    data class CallLogUnavailable(val reason: String) : CallMonitoringState()
    data class Error(val message: String) : CallMonitoringState()
}

class CallStateObserver(
    private val context: Context,
    private val callLogReader: CallLogReader,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onCallCompleted: (CallEvent) -> Unit
) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val _monitoringState = MutableStateFlow<CallMonitoringState>(CallMonitoringState.IdleWaiting)
    val monitoringState: StateFlow<CallMonitoringState> = _monitoringState.asStateFlow()

    private val _currentCallState = MutableStateFlow(TelephonyManager.CALL_STATE_IDLE)
    val currentCallState: StateFlow<Int> = _currentCallState.asStateFlow()

    private var previousState = TelephonyManager.CALL_STATE_IDLE
    private var isListening = false
    private var isQueryingCallLog = false
    private var activeQueryJob: Job? = null

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null
    private var dynamicReceiver: BroadcastReceiver? = null

    fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isObserving(): Boolean = isListening

    /**
     * Starts listening for call state transitions.
     * Safe to call multiple times (e.g. in onResume or after permission grant).
     */
    fun startObserving() {
        if (!hasPhoneStatePermission()) {
            _monitoringState.value = CallMonitoringState.Error(
                "Phone State permission is missing. Please grant permission in Step 1."
            )
            return
        }

        if (isListening) return
        if (telephonyManager == null) {
            _monitoringState.value = CallMonitoringState.Error("TelephonyManager is unavailable on this device.")
            return
        }

        try {
            // 1. Modern Android 12+ (API 31+) TelephonyCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleStateChange(state)
                    }
                }
                modernCallback = callback
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
            } else {
                // Legacy Android <= 11 PhoneStateListener
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleStateChange(state)
                    }
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }

            // 2. Dynamic BroadcastReceiver for ACTION_PHONE_STATE as secondary fallback
            registerDynamicReceiver()

            isListening = true
            _monitoringState.value = CallMonitoringState.IdleWaiting
        } catch (e: SecurityException) {
            isListening = false
            _monitoringState.value = CallMonitoringState.Error("SecurityException registering telephony listener: ${e.message}")
        } catch (e: Exception) {
            isListening = false
            _monitoringState.value = CallMonitoringState.Error("Failed to register telephony listener: ${e.message}")
        }
    }

    private fun registerDynamicReceiver() {
        if (dynamicReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        val newState = when (stateStr) {
                            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                            else -> return
                        }
                        handleStateChange(newState)
                    }
                }
            }
            dynamicReceiver = receiver
            context.registerReceiver(
                receiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            )
        } catch (e: Exception) {
            // Dynamic receiver registration failure is non-fatal; TelephonyCallback is primary
        }
    }

    fun stopObserving() {
        if (telephonyManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    modernCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                    modernCallback = null
                } else {
                    @Suppress("DEPRECATION")
                    legacyListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
                    legacyListener = null
                }
            } catch (e: Exception) {
                // Ignored
            }
        }

        if (dynamicReceiver != null) {
            try {
                context.unregisterReceiver(dynamicReceiver)
            } catch (e: Exception) {
                // Ignored
            }
            dynamicReceiver = null
        }

        isListening = false
        _monitoringState.value = CallMonitoringState.IdleWaiting
    }

    /**
     * Handles real-time telephony state changes:
     * - Incoming answered: IDLE -> RINGING -> OFFHOOK -> IDLE
     * - Incoming missed/rejected: IDLE -> RINGING -> IDLE
     * - Outgoing call: IDLE -> OFFHOOK -> IDLE
     */
    fun handleStateChange(newState: Int) {
        val oldState = previousState
        if (oldState == newState) return
        previousState = newState
        _currentCallState.value = newState

        when (newState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                _monitoringState.value = CallMonitoringState.Ringing
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                _monitoringState.value = CallMonitoringState.CallActive
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // If transitioning from an active call (OFFHOOK or RINGING), trigger call log lookup
                if (oldState == TelephonyManager.CALL_STATE_OFFHOOK || oldState == TelephonyManager.CALL_STATE_RINGING) {
                    triggerCallLogLookup()
                } else {
                    _monitoringState.value = CallMonitoringState.IdleWaiting
                }
            }
        }
    }

    /**
     * Polls the CallLog provider to retrieve the completed call record.
     * Retries 4 times with escalating delays to accommodate delayed writes by
     * OEM dialers (Vivo Funtouch OS, Xiaomi MIUI, Samsung Knox).
     */
    fun triggerCallLogLookup() {
        if (isQueryingCallLog) return
        activeQueryJob?.cancel()

        activeQueryJob = scope.launch {
            isQueryingCallLog = true
            var call: CallEvent? = null

            // Check if call log permission is granted
            val accessStatus = callLogReader.checkCallLogAccess()
            if (accessStatus == CallLogAccessStatus.PERMISSION_DENIED ||
                accessStatus == CallLogAccessStatus.RESTRICTED_BY_PLATFORM
            ) {
                _monitoringState.value = CallMonitoringState.CallLogUnavailable(
                    "Call Log access is restricted or denied by Android. Grant Call Log access to detect calls."
                )
                isQueryingCallLog = false
                return@launch
            }

            // Up to 4 polling attempts with delays: 1000ms, 1500ms, 2000ms, 2500ms
            val delays = listOf(1000L, 1500L, 2000L, 2500L)
            for ((index, delayMs) in delays.withIndex()) {
                val attempt = index + 1
                _monitoringState.value = CallMonitoringState.CallEndedQueryingLog(attempt)
                delay(delayMs)

                // Query for calls completed in the last 15 minutes
                call = callLogReader.findMostRecentCall(maxEndRecencyMs = 900_000L)
                if (call != null) break
            }

            if (call != null) {
                _monitoringState.value = CallMonitoringState.CallDetected(call)
                onCallCompleted(call)
            } else {
                _monitoringState.value = CallMonitoringState.CallLogUnavailable(
                    "Call ended, but no recent call-log entry was written by the phone's dialer within 7 seconds. Tap 'Check Call Log Now' to re-check."
                )
            }
            isQueryingCallLog = false
        }
    }

    /**
     * Manual or onResume probe for recent calls.
     * Crucial for detecting calls that finished while the app was backgrounded
     * without active foreground callbacks.
     */
    fun checkRecentCompletedCall(maxEndRecencyMs: Long = 900_000L): CallEvent? {
        val call = callLogReader.findMostRecentCall(maxEndRecencyMs = maxEndRecencyMs)
        if (call != null) {
            _monitoringState.value = CallMonitoringState.CallDetected(call)
            onCallCompleted(call)
        }
        return call
    }

    /**
     * Simulation / Testing hook
     */
    fun simulateCallEnded(callEvent: CallEvent) {
        _monitoringState.value = CallMonitoringState.CallDetected(callEvent)
        onCallCompleted(callEvent)
    }
}
