package com.gocavgo.validator.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gocavgo.validator.AutoModeActivity
import com.gocavgo.validator.R
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.navigator.HeadlessNavigActivity
import com.gocavgo.validator.receiver.NavigationLaunchReceiver
import com.gocavgo.validator.util.Logging
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Foreground service for keeping AutoModeActivity alive in background
 * Ensures autonomous trip monitoring continues even when app is minimized
 */
class AutoModeForegroundService : Service() {
    
    companion object {
        private const val TAG = "AutoModeForegroundService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "automode_service_channel"
        private const val CHANNEL_NAME = "Auto Mode Service"
        
        const val ACTION_START_SERVICE = "com.gocavgo.validator.START_AUTO_MODE_SERVICE"
        const val ACTION_STOP_SERVICE = "com.gocavgo.validator.STOP_AUTO_MODE_SERVICE"
        
        private const val PREF_NAME = "automode_service_prefs"
        private const val KEY_SCHEDULED_TRIP = "scheduled_trip"
        private const val KEY_LAUNCH_TIME = "launch_time"
        private const val LAUNCH_REQUEST_CODE = 1001
        private const val COUNTDOWN_UPDATE_INTERVAL_MS = 1000L
        
        @Volatile
        private var INSTANCE: AutoModeForegroundService? = null
        
        fun getInstance(): AutoModeForegroundService? = INSTANCE
    }
    
    private val binder = AutoModeServiceBinder()
    private var tripState: String = "Waiting for trip..."
    private var countdownText: String = ""
    
    // AlarmManager for scheduling launch
    private var alarmManager: AlarmManager? = null
    private var countdownJob: Job? = null
    private var launchTime: Long = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    inner class AutoModeServiceBinder : Binder() {
        fun getService(): AutoModeForegroundService = this@AutoModeForegroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        Logging.d(TAG, "AutoModeForegroundService created")
        
        createNotificationChannel()
        
        // Initialize AlarmManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Try to restore scheduled trip if service was restarted
        restoreScheduledTrip()
        
        INSTANCE = this
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logging.d(TAG, "AutoModeForegroundService started with action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForegroundService()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
            else -> {
                startForegroundService()
            }
        }
        
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onDestroy() {
        Logging.d(TAG, "AutoModeForegroundService destroyed")
        
        // Cancel countdown job
        countdownJob?.cancel()
        
        // Cancel service scope
        serviceScope.cancel()
        
        INSTANCE = null
        super.onDestroy()
    }
    
    /**
     * Start the foreground service with persistent notification
     */
    private fun startForegroundService() {
        try {
            val notification = createForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "AutoMode foreground service started with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AutoMode foreground service: ${e.message}", e)
        }
    }
    
