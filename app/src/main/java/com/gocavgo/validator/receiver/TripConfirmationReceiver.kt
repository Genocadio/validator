package com.gocavgo.validator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gocavgo.validator.util.Logging

/**
 * BroadcastReceiver to handle trip confirmation notification action buttons
 * Relays user decision (start/cancel) to AutoModeHeadlessActivity
 */
class TripConfirmationReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "TripConfirmationReceiver"
        
        // Actions for notification buttons
        const val ACTION_START_NAVIGATION = "com.gocavgo.validator.ACTION_START_NAVIGATION"
        const val ACTION_CANCEL_TRIP = "com.gocavgo.validator.ACTION_CANCEL_TRIP"
        
        // Actions to broadcast to activity
        const val ACTION_START_CONFIRMED = "com.gocavgo.validator.ACTION_START_CONFIRMED"
        const val ACTION_CANCEL_CONFIRMED = "com.gocavgo.validator.ACTION_CANCEL_CONFIRMED"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        Logging.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            ACTION_START_NAVIGATION -> {
                Logging.d(TAG, "User confirmed trip start via notification")
                // Broadcast to activity - make it explicit by setting package
                val activityIntent = Intent(ACTION_START_CONFIRMED).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(activityIntent)
                Logging.d(TAG, "Broadcast sent: $ACTION_START_CONFIRMED")
            }
            ACTION_CANCEL_TRIP -> {
                Logging.d(TAG, "User cancelled trip via notification")
                // Broadcast to activity - make it explicit by setting package
                val activityIntent = Intent(ACTION_CANCEL_CONFIRMED).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(activityIntent)
                Logging.d(TAG, "Broadcast sent: $ACTION_CANCEL_CONFIRMED")
            }
        }
    }
}

