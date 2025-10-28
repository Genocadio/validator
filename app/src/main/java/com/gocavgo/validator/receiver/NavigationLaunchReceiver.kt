package com.gocavgo.validator.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gocavgo.validator.R
import com.gocavgo.validator.navigator.HeadlessNavigActivity

class NavigationLaunchReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NavigationLaunchReceiver"
        private const val CHANNEL_ID = "navigation_launch_channel"
        private const val CHANNEL_NAME = "Navigation Launch"
        private const val NOTIFICATION_ID = 1003
        private const val REQUEST_CODE = 1001
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val tripId = intent.getStringExtra("trip_id") ?: return
        val origin = intent.getStringExtra("origin") ?: "Unknown"
        val destination = intent.getStringExtra("destination") ?: "Unknown"
        
        Log.d(TAG, "=== NAVIGATION LAUNCH ALARM RECEIVED ===")
        Log.d(TAG, "Trip ID: $tripId")
        Log.d(TAG, "Route: $origin → $destination")
        
        try {
            // Create notification channel
            createNotificationChannel(context)
            
            // Create intent to launch navigation
            val navIntent = Intent(context, HeadlessNavigActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(HeadlessNavigActivity.EXTRA_TRIP_ID, tripId)
                putExtra(HeadlessNavigActivity.EXTRA_IS_SIMULATED, false)
            }
            
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    context, REQUEST_CODE, navIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    context, REQUEST_CODE, navIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            
            // Create full-screen high-priority notification
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Trip Starting Now")
                .setContentText("$origin → $destination")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .build()
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Full-screen notification posted, navigation will launch")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create launch notification: ${e.message}", e)
        }
    }
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows when navigation is about to start"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true) // Bypass Do Not Disturb for critical timing
                setShowBadge(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "Created navigation launch notification channel")
        }
    }
}
