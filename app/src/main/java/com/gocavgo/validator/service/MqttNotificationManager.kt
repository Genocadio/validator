package com.gocavgo.validator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.text.toLowerCase
import androidx.core.app.NotificationCompat
import com.gocavgo.validator.R
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.dataclass.TripEventMessage
import com.gocavgo.validator.dataclass.TripData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification manager for MQTT events
 * Handles trip updates and booking notifications when app is in background
 */
class MqttNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "MqttNotificationManager"
        
        // Notification channels
        private const val TRIP_UPDATES_CHANNEL_ID = "trip_updates"
        private const val TRIP_UPDATES_CHANNEL_NAME = "Trip Updates"
        
        private const val BOOKING_UPDATES_CHANNEL_ID = "booking_updates"
        private const val BOOKING_UPDATES_CHANNEL_NAME = "Booking Updates"
        
        // Notification IDs
        private const val TRIP_UPDATE_NOTIFICATION_ID = 2001
        private const val BOOKING_UPDATE_NOTIFICATION_ID = 2002
        
        // Request codes for pending intents
        private const val TRIP_UPDATE_REQUEST_CODE = 1001
        private const val BOOKING_UPDATE_REQUEST_CODE = 1002
        private const val DISMISS_REQUEST_CODE = 1003
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannels()
    }
    
    /**
     * Create notification channels for different types of notifications
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Trip updates channel
            val tripChannel = NotificationChannel(
                TRIP_UPDATES_CHANNEL_ID,
                TRIP_UPDATES_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for trip assignments and updates"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            
            // Booking updates channel
            val bookingChannel = NotificationChannel(
                BOOKING_UPDATES_CHANNEL_ID,
                BOOKING_UPDATES_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for booking bundles and passenger updates"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            
            notificationManager.createNotificationChannel(tripChannel)
            notificationManager.createNotificationChannel(bookingChannel)
            
            Log.d(TAG, "Created notification channels")
        }
    }
    
    /**
     * Show notification for trip updates
     */
    fun showTripUpdateNotification(tripEvent: TripEventMessage) {
        try {
            Log.d(TAG, "Showing trip update notification for event: ${tripEvent.event}")
            
            val tripData = tripEvent.data
            val notification = createTripUpdateNotification(tripData, tripEvent.event)
            
            notificationManager.notify(TRIP_UPDATE_NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Trip update notification shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show trip update notification: ${e.message}", e)
        }
    }
    
    /**
     * Show notification for booking updates
     */
    fun showBookingUpdateNotification(
        tripId: String,
        passengerName: String,
        pickup: String,
        dropoff: String,
        numTickets: Int,
        isPaid: Boolean
    ) {
        try {
            Log.d(TAG, "Showing booking update notification for trip: $tripId")
            
            val notification = createBookingUpdateNotification(
                tripId, passengerName, pickup, dropoff, numTickets, isPaid
            )
            
            notificationManager.notify(BOOKING_UPDATE_NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Booking update notification shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show booking update notification: ${e.message}", e)
        }
    }
    
    /**
     * Create trip update notification
     */
    private fun createTripUpdateNotification(tripData: TripData, event: String): Notification {

        // Determine title based on trip status first, then fall back to event
        val title = when {
            // Check trip status for created/cancelled
            tripData.status.equals("scheduled", ignoreCase = true) -> "Trip Created"
            tripData.status.equals("cancelled", ignoreCase = true) -> "Trip Cancelled"
            // Fall back to event-based titles
            event == "trip_started" || event == "TRIP_STARTED" -> "Trip Started"
            event == "progress_update" || event == "PROGRESS_UPDATE" -> "Trip Progress Update"
            event == "trip_completed" || event == "TRIP_COMPLETED" -> "Trip Completed"
            event == "created" || event == "CREATED" -> "Trip Created"
            event == "TRIP_CANCELLED" || event == "trip_cancelled" -> "Trip Cancelled"
            else -> "Trip Update"
        }
        
        val origin = tripData.route.origin.google_place_name
        val destination = tripData.route.destination.google_place_name
        val departureTime = formatDepartureTime(tripData.departure_time)
        
        val contentText = "$origin → $destination"
        val contentSubText = if (departureTime.isNotEmpty()) "Departure: $departureTime" else null
        
        // Create intent to open AutoModeHeadlessActivity
        val intent = Intent(context, AutoModeHeadlessActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // AutoModeHeadlessActivity loads active trip from database, so trip_id not needed
            // but we can include it for reference if needed
            putExtra("trip_id", tripData.id)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, TRIP_UPDATE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create dismiss action
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("notification_id", TRIP_UPDATE_NOTIFICATION_ID)
        }
        
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, DISMISS_REQUEST_CODE, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(context, TRIP_UPDATES_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(contentSubText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        
        // Only add "View Trip" action if trip is not cancelled
        val isCancelled = tripData.status.equals("cancelled", ignoreCase = true) || 
                         event == "TRIP_CANCELLED" || event == "trip_cancelled"
        
        if (!isCancelled) {
            builder.addAction(
                R.drawable.ic_launcher_foreground,
                "View Trip",
                pendingIntent
            )
        }
        
        // Always add "Dismiss" action
        builder.addAction(
            R.drawable.ic_launcher_foreground,
            "Dismiss",
            dismissPendingIntent
        )
        
        return builder.build()
    }
    
    /**
     * Create booking update notification
     */
    private fun createBookingUpdateNotification(
        tripId: String,
        passengerName: String,
        pickup: String,
        dropoff: String,
        numTickets: Int,
        isPaid: Boolean
    ): Notification {
        val title = "New Booking Received"
        val contentText = "$passengerName: $pickup → $dropoff"
        val contentSubText = "$numTickets ticket${if (numTickets > 1) "s" else ""} • ${if (isPaid) "Paid" else "Pending Payment"}"
        
        // Create intent to open AutoModeHeadlessActivity
        val intent = Intent(context, AutoModeHeadlessActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, BOOKING_UPDATE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create dismiss action
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("notification_id", BOOKING_UPDATE_NOTIFICATION_ID)
        }
        
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, DISMISS_REQUEST_CODE, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, BOOKING_UPDATES_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(contentSubText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "View Booking",
                pendingIntent
            )
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Dismiss",
                dismissPendingIntent
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }
    
    /**
     * Format departure time for display
     */
    private fun formatDepartureTime(departureTime: Long): String {
        return try {
            if (departureTime <= 0) return ""
            
            // Convert timestamp to date and format it
            val date = Date(departureTime)
            val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            outputFormat.format(date)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to format departure time: $departureTime", e)
            ""
        }
    }
    
    /**
     * Cancel trip update notification
     */
    fun cancelTripUpdateNotification() {
        try {
            notificationManager.cancel(TRIP_UPDATE_NOTIFICATION_ID)
            Log.d(TAG, "Cancelled trip update notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel trip update notification: ${e.message}", e)
        }
    }
    
    /**
     * Cancel booking update notification
     */
    fun cancelBookingUpdateNotification() {
        try {
            notificationManager.cancel(BOOKING_UPDATE_NOTIFICATION_ID)
            Log.d(TAG, "Cancelled booking update notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel booking update notification: ${e.message}", e)
        }
    }
    
    /**
     * Cancel all notifications
     */
    fun cancelAllNotifications() {
        try {
            notificationManager.cancelAll()
            Log.d(TAG, "Cancelled all notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel all notifications: ${e.message}", e)
        }
    }
}

/**
 * Broadcast receiver to handle notification dismiss actions
 */
class NotificationDismissReceiver : android.content.BroadcastReceiver() {
    companion object {
        private const val TAG = "NotificationDismissReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val notificationId = intent.getIntExtra("notification_id", -1)
            if (notificationId != -1) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId)
                Log.d(TAG, "Dismissed notification with ID: $notificationId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss notification: ${e.message}", e)
        }
    }
}