    /**
     * Stop the foreground service
     */
    private fun stopForegroundService() {
        Logging.d(TAG, "Stopping AutoMode foreground service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
    
    /**
     * Create notification channel for AutoMode service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Auto Mode trip monitoring status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "Created AutoMode notification channel: $CHANNEL_ID")
        }
    }
    
    /**
     * Create foreground notification
     */
    private fun createForegroundNotification(): Notification {
        val statusText = if (countdownText.isNotEmpty()) {
            "$tripState\n$countdownText"
        } else {
            tripState
        }
        
        // Create intent to open AutoModeActivity when notification is tapped
        val mainIntent = Intent(this, AutoModeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GoCavGo Auto Mode")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    /**
     * Update notification with current trip state and countdown
     */
    fun updateNotification(state: String, countdown: String = "") {
        try {
            tripState = state
            countdownText = countdown
            
            val notification = createForegroundNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Logging.d(TAG, "Updated AutoMode notification: $state")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to update notification: ${e.message}", e)
        }
    }
    
    /**
     * Schedule navigation launch for a trip
     */
    fun scheduleNavigationLaunch(trip: TripResponse) {
        Logging.d(TAG, "=== SCHEDULING NAVIGATION LAUNCH ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        
        val launchTimeMillis = trip.departure_time - (2 * 60 * 1000) // 2 minutes before departure
        val currentTime = System.currentTimeMillis()
        
        Logging.d(TAG, "Current time: $currentTime")
        Logging.d(TAG, "Launch time: $launchTimeMillis")
        Logging.d(TAG, "Delay: ${launchTimeMillis - currentTime}ms")
        
        if (launchTimeMillis <= currentTime) {
            Logging.w(TAG, "Launch time has passed, launching immediately")
            launchNavigation(trip)
            return
        }
        
        // Save trip for persistence
        saveScheduledTrip(trip, launchTimeMillis)
        
        // Schedule AlarmManager
        try {
            val intent = Intent(this, NavigationLaunchReceiver::class.java).apply {
                putExtra("trip_id", trip.id)
                putExtra("origin", trip.route.origin.google_place_name)
                putExtra("destination", trip.route.destination.google_place_name)
            }
            
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getBroadcast(
                    this, LAUNCH_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    this, LAUNCH_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            
            // Use setExactAndAllowWhileIdle for Android 6+ to ensure alarm fires even in doze mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    launchTimeMillis,
                    pendingIntent
                )
            } else {
                alarmManager?.setExact(
                    AlarmManager.RTC_WAKEUP,
                    launchTimeMillis,
                    pendingIntent
                )
            }
            
            launchTime = launchTimeMillis
            startCountdownUpdates(launchTimeMillis)
            
            Logging.d(TAG, "Navigation launch scheduled successfully")
            
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to schedule navigation launch: ${e.message}", e)
        }
    }
    
    /**
     * Launch navigation immediately
     */
    private fun launchNavigation(trip: TripResponse) {
        Logging.d(TAG, "=== LAUNCHING NAVIGATION ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        
        try {
            val intent = Intent(this, HeadlessNavigActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(HeadlessNavigActivity.EXTRA_TRIP_ID, trip.id)
                putExtra(HeadlessNavigActivity.EXTRA_IS_SIMULATED, false)
            }
            
            startActivity(intent)
            
            updateNotification("Navigation active: ${trip.route.origin.google_place_name} → ${trip.route.destination.google_place_name}")
            
            Logging.d(TAG, "Navigation launched successfully")
            
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to launch navigation: ${e.message}", e)
        }
    }
    
    /**
     * Cancel scheduled launch
     */
    fun cancelScheduledLaunch() {
        Logging.d(TAG, "Cancelling scheduled navigation launch")
        
        try {
            val intent = Intent(this, NavigationLaunchReceiver::class.java)
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getBroadcast(
                    this, LAUNCH_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    this, LAUNCH_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            
            alarmManager?.cancel(pendingIntent)
            
            // Clear stored data
            prefs.edit().clear().apply()
            
            countdownJob?.cancel()
            launchTime = 0
            
            Logging.d(TAG, "Scheduled launch cancelled")
            
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to cancel scheduled launch: ${e.message}", e)
        }
    }
    
    /**
     * Start countdown updates
     */
    private fun startCountdownUpdates(launchTimeMillis: Long) {
        countdownJob?.cancel()
        
        countdownJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                val remainingMillis = launchTimeMillis - currentTime
                
                if (remainingMillis <= 0) {
                    break
                }
                
                val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMillis).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val countdownTextFormatted = "Auto-launch in: %02d:%02d".format(minutes, seconds)
                
                countdownText = countdownTextFormatted
                
                // Update notification
                withContext(Dispatchers.Main) {
                    val currentNotification = createForegroundNotification()
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, currentNotification)
                }
                
                delay(COUNTDOWN_UPDATE_INTERVAL_MS)
            }
            
            // Countdown complete
            if (isActive) {
                Logging.d(TAG, "Countdown complete")
                countdownText = ""
            }
        }
    }
    
    /**
     * Save scheduled trip to SharedPreferences
     */
    private fun saveScheduledTrip(trip: TripResponse, launchTimeMillis: Long) {
        try {
            val tripJson = gson.toJson(trip)
            prefs.edit().apply {
                putString(KEY_SCHEDULED_TRIP, tripJson)
                putLong(KEY_LAUNCH_TIME, launchTimeMillis)
                apply()
            }
            Logging.d(TAG, "Saved scheduled trip to preferences")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to save scheduled trip: ${e.message}", e)
        }
    }
    
    /**
     * Restore scheduled trip from SharedPreferences
     */
    private fun restoreScheduledTrip() {
        try {
            val tripJson = prefs.getString(KEY_SCHEDULED_TRIP, null)
            val savedLaunchTime = prefs.getLong(KEY_LAUNCH_TIME, 0)
            
            if (tripJson != null && savedLaunchTime > System.currentTimeMillis()) {
                Logging.d(TAG, "Restoring scheduled trip from preferences")
                
                val trip = gson.fromJson(tripJson, TripResponse::class.java)
                
                // Reschedule the launch
                scheduleNavigationLaunch(trip)
                
            } else {
                Logging.d(TAG, "No valid scheduled trip to restore")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to restore scheduled trip: ${e.message}", e)
        }
    }
}
