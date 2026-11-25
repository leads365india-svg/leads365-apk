package com.leads365.apk.calls

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.leads365.apk.recordings.VoipRecordingService

/**
 * Phase 3 — Accessibility service that detects active WhatsApp and WhatsApp Business
 * VoIP calls and drives [VoipRecordingService] via explicit Intents.
 *
 * NOT annotated with @AndroidEntryPoint — Hilt does NOT support AccessibilityService.
 * All dependencies are obtained via applicationContext or Intents; no Hilt injection here.
 *
 * Detection strategy:
 *   • Monitors window-content changes in TARGET_PACKAGES.
 *   • Looks for the call timer (HH:MM or HH:MM:SS regex) → call is active.
 *   • Looks for the end-call button by resource-id or text.
 *   • When end-call disappears AND call was active → 3-second debounce → fire ACTION_STOP.
 *
 * Recording is only started when RECORD_AUDIO permission is granted; the service
 * remains alive and monitoring even without it, so it can record as soon as permission
 * is granted without requiring a reboot.
 */
class WhatsAppCallAccessibilityService : AccessibilityService() {

    // ── Call state ──────────────────────────────────────────────────────────
    private var isCallActive            = false
    private var isRecordingStarted      = false
    private var serviceConfirmedRunning = false
    private var activePackage: String?  = null
    private var callStartTimeMs         = 0L
    private var currentCaller           = ""
    private var currentSourceApp        = ""
    private var heartbeatNoCallUiChecks = 0

