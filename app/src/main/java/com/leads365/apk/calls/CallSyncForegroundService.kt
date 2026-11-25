package com.leads365.apk.calls

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.leads365.apk.core.CallEvent
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.ui.MainActivity
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persistent Foreground Service ensuring call detection, missed-call capture,
 * and recording upload resumption run continuously even when the app is swiped
 * or closed from Recents.
 */
@AndroidEntryPoint
class CallSyncForegroundService : Service() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var workManagerScheduler: dagger.Lazy<WorkManagerScheduler>

    @Inject
    lateinit var callProcessingManager: dagger.Lazy<CallProcessingManager>

    @Inject
    lateinit var callRepository: dagger.Lazy<CallRepository>

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var callStateObserver: CallStateObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CallSyncForegroundService onCreate")
        startInForeground()
        initCallObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "CallSyncForegroundService onStartCommand action=$action")

        if (action == ACTION_STOP) {
            Log.i(TAG, "Received ACTION_STOP. Stopping CallSyncForegroundService.")
            stopForegroundService()
            return START_NOT_STICKY
        }

        // Strict Auth Gate: only authenticated users keep foreground sync alive
        if (!authRepository.isLoggedIn()) {
            Log.i(TAG, "User not authenticated; stopping CallSyncForegroundService")
            stopForegroundService()
            return START_NOT_STICKY
        }

        startInForeground()

        // Ensure CallStateObserver is active
        if (callStateObserver?.isObserving() != true) {
            callStateObserver?.startObserving()
        }

        // Opportunistic background sync of pending calls and uploads on service start
        serviceScope.launch {
            try {
                callRepository.get().syncPendingCallsToBackend()
                workManagerScheduler.get().resumePendingUploads()
            } catch (e: Exception) {
                Log.w(TAG, "Opportunistic sync on service start failed: ${e.message}")
            }
        }

        isRunning = true
        return START_STICKY
    }

    private fun startInForeground() {
        createNotificationChannel()
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground notice: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Leads-365 Call Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps call detection and recording sync active in background"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Leads-365 Call Sync Active")
            .setContentText("Monitoring calls and syncing recordings in background")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun initCallObserver() {
        if (callStateObserver != null) return

        val reader = CallLogReader(applicationContext)
        val observer = CallStateObserver(
            context = applicationContext,
            callLogReader = reader,
            scope = serviceScope,
            onCallCompleted = { call ->
                handleCallCompleted(call)
            }
        )
        callStateObserver = observer

        if (observer.hasPhoneStatePermission()) {
            observer.startObserving()
            Log.i(TAG, "CallStateObserver active in CallSyncForegroundService")
        } else {
            Log.w(TAG, "Phone State permission not yet granted; observer waiting")
        }
    }

    private fun handleCallCompleted(call: CallEvent) {
        Log.i(TAG, "Call completed detected in background: ${call.direction} to ${call.normalizedNumber}")
        serviceScope.launch {
            try {
                val processor = callProcessingManager.get()
                val repo = callRepository.get()
                val scheduler = workManagerScheduler.get()

                val processResult = processor.processCallEvent(call)
                val clientCallId = when (processResult) {
                    is CallProcessingResult.Recorded -> processResult.call.clientCallId
                    is CallProcessingResult.UnlinkedIncomingRecorded -> processResult.call.clientCallId
                    else -> null
                }

                if (!clientCallId.isNullOrBlank()) {
                    try {
                        repo.reportCallToBackend(clientCallId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Immediate call report deferred: ${e.message}")
                    }
                }

                // Trigger opportunistic call sync and recording upload resumption
                try {
                    repo.syncPendingCallsToBackend()
                    scheduler.resumePendingUploads()
                } catch (e: Exception) {
                    Log.w(TAG, "Call/recording sync error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call event in foreground service", e)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: App swiped/closed from Recents")

        if (authRepository.isLoggedIn()) {
            scheduleServiceRestart()
        }
    }

    private fun scheduleServiceRestart() {
        try {
            val restartIntent = Intent(applicationContext, CallSyncForegroundService::class.java).apply {
                action = ACTION_START
            }
            val restartPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    RESTART_REQUEST_CODE,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    RESTART_REQUEST_CODE,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val restartTime = SystemClock.elapsedRealtime() + 1000L
            alarmManager?.set(AlarmManager.ELAPSED_REALTIME, restartTime, restartPendingIntent)
            Log.i(TAG, "Scheduled quick service restart alarm in 1000ms")

            // Also trigger background one-time sync as backup
            workManagerScheduler.get().triggerOneTimeLeadSync()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule restart alarm: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        isRunning = false
        callStateObserver?.stopObserving()
        callStateObserver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CallSyncForegroundService onDestroy")
        isRunning = false
        callStateObserver?.stopObserving()
        callStateObserver = null
        serviceScope.cancel()
    }

    companion object {
        const val TAG = "CallSyncFgService"
        const val CHANNEL_ID = "call_sync_service_channel"
        const val NOTIFICATION_ID = 9001
        const val RESTART_REQUEST_CODE = 1001

        const val ACTION_START = "com.leads365.apk.calls.action.START_CALL_SYNC"
        const val ACTION_STOP = "com.leads365.apk.calls.action.STOP_CALL_SYNC"

        @Volatile
        var isRunning: Boolean = false
            internal set

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, CallSyncForegroundService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CallSyncForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CallSyncForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CallSyncForegroundService: ${e.message}", e)
            }
        }
    }
}
