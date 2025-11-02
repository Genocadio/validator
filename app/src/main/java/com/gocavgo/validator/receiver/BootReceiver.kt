package com.gocavgo.validator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.service.SettingsTimeoutWorker
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives BOOT_COMPLETED broadcast and starts AutoModeHeadlessActivity
 * Activity will listen for MQTT trips and handle autonomous navigation
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Logging.d(TAG, "=== BOOT COMPLETED ===")
            Logging.d(TAG, "Starting AutoModeHeadlessActivity")
            
            try {
                // Check if vehicle is registered before starting activity
                val vehicleSecurityManager = VehicleSecurityManager(context)
                if (!vehicleSecurityManager.isVehicleRegistered()) {
                    Logging.w(TAG, "Vehicle not registered, skipping auto mode start")
                    return
                }
                
                // Fetch settings from API on boot
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val settingsManager = VehicleSettingsManager.getInstance(context)
                
                // Use coroutine scope for async operations in BroadcastReceiver
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope.launch {
                    try {
                        Logging.d(TAG, "Fetching settings from API on boot for vehicle $vehicleId")
                        val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                        
                        result.onSuccess { settings ->
                            Logging.d(TAG, "Settings fetched successfully on boot")
                            // Apply settings (check logout/deactivate)
                            settingsManager.applySettings(context, settings)
                        }.onFailure { error ->
                            Logging.e(TAG, "Failed to fetch settings on boot: ${error.message}")
                            // Continue with saved settings if available
                            val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                            savedSettings?.let {
                                settingsManager.applySettings(context, it)
                            }
                        }
                    } catch (e: Exception) {
                        Logging.e(TAG, "Exception fetching settings on boot: ${e.message}", e)
                    }
                }
                
                // Schedule settings timeout worker (checks for 4-day timeout)
                SettingsTimeoutWorker.schedule(context)
                
                // Start AutoModeHeadlessActivity
                // It will listen for MQTT trips and handle navigation automatically
                val activityIntent = Intent(context, AutoModeHeadlessActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                context.startActivity(activityIntent)
                
                Logging.d(TAG, "AutoModeHeadlessActivity started after boot")
                
            } catch (e: IllegalStateException) {
                // SharedPreferences not available until device is unlocked
                // UserUnlockedReceiver will start the activity when device is unlocked
                Logging.w(TAG, "Device not unlocked yet, SharedPreferences unavailable. Will start auto mode after unlock: ${e.message}")
            } catch (e: Exception) {
                Logging.e(TAG, "Error starting auto mode after boot: ${e.message}", e)
            }
        }
    }
}