    private val audioManager: AudioManager? by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val powerManager: PowerManager? by lazy {
        getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    // ── Debounce & heartbeat handler ────────────────────────────────────────
    private val stopHandler              = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null
    private var heartbeatRunnable: Runnable?   = null

    // ── AccessibilityService callbacks ──────────────────────────────────────

    override fun onServiceConnected() {
        Log.i(TAG, "WhatsApp Accessibility Service connected")
        // Runtime config is loaded from whatsapp_accessibility_config.xml,
        // but set feedback type here defensively.
        serviceInfo = serviceInfo?.also { info ->
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return

        // Reset stale state immediately if VoipRecordingService already auto-stopped via watchdog
        if (isCallActive) {
            if (VoipRecordingService.isRunning) {
                serviceConfirmedRunning = true
            } else if (serviceConfirmedRunning || System.currentTimeMillis() - callStartTimeMs >= SERVICE_STARTUP_GRACE_MS) {
                Log.i(TAG, "VoipRecordingService stopped externally — resetting active VoIP call state")
                resetCallState()
            }
        }

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return

        try {
            processWindowState(root, pkg)
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        cancelPendingStop()
        stopHeartbeat()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingStop()
        stopHeartbeat()
        // If the service is killed while recording, stop VoipRecordingService cleanly
        if (isCallActive) {
            sendStopIntent(durationSec = elapsedCallSeconds())
        }
    }

    // ── Core detection logic ────────────────────────────────────────────────

    private fun processWindowState(root: AccessibilityNodeInfo, pkg: String) {
        val hasCallTimer   = findCallTimerText(root) != null
        val hasEndCallBtn  = findEndCallButton(root) != null
        val callerText     = extractCallerIdentifier(root)

        when {
            // ── Call just started (timer appeared for the first time) ───────
            !isCallActive && hasCallTimer -> {
                isCallActive            = true
                serviceConfirmedRunning = false
                heartbeatNoCallUiChecks = 0
                activePackage           = pkg
                callStartTimeMs         = System.currentTimeMillis()
                currentSourceApp        = pkg
                currentCaller           = callerText ?: "WhatsApp Contact"
                cancelPendingStop()
                Log.i(TAG, "VoIP call detected: caller='$currentCaller' pkg=$pkg")
                isRecordingStarted = sendStartIntent()
                if (isRecordingStarted) {
                    startHeartbeat()
                } else {
                    isCallActive = false
                    activePackage = null
                }
            }

            // ── Call is active; keep updating caller if it resolves later ───
            isCallActive && (hasEndCallBtn || hasCallTimer) -> {
                // In-call controls still visible → call ongoing → cancel any pending stop debounce
                cancelPendingStop()
                heartbeatNoCallUiChecks = 0
                activePackage = pkg
                if (!callerText.isNullOrBlank()) currentCaller = callerText
            }

            // ── Call may have ended (controls gone while call was active) ───
            isCallActive && !hasCallTimer && !hasEndCallBtn -> {
                schedulePendingStop()
            }
        }
    }

    // ── Intent helpers ──────────────────────────────────────────────────────

    private fun sendStartIntent(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — skipping recording start")
            return false
        }
        val intent = Intent(this, VoipRecordingService::class.java).apply {
            action = VoipRecordingService.ACTION_START
            putExtra(VoipRecordingService.EXTRA_CALLER,      currentCaller)
            putExtra(VoipRecordingService.EXTRA_SOURCE_APP,  currentSourceApp)
            putExtra(VoipRecordingService.EXTRA_START_TIME,  callStartTimeMs)
        }
        return try {
            startForegroundService(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoipRecordingService", e)
            false
        }
    }

    private fun sendStopIntent(durationSec: Int) {
        val wasRecording = isRecordingStarted
        resetCallState()
        if (!wasRecording || !VoipRecordingService.isRunning) return

        val intent = Intent(this, VoipRecordingService::class.java).apply {
            action = VoipRecordingService.ACTION_STOP
            putExtra(VoipRecordingService.EXTRA_CALLER,       currentCaller)
            putExtra(VoipRecordingService.EXTRA_SOURCE_APP,   currentSourceApp)
            putExtra(VoipRecordingService.EXTRA_START_TIME,   callStartTimeMs)
            putExtra(VoipRecordingService.EXTRA_DURATION_SEC, durationSec)
        }
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            if (VoipRecordingService.isRunning) {
                try {
                    startForegroundService(intent)
                } catch (fgErr: Exception) {
                    Log.e(TAG, "Failed to stop VoipRecordingService via foreground fallback", fgErr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VoipRecordingService", e)
        }
    }

    private fun resetCallState() {
        cancelPendingStop()
        stopHeartbeat()
        isCallActive = false
        isRecordingStarted = false
        serviceConfirmedRunning = false
        heartbeatNoCallUiChecks = 0
        activePackage = null
    }

    // ── Heartbeat (2 s) for screen-lock / app-exit safety ───────────────────

    private fun startHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                if (!isCallActive) {
                    heartbeatRunnable = null
                    return
                }

                if (VoipRecordingService.isRunning) {
                    serviceConfirmedRunning = true
                } else if (serviceConfirmedRunning || System.currentTimeMillis() - callStartTimeMs >= SERVICE_STARTUP_GRACE_MS) {
                    Log.i(TAG, "Heartbeat: VoipRecordingService no longer running — resetting call state")
                    resetCallState()
                    return
                }

                val isScreenOn = try {
                    powerManager?.isInteractive ?: true
                } catch (_: Exception) {
                    true
                }

                if (!isScreenOn) {
                    // Screen is OFF (likely held to ear): never stop based on missing UI nodes
                    heartbeatNoCallUiChecks = 0
                } else {
                    val mode = try {
                        audioManager?.mode ?: AudioManager.MODE_NORMAL
                    } catch (_: Exception) {
                        AudioManager.MODE_NORMAL
                    }
                    val isInComm = mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL
                    val hasCallUi = hasActiveWhatsAppCallUi()

                    if (!isInComm && !hasCallUi) {
                        heartbeatNoCallUiChecks++
                        if (heartbeatNoCallUiChecks >= HEARTBEAT_REQUIRED_NO_UI_CHECKS) {
                            Log.i(
                                TAG,
                                "Heartbeat: screen ON, audio mode=$mode, and no WhatsApp call UI for $heartbeatNoCallUiChecks checks — stopping recording"
                            )
                            sendStopIntent(durationSec = elapsedCallSeconds())
                            return
                        }
                    } else {
                        heartbeatNoCallUiChecks = 0
                    }
                }

                if (isCallActive) {
                    stopHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
        heartbeatRunnable = runnable
        stopHandler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun hasActiveWhatsAppCallUi(): Boolean {
        val checkedRoots = mutableListOf<Pair<AccessibilityNodeInfo, Boolean>>()
        try {
            rootInActiveWindow?.let { checkedRoots.add(it to true) }
            try {
                windows?.forEach { win ->
                    win?.root?.let { root ->
                        val pkg = root.packageName?.toString()
                        if (pkg == null || pkg in TARGET_PACKAGES) {
                            checkedRoots.add(root to true)
                        } else {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                @Suppress("DEPRECATION")
                                root.recycle()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            return checkedRoots.any { (node, _) ->
                val pkg = node.packageName?.toString()
                (pkg == null || pkg in TARGET_PACKAGES) &&
                        (findEndCallButton(node) != null || findCallTimerText(node) != null)
            }
        } catch (_: Exception) {
            return false
        } finally {
            checkedRoots.forEach { (node, shouldRecycle) ->
                if (shouldRecycle && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { stopHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // ── Debounce ─────────────────────────────────────────────────────────────

    private fun schedulePendingStop() {
        if (pendingStopRunnable != null) return // already scheduled
        val runnable = Runnable {
            pendingStopRunnable = null
            if (isCallActive) {
                Log.i(TAG, "Call ended (debounce elapsed) — duration=${elapsedCallSeconds()}s")
                sendStopIntent(durationSec = elapsedCallSeconds())
            }
        }
        pendingStopRunnable = runnable
        stopHandler.postDelayed(runnable, STOP_DEBOUNCE_MS)
    }

    private fun cancelPendingStop() {
        pendingStopRunnable?.let { stopHandler.removeCallbacks(it) }
        pendingStopRunnable = null
    }

    private fun elapsedCallSeconds(): Int =
        ((System.currentTimeMillis() - callStartTimeMs) / 1000L).toInt().coerceAtLeast(1)

    // ── Node search helpers ──────────────────────────────────────────────────

    /**
     * Finds the in-call end-call / hang-up button by resource-id or text/contentDescription.
     */
    private fun findEndCallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Resource-id based (most reliable)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/end_call_btn")
            if (nodes.isNotEmpty()) return nodes[0]
        }
        // Text / contentDescription fallback (BFS)
        return bfsFind(root) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            (text.equals("End call", ignoreCase = true) || text.equals("Hang up", ignoreCase = true)
                    || desc.equals("End call", ignoreCase = true) || desc.equals("Hang up", ignoreCase = true))
                    && node.isClickable
        }
    }

    /**
     * Finds the call timer text node matching HH:MM or HH:MM:SS.
     */
    private fun findCallTimerText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        bfsFind(root) { node ->
            val text = node.text?.toString() ?: ""
            TIMER_REGEX.matches(text)
        }

    /**
     * Extracts caller name/number from the call screen.
     * Checks known resource-id hints first, then falls back to the first prominent TextView
     * that is neither a status label nor an encryption notice.
     */
    private fun extractCallerIdentifier(root: AccessibilityNodeInfo): String? {
        val idHints = listOf(
            "com.whatsapp:id/call_screen_title",
            "com.whatsapp:id/name",
            "com.whatsapp.w4b:id/call_screen_title",
            "com.whatsapp.w4b:id/name"
        )
        for (hint in idHints) {
            val nodes = root.findAccessibilityNodeInfosByViewId(hint)
            val text  = nodes.firstOrNull()?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        // Fallback: first visible, non-empty TextView that isn't a status/encryption label
        return bfsFind(root) { node ->
            val text = node.text?.toString() ?: ""
            text.isNotBlank()
                    && !text.matches(TIMER_REGEX)
                    && !STATUS_LABELS.any { text.contains(it, ignoreCase = true) }
                    && node.className?.contains("TextView") == true
        }?.text?.toString()
    }

    /**
     * BFS traversal returning the first node matching [predicate], or null.
     */
    private fun bfsFind(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "WhatsAppAccessibility"

        /** Packages monitored by this service (must match whatsapp_accessibility_config.xml). */
        private val TARGET_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /** Wait 3 seconds after end-call button disappears before treating the call as ended. */
        private const val STOP_DEBOUNCE_MS = 3_000L

        /** Poll interval while active call is tracked so screen-lock / app-exit stops capture promptly. */
        private const val HEARTBEAT_INTERVAL_MS = 2_000L

        /** Grace period for VoipRecordingService.onStartCommand() to flip isRunning = true. */
        private const val SERVICE_STARTUP_GRACE_MS = 2_500L

        /** Consecutive 2s checks with screen ON, non-comm audio, and no call UI before stopping (6s). */
        private const val HEARTBEAT_REQUIRED_NO_UI_CHECKS = 3

        /** Matches call timer strings like "0:42", "1:05", "12:34", "1:23:45". */
        private val TIMER_REGEX = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

        /** Text fragments to skip when searching for the caller identifier. */
        private val STATUS_LABELS = listOf(
            "Calling", "Ringing", "Connecting", "Reconnecting",
            "End-to-end encrypted", "encrypted", "WhatsApp call"
        )
    }
}
