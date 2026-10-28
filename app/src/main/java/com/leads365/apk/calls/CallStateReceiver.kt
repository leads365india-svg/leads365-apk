package com.leads365.apk.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.leads365.apk.core.CallEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

class CallStateReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CallStateReceiverEntryPoint {
        fun callProcessingManager(): CallProcessingManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "CallStateReceiver received state: $stateStr")

        // Phase 10: Notify real-time call state transition for caller identification pop-up
        if (stateStr != null) {
            onCallStateChangedListener?.invoke(stateStr, incomingNumber)
        }

        if (stateStr == TelephonyManager.EXTRA_STATE_IDLE) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = CallLogReader(context.applicationContext)
                    var call: CallEvent? = null
                    // Poll with progressive delays to wait for dialer to flush call log
                    val delays = listOf(1500L, 2000L, 2500L, 3000L)
                    for (d in delays) {
                        delay(d)
                        call = reader.findMostRecentCall(maxEndRecencyMs = 900_000L)
                        if (call != null) break
                    }

                    if (call != null) {
                        Log.i(TAG, "Call detected from CallLog: raw=${call.rawNumber}, direction=${call.direction}, duration=${call.durationSeconds}s")

                        // 1. Notify all registered in-process listeners (e.g. UI/Wizard)
                        notifyCallDetected(call)

                        // 2. Direct pipeline processing (Room DB, Lead check, Targeted Recording Discovery, Upload queue)
                        try {
                            val entryPoint = EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                CallStateReceiverEntryPoint::class.java
                            )
                            val callProcessingManager = entryPoint.callProcessingManager()
                            Log.d(TAG, "Dispatching call to CallProcessingManager for recording discovery and persistence")
                            callProcessingManager.processCallEvent(call)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to dispatch call to CallProcessingManager: ${e.message}", e)
                        }
                    } else {
                        Log.w(TAG, "No completed call found in CallLog within recency window")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in CallStateReceiver background processing: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
        private val listeners = CopyOnWriteArrayList<(CallEvent) -> Unit>()

        @JvmStatic
        var onCallDetectedListener: ((CallEvent) -> Unit)?
            get() = listeners.firstOrNull()
            set(value) {
                listeners.clear()
                if (value != null) {
                    listeners.add(value)
                }
            }

        @JvmStatic
        fun addCallDetectedListener(listener: (CallEvent) -> Unit) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }

        @JvmStatic
        fun removeCallDetectedListener(listener: (CallEvent) -> Unit) {
            listeners.remove(listener)
        }

        @JvmStatic
        fun notifyCallDetected(call: CallEvent) {
            for (listener in listeners) {
                try {
                    listener.invoke(call)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying call listener: ${e.message}", e)
                }
            }
        }

        var onCallStateChangedListener: ((String, String?) -> Unit)? = null
    }
}
