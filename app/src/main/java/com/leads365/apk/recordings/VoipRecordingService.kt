package com.leads365.apk.recordings

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.leads365.apk.data.repository.PolicyRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Phase 3 — Foreground service that captures audio during a WhatsApp VoIP call.
 *
 * Lifecycle:
 *   ACTION_START → start foreground + MediaRecorder.start()
 *   ACTION_STOP  → MediaRecorder.stop/release → VoipCallEnqueueHelper.enqueueVoipRecording()
 *                  → stopForeground + stopSelf()
 *
 * Android 14/15 safety:
 *   - Requires RECORD_AUDIO permission at runtime.
 *   - Uses FOREGROUND_SERVICE_TYPE_MICROPHONE on API 29+.
 *   - Catches SecurityException from startForeground on Android 14+.
 */
@AndroidEntryPoint
class VoipRecordingService : Service() {

    // ── Hilt-injected dependencies ──────────────────────────────────────────
    @Inject lateinit var voipCallEnqueueHelper: VoipCallEnqueueHelper
    @Inject lateinit var policyRepository: PolicyRepository

    // ── State ───────────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var callerIdentifier: String = ""
    private var sourceApp: String = ""
    private var startTimeMs: Long = 0L
    private var isForegroundActive: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Service lifecycle ───────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> handleStop(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isForegroundActive = false
        releaseRecorder()
        serviceScope.cancel()
    }

    // ── ACTION_START ────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        if (mediaRecorder != null) {
            Log.w(TAG, "Recording already in progress — ignoring duplicate ACTION_START")
            return
        }

        // Guard: permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — aborting capture")
            stopSelf()
            return
        }

        callerIdentifier = intent.getStringExtra(EXTRA_CALLER)      ?: "WhatsApp Contact"
        sourceApp        = intent.getStringExtra(EXTRA_SOURCE_APP)  ?: "com.whatsapp"
        startTimeMs      = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())

        // Start foreground BEFORE policy check so startForegroundService contract is always fulfilled
        ensureNotificationChannel()
        try {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(callerIdentifier),
                fgType
            )
            isForegroundActive = true
        } catch (se: SecurityException) {
            Log.e(TAG, "startForeground SecurityException — missing FOREGROUND_SERVICE_MICROPHONE?", se)
            stopSelf()
            return
        }

        // Guard: policy pause
        if (policyRepository.isRecordingSyncPaused()) {
            Log.w(TAG, "Recording sync paused by policy — aborting capture")
            stopForegroundAndStopSelf()
            return
        }

        // Prepare output file
        val voipDir = File(filesDir, "voip_recordings").apply { mkdirs() }
        val sanitized = callerIdentifier.replace(Regex("[^A-Za-z0-9_+\\-]"), "_").take(40)
        val outFile = File(voipDir, "WA_${sanitized}_$startTimeMs.m4a")
        outputFile = outFile

        // Initialise MediaRecorder (API-safe constructor)
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            // Try audio sources in preference order: VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC
            var started = false
            for (audioSource in listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )) {
                try {
                    recorder.reset()
                    recorder.setAudioSource(audioSource)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioSamplingRate(44100)
                    recorder.setAudioEncodingBitRate(96_000)
                    recorder.setOutputFile(outFile.absolutePath)
                    recorder.prepare()
                    recorder.start()
                    started = true
                    Log.i(TAG, "Recording started (source=$audioSource) → ${outFile.name}")
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "AudioSource $audioSource failed: ${e.message}")
                } catch (e: RuntimeException) {
                    Log.w(TAG, "AudioSource $audioSource RuntimeException: ${e.message}")
                }
            }

            if (!started) {
                Log.e(TAG, "All audio sources failed — stopping service")
                recorder.release()
                outputFile = null
                runCatching { outFile.delete() }
                stopForegroundAndStopSelf()
                return
            }

            mediaRecorder = recorder
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting MediaRecorder", e)
            runCatching { recorder.release() }
            outputFile = null
            runCatching { outFile.delete() }
            stopForegroundAndStopSelf()
        }
    }

    // ── ACTION_STOP ─────────────────────────────────────────────────────────

    private fun handleStop(intent: Intent) {
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 0)
        val capturedFile = outputFile
        val capturedCaller = callerIdentifier
        val capturedSourceApp = sourceApp
        val capturedStartTime = startTimeMs

        // Clear references and stop MediaRecorder synchronously before launching IO work
        outputFile = null
        releaseRecorder()

        if (!isForegroundActive && capturedFile == null) {
            stopSelf()
            return
        }

        // Enqueue in IO coroutine; stop foreground AFTER enqueue completes
        serviceScope.launch {
            try {
                if (capturedFile != null) {
                    voipCallEnqueueHelper.enqueueVoipRecording(
                        callerIdentifier = capturedCaller,
                        sourceApp        = capturedSourceApp,
                        file             = capturedFile,
                        startTimeMs      = capturedStartTime,
                        durationSec      = durationSec
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "enqueueVoipRecording failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    stopForegroundAndStopSelf()
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun stopForegroundAndStopSelf() {
        if (isForegroundActive) {
            isForegroundActive = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun releaseRecorder() {
        mediaRecorder?.let { r ->
            try { r.stop()    } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        mediaRecorder = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "VoIP Call Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Active when recording a WhatsApp call" }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(caller: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording WhatsApp Call")
            .setContentText("Caller: $caller")
            .setOngoing(true)
            .build()
    }

    // ── Constants ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VoipRecordingService"

        const val ACTION_START        = "com.leads365.apk.action.VOIP_RECORD_START"
        const val ACTION_STOP         = "com.leads365.apk.action.VOIP_RECORD_STOP"
        const val EXTRA_CALLER        = "extra_caller"
        const val EXTRA_SOURCE_APP    = "extra_source_app"
        const val EXTRA_START_TIME    = "extra_start_time_ms"
        const val EXTRA_DURATION_SEC  = "extra_duration_sec"
        const val CHANNEL_ID          = "leads365_voip_capture"
        const val NOTIFICATION_ID     = 9002
    }
}
