package com.gocavgo.validator.navigator

import android.content.Context
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.trip.ActiveTripListener
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.engine.SDKNativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages network state using StateFlow.
 * Handles network monitoring and HERE SDK offline mode.
 */
data class NetworkState(
    val isConnected: Boolean = true,
    val connectionType: String = "UNKNOWN",
    val isMetered: Boolean = true,
    val offlineTime: Long = 0
)

class NetworkStateManager(private val context: Context) {
    companion object {
        private const val TAG = "NetworkStateManager"
    }

    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var networkMonitor: NetworkMonitor? = null
    private val networkOfflineTime = AtomicLong(0)
    private var pendingOfflineMode: Boolean? = null

    // Callbacks
    var onNetworkLost: (() -> Unit)? = null
    var onNetworkRestored: ((Boolean, TripResponse?) -> Unit)? = null
    var onMapDownloaderNetworkAvailable: (() -> Unit)? = null

    /**
     * Initialize network monitoring
     */
    fun initialize() {
        Logging.d(TAG, "Initializing network monitoring...")

        if (!NetworkUtils.hasNetworkPermissions(context)) {
            Logging.w(TAG, "Network permissions not available, using basic monitoring")
            return
        }

        networkMonitor = NetworkMonitor(context) { connected, type, metered ->
            Logging.d(TAG, "Network state changed: connected=$connected, type=$type, metered=$metered")

            val currentState = _state.value
            _state.value = currentState.copy(
                isConnected = connected,
                connectionType = type,
                isMetered = metered
            )

            // Update HERE SDK offline mode
            updateOfflineMode(connected)

            // Notify map downloader
            if (connected) {
                onMapDownloaderNetworkAvailable?.invoke()
            }

            // Handle network state changes
            if (connected) {
                handleNetworkRestored()
            } else {
                handleNetworkLost()
            }
        }

        networkMonitor?.startMonitoring()
        Logging.d(TAG, "Network monitoring started")
    }

    /**
     * Handle network lost
     */
    private fun handleNetworkLost() {
        val offlineTime = System.currentTimeMillis()
        networkOfflineTime.set(offlineTime)
        _state.value = _state.value.copy(offlineTime = offlineTime)
        Logging.d(TAG, "Network lost at: $offlineTime")
        onNetworkLost?.invoke()
    }

    /**
     * Handle network restored
     */
    private fun handleNetworkRestored() {
        val offlineTime = networkOfflineTime.get()
        if (offlineTime == 0L) return

        val offlineDurationMs = System.currentTimeMillis() - offlineTime
        val offlineMinutes = offlineDurationMs / (60 * 1000)

        Logging.d(TAG, "=== NETWORK RESTORED ===")
        Logging.d(TAG, "Offline duration: $offlineMinutes minutes")

        networkOfflineTime.set(0)
        _state.value = _state.value.copy(offlineTime = 0)

        val currentState = _state.value
        onNetworkRestored?.invoke(currentState.isConnected, null) // Trip will be passed by caller
    }

    /**
     * Update HERE SDK offline mode
     */
    fun updateOfflineMode(isConnected: Boolean) {
        try {
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine == null) {
                // SDK not initialized yet, store for later
                pendingOfflineMode = !isConnected
                Logging.d(TAG, "HERE SDK not initialized, storing offline mode: ${!isConnected}")
                return
            }

            val offlineMode = !isConnected
            sdkNativeEngine.setOfflineMode(offlineMode)
            Logging.d(TAG, "HERE SDK offline mode updated: $offlineMode")
            pendingOfflineMode = null
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to update HERE SDK offline mode: ${e.message}", e)
        }
    }

    /**
     * Apply pending offline mode (called after SDK initialization)
     */
    fun applyPendingOfflineMode() {
        pendingOfflineMode?.let { offlineMode ->
            try {
                SDKNativeEngine.getSharedInstance()?.setOfflineMode(offlineMode)
                Logging.d(TAG, "Applied pending HERE SDK offline mode: $offlineMode")
                pendingOfflineMode = null
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to apply pending offline mode: ${e.message}", e)
            }
        }
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        Logging.d(TAG, "Network monitoring stopped")
    }

    /**
     * Get current state
     */
    fun getState(): NetworkState = _state.value
}
