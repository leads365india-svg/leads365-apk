package com.leads365.apk.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Phase 12: Phone-maker & background hardening helper.
 * Provides OEM detection, battery optimization verification, background restriction checks,
 * actionable OEM guidance, and safe system settings intent builders without hidden APIs.
 */
object BackgroundHardeningHelper {

    private const val TAG = "BackgroundHardening"

    enum class Manufacturer {
        XIAOMI,
        SAMSUNG,
        OPPO_REALME,
        VIVO,
        ONEPLUS,
        HUAWEI,
        GENERIC
    }

    fun getManufacturer(): Manufacturer {
        val oem = Build.MANUFACTURER.lowercase()
        return when {
            oem.contains("xiaomi") || oem.contains("redmi") || oem.contains("poco") -> Manufacturer.XIAOMI
            oem.contains("samsung") -> Manufacturer.SAMSUNG
            oem.contains("oppo") || oem.contains("realme") -> Manufacturer.OPPO_REALME
            oem.contains("vivo") || oem.contains("iqoo") -> Manufacturer.VIVO
            oem.contains("oneplus") -> Manufacturer.ONEPLUS
            oem.contains("huawei") || oem.contains("honor") -> Manufacturer.HUAWEI
            else -> Manufacturer.GENERIC
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = try {
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            } catch (e: Exception) {
                null
            }
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activityManager = try {
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            } catch (e: Exception) {
                null
            }
            activityManager?.isBackgroundRestricted ?: false
        } else {
            false
        }
    }

    data class HardeningAssessment(
        val isOptExempt: Boolean,
        val isRestricted: Boolean,
        val manufacturer: Manufacturer,
        val guidanceTitle: String,
        val actionableGuidance: String
    )

    fun evaluateBackgroundHardening(context: Context): HardeningAssessment {
        val isOptExempt = isBatteryOptimizationIgnored(context)
        val isRestricted = isBackgroundRestricted(context)
        val manufacturer = getManufacturer()

        val guidance = when (manufacturer) {
            Manufacturer.XIAOMI -> {
                "Xiaomi/MIUI detected: 1) Enable 'Autostart' in App Info. 2) Set Battery Saver to 'No restrictions'."
            }
            Manufacturer.SAMSUNG -> {
                "Samsung One UI detected: 1) Set App Battery to 'Unrestricted'. 2) Remove Leads365 from 'Sleeping apps' in Device Care."
            }
            Manufacturer.OPPO_REALME -> {
                "Oppo/Realme detected: Enable 'Allow background activity' and 'Auto-launch' in Battery Settings."
            }
            Manufacturer.VIVO -> {
                "Vivo detected: Enable 'Autostart' and allow 'High background power consumption' in Battery Management."
            }
            Manufacturer.ONEPLUS -> {
                "OnePlus detected: Set Battery Optimization to 'Don't optimize' in Advanced Battery Settings."
            }
            Manufacturer.HUAWEI -> {
                "Huawei/Honor detected: In 'App launch', set Leads365 to 'Manage manually' and enable 'Run in background'."
            }
            Manufacturer.GENERIC -> {
                "Standard Android: Set App Battery Usage to 'Unrestricted' in App Info to prevent background worker delays."
            }
        }

        return HardeningAssessment(
            isOptExempt = isOptExempt,
            isRestricted = isRestricted,
            manufacturer = manufacturer,
            guidanceTitle = "${manufacturer.name} Background Readiness",
            actionableGuidance = guidance
        )
    }

    fun createBatteryOptimizationIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (listIntent.resolveActivity(context.packageManager) != null) {
                return listIntent
            }
        }
        return createAppDetailsIntent(context)
    }

    fun createAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    fun launchSafeSystemSettings(context: Context): Boolean {
        val intent = createBatteryOptimizationIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch battery optimization settings; falling back to app details", e)
            try {
                val appDetails = createAppDetailsIntent(context).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appDetails)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch app details settings", e2)
                false
            }
        }
    }
}
