package com.leads365.apk.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.leads365.apk.ui.theme.Leads365Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var workManagerScheduler: dagger.Lazy<com.leads365.apk.work.WorkManagerScheduler>

    @javax.inject.Inject
    lateinit var callProcessingManager: dagger.Lazy<com.leads365.apk.calls.CallProcessingManager>

    @javax.inject.Inject
    lateinit var callerIdentificationManager: dagger.Lazy<com.leads365.apk.calls.CallerIdentificationManager>

    @javax.inject.Inject
    lateinit var authRepository: com.leads365.apk.data.repository.AuthRepository

    @javax.inject.Inject
    lateinit var fcmRegistrationManager: com.leads365.apk.notifications.FcmRegistrationManager

    @javax.inject.Inject
    lateinit var safRecordingSource: com.leads365.apk.recordings.SafRecordingSource

    @javax.inject.Inject
    lateinit var leadRepository: dagger.Lazy<com.leads365.apk.data.repository.LeadRepository>

    @javax.inject.Inject
    lateinit var callRepository: dagger.Lazy<com.leads365.apk.data.repository.CallRepository>

    private val viewModel: MainViewModel by viewModels()
    private val leadsViewModel: com.leads365.apk.ui.leads.LeadsViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[com.leads365.apk.ui.leads.LeadsViewModel::class.java]
    }

    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.setSafTreeUri(uri)
            safRecordingSource.notifyFolderUpdated()
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshState()
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val scheduler = workManagerScheduler.get()
            scheduler.schedulePeriodicContactsSync()
            scheduler.triggerOneTimeContactsSync(force = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupStartNs = android.os.SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fast main-thread setup: channels only
        com.leads365.apk.notifications.LeadsNotificationManager.initChannels(this)
        val channelsElapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - startupStartNs) / 1_000_000

        // Defer non-essential initialization off the main thread to ensure immediate UI presentation
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            fcmRegistrationManager.checkAndRegisterToken(applicationContext)

            // Only schedule and trigger authenticated background workers if logged in
            if (authRepository.isLoggedIn()) {
                com.leads365.apk.calls.CallSyncForegroundService.start(applicationContext)
                val scheduler = workManagerScheduler.get()
                scheduler.schedulePeriodicLeadSync()
                scheduler.triggerOneTimeLeadSync()
                scheduler.schedulePeriodicHeartbeat()
                scheduler.triggerOneTimeHeartbeat()
                scheduler.schedulePeriodicContactsSync()
                scheduler.triggerOneTimeContactsSync()
                try {
                    scheduler.resumePendingUploads()
                } catch (e: Exception) {
                    // Non-blocking background recovery
                }
            }
        }

        // Wire CallStateReceiver state transitions for Caller ID overlay/heads-up
        com.leads365.apk.calls.CallStateReceiver.onCallStateChangedListener = { stateStr, number ->
            when (stateStr) {
                android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
                android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    callerIdentificationManager.get().onCallStarted(applicationContext, number)
                }
                android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                    callerIdentificationManager.get().onCallEnded(applicationContext)
                }
            }
        }

        handleIncomingNotificationIntent(intent)

        lifecycleScope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is com.leads365.apk.data.repository.AuthState.Authenticated -> {
                        com.leads365.apk.calls.CallSyncForegroundService.start(applicationContext)
                        checkAndRequestNotificationPermissionGracefully()
                        checkAndRequestContactsPermissionGracefully()
                        checkAndPromptBatteryOptimizationGracefully()
                    }
                    is com.leads365.apk.data.repository.AuthState.Unauthenticated,
                    is com.leads365.apk.data.repository.AuthState.SessionRevoked -> {
                        com.leads365.apk.calls.CallSyncForegroundService.stop(applicationContext)
                    }
                    else -> {}
                }
            }
        }

        setContent {
            val compositionStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            Leads365Theme {
                com.leads365.apk.ui.navigation.MainAppShell(
                    viewModel = viewModel,
                    onRequestPermissions = { requestAppPermissions() },
                    onRequestDirectCallPermission = { requestDirectCallPermission() },
                    onRequestOverlayPermission = { openOverlaySettings() },
                    onRequestBatteryOptimization = { requestIgnoreBatteryOptimization() },
                    onOpenAppSettings = { openAppSettings() },
                    onPickSafFolder = { safPickerLauncher.launch(null) }
                )
            }
            androidx.compose.runtime.SideEffect {
                val compElapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - compositionStartNs) / 1_000_000
                val totalStartupMs = (android.os.SystemClock.elapsedRealtimeNanos() - startupStartNs) / 1_000_000
                android.util.Log.i("Leads365Startup", "First composition complete in ${compElapsedMs}ms (total cold start to first UI frame: ${totalStartupMs}ms, channels: ${channelsElapsedMs}ms)")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
        safRecordingSource.notifyFolderUpdated()
        // If user returns to the app during the test call step, check if a call just completed
        if (viewModel.uiState.value.currentStep == WizardStep.TEST_CALL &&
            viewModel.uiState.value.recentCallEvent == null
        ) {
            viewModel.checkForRecentCall()
        }

        // Asynchronously probe CallLog for recent calls matching assigned leads off the main thread
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val reader = com.leads365.apk.calls.CallLogReader(applicationContext)
                val recentCalls = reader.findRecentCalls(lookbackMs = 300_000L, queryLimit = 25)
                val processor = callProcessingManager.get()
                val repo = callRepository.get()

                for (call in recentCalls) {
                    val processResult = processor.processCallEvent(call)
                    val clientCallId = when (processResult) {
                        is com.leads365.apk.calls.CallProcessingResult.Recorded -> processResult.call.clientCallId
                        is com.leads365.apk.calls.CallProcessingResult.UnlinkedIncomingRecorded -> processResult.call.clientCallId
                        else -> null
                    }
                    if (!clientCallId.isNullOrBlank()) {
                        try {
                            repo.reportCallToBackend(clientCallId)
                        } catch (e: Exception) {
                            // Non-fatal, deferred
                        }
                    }
                }
            } catch (e: Exception) {
                // Non-blocking background scan
            }

            if (authRepository.isLoggedIn()) {
                try {
                    callRepository.get().syncPendingCallsToBackend()
                    workManagerScheduler.get().resumePendingUploads()
                } catch (e: Exception) {
                    // Non-blocking recovery
                }
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestDirectCallPermission() {
        permissionsLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Direct 1-tap system prompt requesting exemption
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    // Fallback to system battery optimization list
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    openAppSettings()
                }
            }
        }
    }

    private fun checkAndPromptBatteryOptimizationGracefully() {
        if (!authRepository.isLoggedIn()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
            if (!isIgnoring) {
                val prefs = getSharedPreferences("leads365_hardening_prefs", android.content.Context.MODE_PRIVATE)
                val prompted = prefs.getBoolean("battery_optimization_prompted", false)
                if (!prompted) {
                    prefs.edit().putBoolean("battery_optimization_prompted", true).apply()
                    requestIgnoreBatteryOptimization()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotificationIntent(intent)
    }

    private fun handleIncomingNotificationIntent(intent: Intent?) {
        if (intent == null) return
        if (!authRepository.isLoggedIn()) return

        val destination = intent.getStringExtra(com.leads365.apk.notifications.LeadsNotificationManager.EXTRA_DESTINATION)
            ?: if (intent.action == com.leads365.apk.notifications.LeadsNotificationManager.ACTION_VIEW_FOLLOW_UPS) "followups" else null

        if (!destination.isNullOrBlank()) {
            viewModel.setPendingDestination(destination)
        }

        val leadId = intent.getStringExtra(com.leads365.apk.notifications.LeadsNotificationManager.EXTRA_LEAD_ID)
        if (!leadId.isNullOrBlank()) {
            lifecycleScope.launch {
                val res = leadRepository.get().getLeadById(leadId)
                if (res is com.leads365.apk.core.AppResult.Success) {
                    res.data?.let { lead ->
                        leadsViewModel.selectLeadForDetail(lead)
                    }
                }
            }
        }
    }

    private fun checkAndRequestNotificationPermissionGracefully() {
        if (!authRepository.isLoggedIn()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                val prefs = getSharedPreferences("leads365_hardening_prefs", android.content.Context.MODE_PRIVATE)
                val prompted = prefs.getBoolean("post_notifications_prompted", false)
                if (!prompted) {
                    prefs.edit().putBoolean("post_notifications_prompted", true).apply()
                    permissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        }
    }

    private fun checkAndRequestContactsPermissionGracefully() {
        if (!authRepository.isLoggedIn()) return
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val scheduler = workManagerScheduler.get()
            scheduler.schedulePeriodicContactsSync()
            scheduler.triggerOneTimeContactsSync()
        } else {
            val prefs = getSharedPreferences(
                com.leads365.apk.contacts.PhoneContactsSyncManager.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            )
            val prompted = prefs.getBoolean(
                com.leads365.apk.contacts.PhoneContactsSyncManager.KEY_CONTACTS_PERMISSION_PROMPTED,
                false
            )
            if (!prompted) {
                prefs.edit().putBoolean(
                    com.leads365.apk.contacts.PhoneContactsSyncManager.KEY_CONTACTS_PERMISSION_PROMPTED,
                    true
                ).apply()
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if application details intent fails
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
