package com.leads365.apk.calls

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var isCallActive       = false
    private var isRecordingStarted = false
    private var callStartTimeMs    = 0L
    private var currentCaller      = ""
    private var currentSourceApp   = ""

    // ── Debounce handler (3 s) ──────────────────────────────────────────────
    private val stopHandler              = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null

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
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingStop()
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
                isCallActive     = true
                callStartTimeMs  = System.currentTimeMillis()
                currentSourceApp = pkg
                currentCaller    = callerText ?: "WhatsApp Contact"
                cancelPendingStop()
                Log.i(TAG, "VoIP call detected: caller='$currentCaller' pkg=$pkg")
                isRecordingStarted = sendStartIntent()
            }

            // ── Call is active; keep updating caller if it resolves later ───
            isCallActive && (hasEndCallBtn || hasCallTimer) -> {
                // In-call controls still visible → call ongoing → cancel any pending stop debounce
                cancelPendingStop()
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
        isCallActive = false
        isRecordingStarted = false
        if (!wasRecording) return

        val intent = Intent(this, VoipRecordingService::class.java).apply {
            action = VoipRecordingService.ACTION_STOP
            putExtra(VoipRecordingService.EXTRA_CALLER,       currentCaller)
            putExtra(VoipRecordingService.EXTRA_SOURCE_APP,   currentSourceApp)
            putExtra(VoipRecordingService.EXTRA_START_TIME,   callStartTimeMs)
            putExtra(VoipRecordingService.EXTRA_DURATION_SEC, durationSec)
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VoipRecordingService", e)
        }
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

        /** Matches call timer strings like "0:42", "1:05", "12:34", "1:23:45". */
        private val TIMER_REGEX = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

        /** Text fragments to skip when searching for the caller identifier. */
        private val STATUS_LABELS = listOf(
            "Calling", "Ringing", "Connecting", "Reconnecting",
            "End-to-end encrypted", "encrypted", "WhatsApp call"
        )
    }
}
