package com.gocavgo.validator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gocavgo.validator.R
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.receiver.TripConfirmationReceiver
import com.gocavgo.validator.util.Logging
import java.text.SimpleDateFormat
import java.util.*

/**
 * Minimal foreground service that keeps AutoModeHeadlessActivity alive in background
 * All trip logic, MQTT listening, and navigation is handled by the activity itself
 */
class AutoModeHeadlessForegroundService : Service() {
    
    companion object {
        private const val TAG = "AutoModeHeadlessForegroundService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "automode_headless_channel"
        private const val CHANNEL_NAME = "Auto Mode"
        
        // Confirmation notification
        private const val CONFIRMATION_NOTIFICATION_ID = 1004
        private const val CONFIRMATION_CHANNEL_ID = "trip_confirmation_channel"
        private const val CONFIRMATION_CHANNEL_NAME = "Trip Confirmation"
    }
    
    private val binder = LocalBinder()
    private var notificationMessage = "Auto Mode Active"
    
    inner class LocalBinder : Binder() {
        fun getService(): AutoModeHeadlessForegroundService = this@AutoModeHeadlessForegroundService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Logging.d(TAG, "Service bound")
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Logging.d(TAG, "AutoModeHeadlessForegroundService created")
        
        createNotificationChannel()
        
        // Start foreground immediately
        val notification = createNotification("Auto Mode Active")
        startForeground(NOTIFICATION_ID, notification)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logging.d(TAG, "Service started")
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop foreground service and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Logging.d(TAG, "AutoModeHeadlessForegroundService destroyed")
    }
    
    /**
     * Update the foreground notification with new message
     * Called by AutoModeHeadlessActivity to reflect current state
     */
    fun updateNotification(message: String) {
        notificationMessage = message
        val notification = createNotification(message)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
        Logging.d(TAG, "Notification updated: $message")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Auto Mode status channel (low priority)
            val statusChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Auto Mode status and keeps activity alive in background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            // Trip confirmation channel (high priority)
            val confirmationChannel = NotificationChannel(
                CONFIRMATION_CHANNEL_ID,
                CONFIRMATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for trip departure time confirmations"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(statusChannel)
            notificationManager.createNotificationChannel(confirmationChannel)
            
            Logging.d(TAG, "Created notification channels: $CHANNEL_ID, $CONFIRMATION_CHANNEL_ID")
        }
    }
    
    private fun createNotification(message: String): Notification {
        // Create intent to open AutoModeHeadlessActivity when notification is tapped
        val intent = Intent(this, AutoModeHeadlessActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GoCavGo Auto Mode")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    /**
     * Show confirmation notification for late trip
     */
    fun showConfirmationNotification(trip: TripResponse, delayMinutes: Int) {
        try {
            val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
            val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
            
            // Format times
            val expectedTime = formatTime(trip.departure_time * 1000)
            val currentTime = formatTime(System.currentTimeMillis())
            
            // Build big text with detailed information
            val delayText = if (delayMinutes >= 60) {
                "${delayMinutes / 60}h ${delayMinutes % 60}min late"
            } else {
                "$delayMinutes minutes late"
            }
            
            val bigText = """
                Route: $origin → $destination
                
                Expected Departure: $expectedTime
                Current Time: $currentTime
                Delay: $delayText
                
                Navigation will auto-start in 3 minutes if no action is taken.
            """.trimIndent()
            
            // Create "Start Navigation" action
            val startIntent = Intent(this, TripConfirmationReceiver::class.java).apply {
                action = TripConfirmationReceiver.ACTION_START_NAVIGATION
            }
            val startPendingIntent = PendingIntent.getBroadcast(
                this, 0, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Create "Cancel Trip" action
            val cancelIntent = Intent(this, TripConfirmationReceiver::class.java).apply {
                action = TripConfirmationReceiver.ACTION_CANCEL_TRIP
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                this, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Create intent to open AutoModeHeadlessActivity when notification is tapped
            val activityIntent = Intent(this, AutoModeHeadlessActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val activityPendingIntent = PendingIntent.getActivity(
                this, 2, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(this, CONFIRMATION_CHANNEL_ID)
                .setContentTitle("Trip Departure Time Passed")
                .setContentText("$origin → $destination")
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(activityPendingIntent)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Start Navigation",
                    startPendingIntent
                )
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Cancel Trip",
                    cancelPendingIntent
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(CONFIRMATION_NOTIFICATION_ID, notification)
            
            Logging.d(TAG, "Confirmation notification shown for trip ${trip.id}, delay: $delayMinutes min")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to show confirmation notification: ${e.message}", e)
        }
    }
    
    /**
     * Cancel confirmation notification
     */
    fun cancelConfirmationNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(CONFIRMATION_NOTIFICATION_ID)
            Logging.d(TAG, "Confirmation notification cancelled")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to cancel confirmation notification: ${e.message}", e)
        }
    }
    
    private fun formatTime(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}

