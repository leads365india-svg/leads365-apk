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

/**
 * Helper utilities for checking and opening opt-in WhatsApp VoIP call
 * recording permissions (Microphone + Accessibility Service + Android 13+
 * Restricted Settings).
 */
object WhatsAppAccessibilityHelper {

    private const val PREFS_NAME = "com.leads365.apk.preferences"
    private const val KEY_WHATSAPP_A11Y_EVER_ENABLED = "pref_whatsapp_a11y_ever_enabled"

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
}
