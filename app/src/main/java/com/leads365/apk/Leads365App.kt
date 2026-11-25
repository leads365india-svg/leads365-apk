package com.leads365.apk

import android.app.Application
import android.util.Log
import com.leads365.apk.calls.CallSyncForegroundService
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.network.NetworkConnectivityMonitor
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class Leads365App : Application() {

    @Inject
    lateinit var networkConnectivityMonitor: dagger.Lazy<NetworkConnectivityMonitor>

    @Inject
    lateinit var authRepository: dagger.Lazy<AuthRepository>

    @Inject
    lateinit var workManagerScheduler: dagger.Lazy<WorkManagerScheduler>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Defer heavy Hilt Lazy resolution (SQLCipher/Room/Keystore/WorkManager) off the Main Thread
        appScope.launch {
            try {
                networkConnectivityMonitor.get().startMonitoring()
            } catch (e: Exception) {
                Log.w("Leads365App", "Failed to start network connectivity monitor: ${e.message}")
            }

            try {
                if (authRepository.get().isLoggedIn()) {
                    workManagerScheduler.get().schedulePeriodicLeadSync()
                    CallSyncForegroundService.start(this@Leads365App)
                }
            } catch (e: Exception) {
                Log.w("Leads365App", "Failed to initialize background sync on app start: ${e.message}")
            }
        }
    }
}
