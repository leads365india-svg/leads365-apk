package com.leads365.apk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.work.RecordingUploadWorker
import com.leads365.apk.work.WorkManagerScheduler
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class NetworkConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManagerScheduler: Lazy<WorkManagerScheduler>,
    private val authRepository: Lazy<AuthRepository>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isWifiConnected = MutableStateFlow(false)
    open val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    open val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false

    open fun startMonitoring() {
        if (isMonitoring) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        // Evaluate active network immediately on startup
        checkCurrentNetwork(cm)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                updateNetworkState(capabilities)
                onNetworkAvailable(capabilities)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateNetworkState(capabilities)
                onNetworkAvailable(capabilities)
            }

            override fun onLost(network: Network) {
                checkCurrentNetwork(cm)
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            isMonitoring = true
            Log.i(TAG, "NetworkConnectivityMonitor registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    open fun stopMonitoring() {
        if (!isMonitoring) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister NetworkCallback: ${e.message}")
            }
        }
        networkCallback = null
        isMonitoring = false
    }

    private fun checkCurrentNetwork(cm: ConnectivityManager) {
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        updateNetworkState(capabilities)
    }

    private fun updateNetworkState(capabilities: NetworkCapabilities?) {
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifi = capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        _isConnected.value = connected
        _isWifiConnected.value = wifi
    }

    private fun onNetworkAvailable(capabilities: NetworkCapabilities?) {
        if (capabilities == null) return
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return

        val isUnmeteredWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        val wifiOnly = prefs.getBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false)

        val eligibleForUpload = if (wifiOnly) isUnmeteredWifi else true

        if (eligibleForUpload) {
            scope.launch {
                try {
                    val auth = authRepository.get()
                    if (auth.isLoggedIn()) {
                        Log.i(TAG, "Network transition to suitable connection (wifiOnly=$wifiOnly, isWifi=$isUnmeteredWifi). Resuming pending uploads.")
                        workManagerScheduler.get().resumePendingUploads()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error resuming pending uploads during network transition: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
