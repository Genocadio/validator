package com.gocavgo.validator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.util.Logging

/**
 * Receives USER_UNLOCKED broadcast and starts AutoModeHeadlessActivity
 * This is needed because SharedPreferences are not available until after device unlock
 * BootReceiver may fail if device is still locked, so this receiver handles that case
 */
class UserUnlockedReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "UserUnlockedReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            Logging.d(TAG, "=== USER UNLOCKED ===")
            Logging.d(TAG, "Starting AutoModeHeadlessActivity")
            
            try {
                // Check if vehicle is registered before starting activity
                val vehicleSecurityManager = VehicleSecurityManager(context)
                if (!vehicleSecurityManager.isVehicleRegistered()) {
                    Logging.w(TAG, "Vehicle not registered, skipping auto mode start")
                    return
                }
                
                // Start AutoModeHeadlessActivity
                // It will listen for MQTT trips and handle navigation automatically
                val activityIntent = Intent(context, AutoModeHeadlessActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                context.startActivity(activityIntent)
                
                Logging.d(TAG, "AutoModeHeadlessActivity started after unlock")
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error starting auto mode after unlock: ${e.message}", e)
            }
        }
    }
}




