package com.leads365.apk.calls

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper utilities for checking and opening opt-in WhatsApp VoIP call
 * recording permissions (Microphone + Accessibility Service + Android 13+
 * Restricted Settings).
 */
object WhatsAppAccessibilityHelper {

    private const val PREFS_NAME = "com.leads365.apk.preferences"
    private const val KEY_WHATSAPP_A11Y_EVER_ENABLED = "pref_whatsapp_a11y_ever_enabled"
    private const val KEY_WHATSAPP_DIAG_LOG = "pref_whatsapp_diag_log"
    private const val MAX_DIAG_EVENTS = 12
    private val diagLock = Any()

    /**
     * Safely checks whether [WhatsAppCallAccessibilityService] is enabled in
     * Android's Accessibility settings.
     *
     * Whenever it returns `true`, persists that it was previously enabled so
     * future sideload updates that revoke Accessibility access can be surfaced.
     */
    fun isWhatsAppAccessibilityEnabled(context: Context): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            if (enabledServices.isBlank()) return false

            val expectedComponent = ComponentName(context, WhatsAppCallAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val entry = splitter.next().trim()
                if (entry.isEmpty()) continue
                val candidate = ComponentName.unflattenFromString(entry)
                if (candidate == expectedComponent) {
                    markWhatsAppPreviouslyEnabled(context)
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether runtime [Manifest.permission.RECORD_AUDIO] is granted.
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Opens system Accessibility Settings so the user can toggle
     * WhatsApp Call Assistant.
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
            openAppInfoSettings(context)
        }
    }

    /**
     * Opens this app's App Info screen so users on Android 13-16 can tap the
     * overflow menu and select "Allow restricted settings" before enabling
     * Accessibility.
     */
    fun openAppInfoSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    /**
     * Returns whether the WhatsApp Accessibility Service has previously been
     * observed as enabled on this installation.
     */
    fun wasWhatsAppPreviouslyEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WHATSAPP_A11Y_EVER_ENABLED, false)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Records that WhatsApp Accessibility Service was enabled on this installation.
     */
    fun markWhatsAppPreviouslyEnabled(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WHATSAPP_A11Y_EVER_ENABLED, true)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * Appends a timestamped (`HH:mm:ss`) WhatsApp diagnostic event, keeping the most recent 12 entries.
     */
    fun appendDiagEvent(context: Context, message: String) {
        synchronized(diagLock) {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val cleanMessage = message.replace(Regex("[\\r\\n]+"), " ").trim()
                val entry = "$timestamp $cleanMessage"
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existing = prefs.getString(KEY_WHATSAPP_DIAG_LOG, "")
                    .orEmpty()
                    .split('\n')
                    .filter { it.isNotBlank() }
                    .toMutableList()
                existing.add(entry)
                val trimmed = if (existing.size > MAX_DIAG_EVENTS) {
                    existing.takeLast(MAX_DIAG_EVENTS)
                } else {
                    existing
                }
                prefs.edit()
                    .putString(KEY_WHATSAPP_DIAG_LOG, trimmed.joinToString("\n"))
                    .apply()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Returns the stored WhatsApp diagnostic log entries (up to the last 12).
     */
    fun getDiagEvents(context: Context): List<String> {
        synchronized(diagLock) {
            return try {
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_WHATSAPP_DIAG_LOG, "")
                    .orEmpty()
                if (raw.isBlank()) {
                    emptyList()
                } else {
                    raw.split('\n').filter { it.isNotBlank() }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
