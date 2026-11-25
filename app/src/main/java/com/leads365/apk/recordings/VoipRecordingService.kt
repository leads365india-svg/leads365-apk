package com.leads365.apk.recordings

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    @Volatile
    private var isRecording: Boolean = false
    private var sawCommunicationMode: Boolean = false
    private var nonCommChecks: Int = 0
    private var activeSourceIndex: Int = -1
    private var recordingGeneration: Long = 0L
    private var watchdogJob: Job? = null
    private var silenceCheckJob: Job? = null
    private val stopLock = Any()

    private val audioManager: AudioManager? by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

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
        synchronized(stopLock) {
            recordingGeneration++
            watchdogJob?.cancel()
            watchdogJob = null
            silenceCheckJob?.cancel()
            silenceCheckJob = null
            isRecording = false
            isForegroundActive = false
            isRunning = false
            activeSourceIndex = -1
            releaseRecorder()
        }
        serviceScope.cancel()
    }

    // ── ACTION_START ────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        synchronized(stopLock) {
            if (mediaRecorder != null || isRecording) {
                Log.w(TAG, "Recording already in progress — ignoring duplicate ACTION_START")
                return
            }
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
            isRunning = true
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

        synchronized(stopLock) {
            if (mediaRecorder != null || isRecording) {
                Log.w(TAG, "Recording became active concurrently — ignoring ACTION_START")
                return
            }
            val generation = ++recordingGeneration
            outputFile = outFile
            val recorder = createMediaRecorder()
            try {
                val startedIdx = tryStartRecorderFromIndex(recorder, outFile, startIndex = 0, sessionGeneration = generation)
                if (startedIdx == null) {
                    Log.e(TAG, "All audio sources failed (${AUDIO_SOURCE_PRIORITY.joinToString()})")
                    runCatching { recorder.release() }
                    outputFile = null
                    activeSourceIndex = -1
                    runCatching { outFile.delete() }
                    stopForegroundAndStopSelf()
                    return
                }

                mediaRecorder = recorder
                activeSourceIndex = startedIdx
                isRecording = true
                startAudioModeWatchdog(generation)
                scheduleSilenceCheck(startedIdx, generation)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error starting MediaRecorder", e)
                runCatching { recorder.release() }
                outputFile = null
                activeSourceIndex = -1
                runCatching { outFile.delete() }
                stopForegroundAndStopSelf()
            }
        }
    }

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun tryStartRecorderFromIndex(
        recorder: MediaRecorder,
        outFile: File,
        startIndex: Int,
        sessionGeneration: Long
    ): Int? {
        for (idx in startIndex until AUDIO_SOURCE_PRIORITY.size) {
            val audioSource = AUDIO_SOURCE_PRIORITY[idx]
            try {
                if (outFile.exists()) {
                    runCatching { outFile.delete() }
                }
                recorder.reset()
                recorder.setAudioSource(audioSource)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(AUDIO_SAMPLING_RATE)
                recorder.setAudioEncodingBitRate(AUDIO_ENCODING_BITRATE)
                try {
                    recorder.setMaxFileSize(MAX_FILE_SIZE_BYTES)
                } catch (sizeErr: Exception) {
                    Log.w(TAG, "setMaxFileSize unsupported on device for source=$audioSource: ${sizeErr.message}")
                }
                recorder.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        val sameSession = synchronized(stopLock) {
                            isRecording && sessionGeneration == recordingGeneration
                        }
                        if (sameSession) {
                            val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000L)
                                .toInt()
                                .coerceAtLeast(1)
                            Log.w(TAG, "Max VoIP recording size (45 MB) reached; stopping at ${elapsedSec}s")
                            stopCaptureAndEnqueue(elapsedSec)
                        }
                    }
                }
                recorder.setOutputFile(outFile.absolutePath)
                recorder.prepare()
                recorder.start()
                // Reset baseline maxAmplitude latch right after start
                runCatching { recorder.maxAmplitude }
                Log.i(TAG, "Recording started (source=$audioSource, idx=$idx) → ${outFile.name}")
                return idx
            } catch (e: IOException) {
                Log.w(TAG, "AudioSource $audioSource IOException at ${AUDIO_SAMPLING_RATE}Hz/${AUDIO_ENCODING_BITRATE}bps", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "AudioSource $audioSource RuntimeException at ${AUDIO_SAMPLING_RATE}Hz/${AUDIO_ENCODING_BITRATE}bps", e)
            }
        }
        return null
    }

    private fun scheduleSilenceCheck(sourceIdx: Int, generation: Long) {
        silenceCheckJob?.cancel()
        silenceCheckJob = serviceScope.launch {
            delay(SILENCE_CHECK_DELAY_MS)
            if (!isActive) return@launch
            switchAudioSourceIfSilent(sourceIdx, generation)
        }
    }

    private fun switchAudioSourceIfSilent(sourceIdx: Int, generation: Long) {
        synchronized(stopLock) {
            if (!isRecording || generation != recordingGeneration) return
            val recorder = mediaRecorder ?: return
            val outFile = outputFile ?: return
            val currentSource = AUDIO_SOURCE_PRIORITY.getOrElse(sourceIdx) { -1 }

            val amplitude = try {
                recorder.maxAmplitude
            } catch (e: Exception) {
                Log.w(TAG, "maxAmplitude check failed for source=$currentSource: ${e.message}")
                return
            }

            Log.i(
                TAG,
                "Audio source=$currentSource ${SILENCE_CHECK_DELAY_MS}ms maxAmplitude=$amplitude (idx=$sourceIdx/${AUDIO_SOURCE_PRIORITY.size})"
            )

            val nextIdx = nextFallbackSourceIndex(sourceIdx, amplitude) ?: return
            Log.w(
                TAG,
                "Source $currentSource returned maxAmplitude=0 after ${SILENCE_CHECK_DELAY_MS}ms; switching to fallback idx=$nextIdx (source=${AUDIO_SOURCE_PRIORITY[nextIdx]})"
            )

            releaseRecorder()
            val nextRecorder = createMediaRecorder()
            val switchedIdx = tryStartRecorderFromIndex(
                recorder = nextRecorder,
                outFile = outFile,
                startIndex = nextIdx,
                sessionGeneration = generation
            )
            if (switchedIdx != null) {
                mediaRecorder = nextRecorder
                activeSourceIndex = switchedIdx
                scheduleSilenceCheck(switchedIdx, generation)
            } else {
                Log.e(TAG, "All fallback audio sources failed after silent source=$currentSource")
                runCatching { nextRecorder.release() }
                isRecording = false
                isRunning = false
                recordingGeneration++
                activeSourceIndex = -1
                outputFile = null
                watchdogJob?.cancel()
                watchdogJob = null
                runCatching { outFile.delete() }
                serviceScope.launch(Dispatchers.Main) {
                    stopForegroundAndStopSelf()
                }
            }
        }
    }

    // ── AudioMode Watchdog ──────────────────────────────────────────────────

    private fun startAudioModeWatchdog(generation: Long) {
        watchdogJob?.cancel()
        sawCommunicationMode = false
        nonCommChecks = 0

        val am = audioManager ?: return
        val currentStartTime = startTimeMs

        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_POLL_INTERVAL_MS)
                val stillActive = synchronized(stopLock) {
                    isRecording && generation == recordingGeneration
                }
                if (!stillActive) break

                val mode = try {
                    am.mode
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading AudioManager.mode: ${e.message}")
                    AudioManager.MODE_NORMAL
                }
                val elapsedSec = ((System.currentTimeMillis() - currentStartTime) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)

                val transition = evaluateWatchdogSample(
                    audioMode = mode,
                    sawCommunicationMode = sawCommunicationMode,
                    nonCommChecks = nonCommChecks,
                    elapsedSec = elapsedSec
                )
                sawCommunicationMode = transition.sawCommunicationMode
                nonCommChecks = transition.nonCommChecks

                if (transition.shouldStop) {
                    val reason = if (elapsedSec >= MAX_RECORDING_DURATION_SEC) {
                        "safety ceiling (${MAX_RECORDING_DURATION_SEC}s) reached"
                    } else {
                        "VoIP audio mode ended (mode=$mode)"
                    }
                    Log.i(TAG, "$reason; auto-stopping recording at ${elapsedSec}s")
                    stopCaptureAndEnqueue(elapsedSec.coerceAtLeast(1))
                    break
                }
            }
        }
    }

    // ── ACTION_STOP & Capture Stop ──────────────────────────────────────────

    private fun handleStop(intent: Intent) {
        val stopCaller = intent.getStringExtra(EXTRA_CALLER)?.trim()
        if (!stopCaller.isNullOrBlank() && !stopCaller.equals("WhatsApp Contact", ignoreCase = true)) {
            callerIdentifier = stopCaller
        }
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 0)
        stopCaptureAndEnqueue(durationSec)
    }

    private fun stopCaptureAndEnqueue(durationSec: Int) {
        val capturedFile: File?
        val capturedCaller: String
        val capturedSourceApp: String
        val capturedStartTime: Long

        synchronized(stopLock) {
            watchdogJob?.cancel()
            watchdogJob = null
            silenceCheckJob?.cancel()
            silenceCheckJob = null
            recordingGeneration++

            if (!isRecording && outputFile == null) {
                if (!isForegroundActive) {
                    isRunning = false
                    stopSelf()
                }
                return
            }

            isRecording = false
            isRunning = false
            activeSourceIndex = -1
            capturedFile = outputFile
            capturedCaller = callerIdentifier
            capturedSourceApp = sourceApp
            capturedStartTime = startTimeMs
            outputFile = null
            releaseRecorder()
        }

        val resolvedDurationSec = if (durationSec > 0) {
            durationSec
        } else if (capturedStartTime > 0L) {
            ((System.currentTimeMillis() - capturedStartTime) / 1000L).toInt().coerceAtLeast(1)
        } else {
            1
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
                        durationSec      = resolvedDurationSec
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
        isRunning = false
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
        const val CHANNEL_ID              = "leads365_voip_capture"
        const val NOTIFICATION_ID         = 9002
        const val WATCHDOG_POLL_INTERVAL_MS = 2_000L
        const val WATCHDOG_REQUIRED_NON_COMM_CHECKS = 2
        const val SILENCE_CHECK_DELAY_MS  = 1_500L
        const val AUDIO_SAMPLING_RATE     = 44_100
        const val AUDIO_ENCODING_BITRATE  = 64_000
        const val MAX_FILE_SIZE_BYTES     = 45L * 1024L * 1024L
        const val MAX_RECORDING_DURATION_SEC = 45 * 60

        internal val AUDIO_SOURCE_PRIORITY = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        @Volatile
        var isRunning: Boolean = false
            private set

        internal fun nextFallbackSourceIndex(
            currentIndex: Int,
            maxAmplitude: Int,
            totalSources: Int = AUDIO_SOURCE_PRIORITY.size
        ): Int? = if (maxAmplitude == 0 && currentIndex >= 0 && currentIndex + 1 < totalSources) {
            currentIndex + 1
        } else {
            null
        }

        internal data class WatchdogTransition(
            val sawCommunicationMode: Boolean,
            val nonCommChecks: Int,
            val shouldStop: Boolean
        )

        internal fun evaluateWatchdogSample(
            audioMode: Int,
            sawCommunicationMode: Boolean,
            nonCommChecks: Int,
            elapsedSec: Int
        ): WatchdogTransition {
            if (elapsedSec >= MAX_RECORDING_DURATION_SEC) {
                return WatchdogTransition(
                    sawCommunicationMode = sawCommunicationMode,
                    nonCommChecks = nonCommChecks,
                    shouldStop = true
                )
            }
            return if (audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_IN_CALL) {
                WatchdogTransition(
                    sawCommunicationMode = true,
                    nonCommChecks = 0,
                    shouldStop = false
                )
            } else if (sawCommunicationMode) {
                val updatedChecks = nonCommChecks + 1
                WatchdogTransition(
                    sawCommunicationMode = true,
                    nonCommChecks = updatedChecks,
                    shouldStop = updatedChecks >= WATCHDOG_REQUIRED_NON_COMM_CHECKS
                )
            } else {
                // OEM does not expose MODE_IN_COMMUNICATION; do not stop via AudioManager alone
                WatchdogTransition(
                    sawCommunicationMode = false,
                    nonCommChecks = 0,
                    shouldStop = false
                )
            }
        }
    }
}
