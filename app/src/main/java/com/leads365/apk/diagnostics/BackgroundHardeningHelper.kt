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
        val deviceModel: String,
        val guidanceTitle: String,
        val actionableGuidance: String,
        val verifiedByApp: String,
        val requiresManualUserAction: String
    )

    fun evaluateBackgroundHardening(context: Context): HardeningAssessment {
        val isOptExempt = isBatteryOptimizationIgnored(context)
        val isRestricted = isBackgroundRestricted(context)
        val manufacturer = getManufacturer()
        val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

        val (guidance, manualSteps) = when (manufacturer) {
            Manufacturer.XIAOMI -> {
                Pair(
                    "Xiaomi/MIUI/HyperOS ($model): 1) Enable 'Autostart' in App Info. 2) Set Battery Saver to 'No restrictions'. 3) Enable Background Data.",
                    "Enable 'Autostart' and set Battery Saver to 'No restrictions' in MIUI/HyperOS settings."
                )
            }
            Manufacturer.SAMSUNG -> {
                Pair(
                    "Samsung One UI ($model): 1) Set Battery to 'Unrestricted' in App Info. 2) In Device Care > Battery > Background usage limits, ensure Leads365 is not in 'Sleeping apps' or 'Deep sleeping apps'.",
                    "Verify Leads365 is in 'Never sleeping apps' in Samsung Device Care."
                )
            }
            Manufacturer.OPPO_REALME -> {
                Pair(
                    "Oppo/Realme ColorOS ($model): 1) Enable 'Allow background activity' and 'Allow auto-launch' in Battery Settings. 2) Lock app in Recent Tasks.",
                    "Enable 'Auto-launch' and 'Allow background activity' in ColorOS settings."
                )
            }
            Manufacturer.VIVO -> {
                Pair(
                    "Vivo/iQOO Funtouch OS ($model): 1) Enable 'Autostart' in Permission Management. 2) Set 'High background power consumption' in Battery settings.",
                    "Enable 'Autostart' and 'High background power consumption' in Vivo Battery Management."
                )
            }
            Manufacturer.ONEPLUS -> {
                Pair(
                    "OnePlus OxygenOS ($model): 1) Set Battery Optimization to 'Don't optimize'. 2) Enable 'Allow background activity' in App Battery usage.",
                    "Set Battery Optimization to 'Don't optimize' in OxygenOS settings."
                )
            }
            Manufacturer.HUAWEI -> {
                Pair(
                    "Huawei/Honor ($model): In App launch, set Leads365 to 'Manage manually' and enable 'Auto-launch', 'Secondary launch', and 'Run in background'.",
                    "Set App launch to 'Manage manually' with background permissions enabled."
                )
            }
            Manufacturer.GENERIC -> {
                Pair(
                    "Standard Android ($model): Set App Battery Usage to 'Unrestricted' in App Info to prevent WorkManager delays during Doze.",
                    "Select 'Unrestricted' battery usage in App Info."
                )
            }
        }

        val verifiedStatus = if (isOptExempt && !isRestricted) {
            "Standard Android battery optimization exempt (verified via PowerManager)."
        } else if (isRestricted) {
            "Background execution is restricted by OS (verified via ActivityManager)."
        } else {
            "Standard Android battery optimization is active (may defer background sync)."
        }

        return HardeningAssessment(
            isOptExempt = isOptExempt,
            isRestricted = isRestricted,
            manufacturer = manufacturer,
            deviceModel = model,
            guidanceTitle = "$model Readiness",
            actionableGuidance = guidance,
            verifiedByApp = verifiedStatus,
            requiresManualUserAction = manualSteps
        )
    }

    fun createManufacturerSpecificIntent(context: Context): Intent? {
        val pm = context.packageManager
        val candidates = when (getManufacturer()) {
            Manufacturer.XIAOMI -> listOf(
                Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            )
            Manufacturer.VIVO -> listOf(
                Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            )
            Manufacturer.OPPO_REALME -> listOf(
                Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(android.content.ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            )
            Manufacturer.HUAWEI -> listOf(
                Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
            )
            else -> emptyList()
        }

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(pm) != null) {
                    return intent
                }
            } catch (e: Exception) {
                // Ignore resolution failure on non-supported versions
            }
        }
        return null
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
        // Try OEM specific intent first if available and verifiable
        val oemIntent = createManufacturerSpecificIntent(context)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (oemIntent != null) {
            try {
                context.startActivity(oemIntent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "OEM intent launch failed, falling back to standard battery settings", e)
            }
        }

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
