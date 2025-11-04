package com.gocavgo.validator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.util.Logging

/**
 * Receives USER_UNLOCKED broadcast and starts LauncherActivity
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
            Logging.d(TAG, "Starting LauncherActivity")
            
            try {
                // Check if vehicle is registered before starting activity
                val vehicleSecurityManager = VehicleSecurityManager(context)
                if (!vehicleSecurityManager.isVehicleRegistered()) {
                    Logging.w(TAG, "Vehicle not registered, skipping app start")
                    return
                }
                
                // Start LauncherActivity
                // It will handle permission checks and routing to appropriate activity
                val activityIntent = Intent(context, com.gocavgo.validator.LauncherActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                context.startActivity(activityIntent)
                
                Logging.d(TAG, "LauncherActivity started after unlock")
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error starting app after unlock: ${e.message}", e)
            }
        }
    }
}




