package com.gocavgo.validator.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based network monitoring for background reliability
 * Provides periodic network state checks when the app is backgrounded
 * or when the foreground service network monitoring might be unreliable
 */
class NetworkMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NetworkMonitorWorker"
        private const val WORK_NAME = "network_monitor_worker"
        private const val INTERVAL_MINUTES = 5L // Check every 5 minutes
        
        /**
         * Schedule periodic network monitoring
         */
        fun schedule(context: Context) {
            try {
                val workRequest = PeriodicWorkRequestBuilder<NetworkMonitorWorker>(
                    INTERVAL_MINUTES, TimeUnit.MINUTES
                )
                    .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                
                Log.d(TAG, "Network monitoring worker scheduled (every ${INTERVAL_MINUTES} minutes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule network monitoring worker: ${e.message}", e)
            }
        }
        
        /**
         * Cancel network monitoring worker
         */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Network monitoring worker cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel network monitoring worker: ${e.message}", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Network monitoring worker executing...")
            
            // Check if we have network permissions
            if (!NetworkUtils.hasNetworkPermissions(applicationContext)) {
                Log.w(TAG, "Network permissions not available, skipping check")
                return@withContext Result.success()
            }
            
            // Get current network state
            val isConnected = NetworkUtils.isConnectedToInternet(applicationContext)
            val isMetered = NetworkUtils.isConnectionMetered(applicationContext)
            val connectionType = getConnectionType()
            
            Log.d(TAG, "Background network check: connected=$isConnected, type=$connectionType, metered=$isMetered")
            
            // Store the network state for persistence
            storeNetworkState(isConnected, connectionType, isMetered)
            
            // Notify MQTT service if it's running
            notifyMqttService(isConnected, connectionType, isMetered)
            
            Log.d(TAG, "Network monitoring worker completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Network monitoring worker failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    private fun getConnectionType(): String {
        return try {
            val debugInfo = NetworkUtils.getDetailedNetworkInfo(applicationContext)
            when {
                debugInfo.transportTypes.contains("WIFI") -> "WIFI"
                debugInfo.transportTypes.contains("CELLULAR") -> {
                    if (debugInfo.cellularInfo.hasPermission && debugInfo.cellularInfo.isAvailable) {
                        "CELLULAR (${debugInfo.cellularInfo.networkType})"
                    } else {
                        "CELLULAR"
                    }
                }
                debugInfo.transportTypes.contains("ETHERNET") -> "ETHERNET"
                debugInfo.transportTypes.contains("BLUETOOTH") -> "BLUETOOTH"
                debugInfo.transportTypes.contains("VPN") -> "VPN"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine connection type: ${e.message}")
            "UNKNOWN"
        }
    }
    
    private fun storeNetworkState(connected: Boolean, type: String, metered: Boolean) {
        try {
            val prefs = applicationContext.getSharedPreferences("network_state", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_connected", connected)
                putString("connection_type", type)
                putBoolean("is_metered", metered)
                putLong("last_updated", System.currentTimeMillis())
                putString("source", "worker") // Mark as worker-updated
                apply()
            }
            Log.d(TAG, "Network state stored by worker: connected=$connected, type=$type, metered=$metered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store network state: ${e.message}", e)
        }
    }
    
    private fun notifyMqttService(connected: Boolean, type: String, metered: Boolean) {
        try {
            // Get MQTT service instance and notify of network change
            val mqttService = MqttService.getInstance()
            mqttService?.onNetworkStateChanged(connected, type, metered)
            
            // Also notify MQTT foreground service if running
            val foregroundService = MqttForegroundService.getInstance()
            if (foregroundService?.isServiceRunning() == true) {
                Log.d(TAG, "MQTT foreground service is running, network state updated")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify MQTT service: ${e.message}")
        }
    }
}
