package com.gocavgo.validator.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.util.Log

/**
 * Real-time network connectivity monitor that tracks internet availability,
 * connection type, and metered status changes
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkChanged: (isConnected: Boolean, connectionType: String, isMetered: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        initializeWakeLock()
    }
    
    private fun initializeWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GoCavGo:NetworkMonitor"
            )
            Log.d(TAG, "Wake lock initialized for network monitoring")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wake lock: ${e.message}", e)
        }
    }

    fun startMonitoring() {
        if (isMonitoring || connectivityManager == null) {
            Log.w(TAG, "Network monitoring already started or ConnectivityManager unavailable")
            return
        }

        Log.d(TAG, "Starting network monitoring...")

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available: $network")
                checkAndReportNetworkState()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                checkAndReportNetworkState()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "Network capabilities changed: $network")
                checkAndReportNetworkState()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                Log.d(TAG, "Link properties changed: $network")
                checkAndReportNetworkState()
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
        isMonitoring = true

        // Initial state check
        checkAndReportNetworkState()

        Log.d(TAG, "Network monitoring started successfully")
    }

    fun stopMonitoring() {
        if (!isMonitoring || networkCallback == null) {
            return
        }

        Log.d(TAG, "Stopping network monitoring...")

        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback!!)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered: ${e.message}")
        }

        networkCallback = null
        isMonitoring = false

        Log.d(TAG, "Network monitoring stopped")
    }

    private fun checkAndReportNetworkState() {
        if (!NetworkUtils.hasNetworkPermissions(context)) {
            Log.w(TAG, "Cannot check network state - permissions not granted")
            onNetworkChanged(false, "UNKNOWN", true)
            return
        }

        // Acquire wake lock for critical network operations
        acquireWakeLock()

        try {
            val isConnected = NetworkUtils.isConnectedToInternet(context)
            val isMetered = NetworkUtils.isConnectionMetered(context)
            val connectionType = getConnectionType()

            Log.d(TAG, "Network state: Connected=$isConnected, Type=$connectionType, Metered=$isMetered")

            onNetworkChanged(isConnected, connectionType, isMetered)
        } finally {
            // Always release wake lock
            releaseWakeLock()
        }
    }

    private fun getConnectionType(): String {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                ?: return "UNKNOWN"

            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                ?: return "NONE"

            return when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    getCellularNetworkType()
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connection type: ${e.message}")
            return "UNKNOWN"
        }
    }

    private fun getCellularNetworkType(): String {
        return try {
            if (NetworkUtils.hasNetworkPermissions(context)) {
                val debugInfo = NetworkUtils.getDetailedNetworkInfo(context)
                if (debugInfo.cellularInfo.hasPermission && debugInfo.cellularInfo.isAvailable) {
                    "CELLULAR (${debugInfo.cellularInfo.networkType})"
                } else {
                    "CELLULAR"
                }
            } else {
                "CELLULAR"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get detailed cellular info: ${e.message}")
            "CELLULAR"
        }
    }

    /**
     * Get current network state without starting monitoring
     */
    fun getCurrentNetworkState(): NetworkState {
        if (!NetworkUtils.hasNetworkPermissions(context)) {
            return NetworkState(false, "UNKNOWN", true)
        }

        val isConnected = NetworkUtils.isConnectedToInternet(context)
        val isMetered = NetworkUtils.isConnectionMetered(context)
        val connectionType = getConnectionType()

        return NetworkState(isConnected, connectionType, isMetered)
    }

    data class NetworkState(
        val isConnected: Boolean,
        val connectionType: String,
        val isMetered: Boolean
    )

    fun isCurrentlyMonitoring(): Boolean {
        return isMonitoring
    }
    
    /**
     * Acquire wake lock for critical network operations
     */
    private fun acquireWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (!lock.isHeld) {
                    lock.acquire(30 * 1000L) // 30 seconds timeout
                    Log.d(TAG, "Wake lock acquired for network operation")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
        }
    }
    
    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock: ${e.message}", e)
        }
    }
}