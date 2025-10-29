package com.gocavgo.validator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.util.Logging

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
                
                // Start AutoModeHeadlessActivity
                // It will listen for MQTT trips and handle navigation automatically
                val activityIntent = Intent(context, AutoModeHeadlessActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                context.startActivity(activityIntent)
                
                Logging.d(TAG, "AutoModeHeadlessActivity started after boot")
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error starting auto mode after boot: ${e.message}", e)
            }
        }
    }
}

