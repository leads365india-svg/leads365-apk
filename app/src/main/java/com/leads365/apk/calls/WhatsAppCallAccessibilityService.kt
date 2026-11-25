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

        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        val rootsToRecycle = mutableListOf<AccessibilityNodeInfo>()
        if (activeRoot != null) rootsToRecycle.add(activeRoot)

        try {
            val activePkg = activeRoot?.packageName?.toString()
            val activeMatchesPackage = activeRoot != null && (activePkg == null || activePkg in TARGET_PACKAGES)
            if (activeMatchesPackage &&
                hasInCallControls(activeRoot!!) &&
                (isCallActive || findCallTimerText(activeRoot) != null)
            ) {
                processWindowState(activeRoot, activePkg ?: pkg)
                return
            }

            // Active window did not show complete call UI (or belonged to another window): inspect all WhatsApp windows
            val windowCandidates = collectTargetWindowRoots(rootsToRecycle)

            val controlCandidate =
                windowCandidates.firstOrNull { (node, _) ->
                    findCallTimerText(node) != null && hasInCallControls(node)
                } ?: windowCandidates.firstOrNull { (node, _) ->
                    hasInCallControls(node)
                }

            when {
                controlCandidate != null -> {
                    processWindowState(controlCandidate.first, controlCandidate.second)
                }
                activeMatchesPackage -> {
                    processWindowState(activeRoot!!, activePkg ?: pkg)
                }
                windowCandidates.isNotEmpty() -> {
                    processWindowState(windowCandidates.first().first, windowCandidates.first().second)
                }
                isCallActive -> {
                    schedulePendingStop()
                }
            }
        } finally {
            rootsToRecycle.forEach { safeRecycle(it) }
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
        val hasCallTimer      = findCallTimerText(root) != null
        val hasEndCallBtn     = findEndCallButton(root) != null
        val hasInCallControls = hasEndCallBtn || hasVoipControlMarker(root)
        val callerText        = extractCallerIdentifier(root)

        when {
            // ── Call just started (timer + end-call or active in-call controls visible — never trigger on 24h chat timestamps) ──
            !isCallActive && hasCallTimer && hasInCallControls -> {
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

            // ── Call is active; stay active while end-call button or in-call controls are visible ──
            isCallActive && hasInCallControls -> {
                cancelPendingStop()
                heartbeatNoCallUiChecks = 0
                activePackage = pkg
                if (!callerText.isNullOrBlank()) currentCaller = callerText
            }

            // ── Call may have ended (both end-call button and in-call controls gone while call was active) ──
            isCallActive && !hasInCallControls -> {
                schedulePendingStop()
            }

            // ── Diagnostic log when timer-like text is visible without recognized call controls ──
            !isCallActive && hasCallTimer && !hasInCallControls -> {
                Log.d(
                    TAG,
                    "Timer seen without controls (pkg=$pkg); clickable nodes: ${summarizeClickableNodes(root)}"
                )
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

    private fun collectTargetWindowRoots(
        rootsToRecycle: MutableList<AccessibilityNodeInfo>
    ): List<Pair<AccessibilityNodeInfo, String>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        try {
            windows?.forEach { win ->
                val winRoot = win?.root ?: return@forEach
                val winPkg = winRoot.packageName?.toString()
                if (winPkg != null && winPkg in TARGET_PACKAGES) {
                    rootsToRecycle.add(winRoot)
                    result.add(winRoot to winPkg)
                } else {
                    safeRecycle(winRoot)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun hasActiveWhatsAppCallUi(): Boolean {
        val checkedRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString()
                if (pkg == null || pkg in TARGET_PACKAGES) {
                    checkedRoots.add(root)
                } else {
                    safeRecycle(root)
                }
            }
            collectTargetWindowRoots(checkedRoots)

            return checkedRoots.any { node ->
                findEndCallButton(node) != null || hasInCallControls(node)
            }
        } catch (_: Exception) {
            return false
        } finally {
            checkedRoots.forEach { safeRecycle(it) }
        }
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            try {
                node.recycle()
            } catch (_: Exception) {}
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
    private fun findEndCallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findEndCallButtonInTree(root)

    /**
     * Returns true when active VoIP call controls (end-call, mute, speaker, bluetooth,
     * audio route, or call control container) are present in [root].
     */
    private fun hasInCallControls(root: AccessibilityNodeInfo): Boolean =
        hasInCallControlsInTree(root)

    private fun hasVoipControlMarker(root: AccessibilityNodeInfo): Boolean =
        hasVoipControlMarkerInTree(root)

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
        val hintedName = idHints.firstNotNullOfOrNull { hint ->
            bfsFind(root) { node ->
                node.viewIdResourceName == hint && !node.text.isNullOrBlank()
            }?.text?.toString()
        }
        if (hintedName != null) return hintedName
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
     * Collects up to [limit] clickable node IDs/descriptions/texts for diagnostic logging.
     */
    private fun summarizeClickableNodes(root: AccessibilityNodeInfo, limit: Int = 8): String {
        val items = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && items.size < limit) {
            val node = queue.removeFirst()
            if (node.isClickable) {
                val resId = node.viewIdResourceName ?: "-"
                val desc = node.contentDescription?.toString()?.take(40) ?: "-"
                val text = node.text?.toString()?.take(30) ?: "-"
                items.add("[id=$resId desc='$desc' text='$text']")
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return if (items.isEmpty()) "none" else items.joinToString(", ")
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

        /** View-ID substrings matching end-call controls across WhatsApp / WhatsApp Business variants. */
        private val END_CALL_VIEW_ID_TOKENS = listOf(
            "end_call", "call_end", "hangup", "hang_up", "disconnect"
        )

        /** Clickable end-call button labels across WhatsApp / WhatsApp Business builds. */
        private val END_CALL_LABELS = listOf(
            "end call", "hang up", "leave call", "disconnect"
        )

        /** Active VoIP call-control markers (outside chat screens). */
        private val IN_CALL_CONTROL_TOKENS = listOf(
            "speaker", "bluetooth", "audio_route", "call_controls", "voip_call"
        )

        internal fun findEndCallButtonInTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            bfsFindWithParentClickable(root) { node, parentClickable ->
                matchesEndCallNode(
                    viewId = node.viewIdResourceName,
                    text = node.text,
                    contentDesc = node.contentDescription,
                    isNodeOrParentClickable = node.isClickable || parentClickable
                )
            }

        internal fun hasInCallControlsInTree(root: AccessibilityNodeInfo): Boolean =
            findEndCallButtonInTree(root) != null || hasVoipControlMarkerInTree(root)

        internal fun hasVoipControlMarkerInTree(root: AccessibilityNodeInfo): Boolean =
            bfsFindWithParentClickable(root) { node, parentClickable ->
                matchesInCallControlMarker(
                    viewId = node.viewIdResourceName,
                    contentDesc = node.contentDescription,
                    isNodeOrParentClickable = node.isClickable || parentClickable
                )
            } != null

        internal fun matchesEndCallNode(
            viewId: String?,
            text: CharSequence?,
            contentDesc: CharSequence?,
            isNodeOrParentClickable: Boolean
        ): Boolean {
            val normalizedId = viewId?.lowercase() ?: ""
            if (END_CALL_VIEW_ID_TOKENS.any { normalizedId.contains(it) }) return true
            if (!isNodeOrParentClickable) return false
            return matchesEndCallLabel(text) || matchesEndCallLabel(contentDesc)
        }

        internal fun matchesEndCallLabel(raw: CharSequence?): Boolean {
            val value = raw?.toString()?.trim()?.lowercase() ?: return false
            if (value.isEmpty()) return false
            return value == "end" || END_CALL_LABELS.any { value.contains(it) }
        }

        internal fun matchesInCallControlMarker(
            viewId: String?,
            contentDesc: CharSequence?,
            isNodeOrParentClickable: Boolean
        ): Boolean {
            val normalizedId = viewId?.lowercase() ?: ""
            val normalizedDesc = contentDesc?.toString()?.trim()?.lowercase() ?: ""

            if (IN_CALL_CONTROL_TOKENS.any { normalizedId.contains(it) || normalizedDesc.contains(it) }) {
                return true
            }

            // Handle "mute" while ignoring chat-list muted-conversation status icons
            val idHasMute = normalizedId.contains("mute") &&
                !normalizedId.contains("indicator") &&
                !normalizedId.contains("row_mute") &&
                !normalizedId.contains("chat_mute") &&
                !normalizedId.contains("status_mute")
            if (idHasMute) return true

            val descHasMute = normalizedDesc.contains("mute") &&
                !normalizedDesc.contains("muted chat") &&
                !normalizedDesc.contains("notifications muted") &&
                (normalizedDesc != "muted" || isNodeOrParentClickable)
            return descHasMute
        }

        private fun bfsFind(
            root: AccessibilityNodeInfo,
            predicate: (AccessibilityNodeInfo) -> Boolean
        ): AccessibilityNodeInfo? =
            bfsFindWithParentClickable(root) { node, _ -> predicate(node) }

        private fun bfsFindWithParentClickable(
            root: AccessibilityNodeInfo,
            predicate: (node: AccessibilityNodeInfo, parentClickable: Boolean) -> Boolean
        ): AccessibilityNodeInfo? {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
            queue.add(root to false)
            while (queue.isNotEmpty()) {
                val (node, parentClickable) = queue.removeFirst()
                if (predicate(node, parentClickable)) return node
                val nodeClickable = node.isClickable
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to nodeClickable) }
                }
            }
            return null
        }
    }
}
