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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gocavgo.validator.MainActivity
import com.gocavgo.validator.R
import com.gocavgo.validator.navigator.NavigActivity
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for maintaining MQTT connection in background
 * Uses dataSync foreground service type for trip/booking data synchronization
 */
class MqttForegroundService : Service() {
    
    companion object {
        private const val TAG = "MqttForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val SERVICE_CHANNEL_ID = "mqtt_service_channel"
        private const val SERVICE_CHANNEL_NAME = "MQTT Service Status"
        
        // Service control actions
        const val ACTION_START_SERVICE = "com.gocavgo.validator.START_MQTT_SERVICE"
        const val ACTION_STOP_SERVICE = "com.gocavgo.validator.STOP_MQTT_SERVICE"
        
        @Volatile
        private var isServiceRunning = AtomicBoolean(false)
        
        fun isRunning(): Boolean = isServiceRunning.get()
        
        @Volatile
        private var INSTANCE: MqttForegroundService? = null
        
        fun getInstance(): MqttForegroundService? = INSTANCE
    }
    
    private val binder = MqttServiceBinder()
    private var mqttService: MqttService? = null
    private var notificationManager: MqttNotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkMonitor: NetworkMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var databaseManager: DatabaseManager? = null
    
    inner class MqttServiceBinder : Binder() {
        fun getService(): MqttForegroundService = this@MqttForegroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MqttForegroundService created")
        
        // Create notification channel
        createNotificationChannel()
        
        // Initialize notification manager
        notificationManager = MqttNotificationManager(this)
        
        // Acquire wake lock for critical operations
        acquireWakeLock()
        
        // Get MQTT service instance
        mqttService = MqttService.getInstance()
        
        // Set notification manager in MQTT service
        mqttService?.setNotificationManager(notificationManager!!)
        
        // Initialize database manager
        databaseManager = DatabaseManager.getInstance(this)
        
        // Initialize network monitoring for background operation
        initializeNetworkMonitoring()
        
        INSTANCE = this
        isServiceRunning.set(true)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MqttForegroundService started with action: ${intent?.action}")
        
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
        Log.d(TAG, "=== MqttForegroundService DESTROY STARTED ===")
        
        // Release wake lock
        releaseWakeLock()
        
        // Stop foreground service and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        // Clear notification manager from MQTT service
        mqttService?.setNotificationManager(null)
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        
        INSTANCE = null
        isServiceRunning.set(false)
        
        Log.d(TAG, "=== MqttForegroundService DESTROY COMPLETE ===")
        super.onDestroy()
    }
    
    /**
     * Start the foreground service with persistent notification
     */
    private fun startForegroundService() {
        try {
            val notification = createForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Foreground service started with notification")
            
            // Ensure MQTT service is connected
            serviceScope.launch {
                try {
                    mqttService?.let { mqtt ->
                        if (!mqtt.isConnected()) {
                            Log.d(TAG, "MQTT not connected, attempting connection...")
                            // MQTT service should already be initialized, just ensure connection
                            mqtt.checkAndFixInconsistentState()
                        } else {
                            Log.d(TAG, "MQTT already connected")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error ensuring MQTT connection: ${e.message}", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
        }
    }
    
    /**
     * Stop the foreground service
     */
    private fun stopForegroundService() {
        Log.d(TAG, "Stopping foreground service")
        
        // Release wake lock
        releaseWakeLock()
        
        // Stop foreground service and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.d(TAG, "Foreground service stopped and removed")
    }
    
    /**
     * Create notification channel for service status
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                SERVICE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows MQTT service connection status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "Created notification channel: $SERVICE_CHANNEL_ID")
        }
    }
    
    /**
     * Create foreground notification showing MQTT connection status
     */
    private fun createForegroundNotification(): Notification {
        val mqttStatus = mqttService?.let { 
            when {
                it.isWaitingForNetwork() -> "Waiting for network"
                it.isConnected() -> "Connected"
                else -> "Disconnected"
            }
        } ?: "Unknown"
        
        val statusText = "MQTT: $mqttStatus"
        
        // Create intent to open main activity when notification is tapped
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("GoCavGo Validator")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
    
    /**
     * Update the foreground notification with current MQTT status
     */
    fun updateNotification() {
        try {
            val notification = createForegroundNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Updated foreground notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}", e)
        }
    }
    
    /**
     * Acquire wake lock for critical MQTT operations
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GoCavGo:MqttForegroundService"
            )
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
        }
    }
    
    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock: ${e.message}", e)
        }
    }
    
    /**
     * Get MQTT service instance
     */
    fun getMqttService(): MqttService? = mqttService
    
    /**
     * Get notification manager instance
     */
    fun getNotificationManager(): MqttNotificationManager? = notificationManager
    
    /**
     * Check if service is running
     */
    fun isServiceRunning(): Boolean = isServiceRunning.get()
    
    /**
     * Initialize network monitoring for background operation
     */
    private fun initializeNetworkMonitoring() {
        try {
            Log.d(TAG, "Initializing background network monitoring...")
            
            networkMonitor = NetworkMonitor(this) { connected, type, metered ->
                Log.d(TAG, "=== BACKGROUND NETWORK STATE CHANGED ===")
                Log.d(TAG, "Connected: $connected")
                Log.d(TAG, "Connection Type: $type")
                Log.d(TAG, "Is Metered: $metered")
                Log.d(TAG, "=========================================")
                
                // Notify MQTT service of network changes
                mqttService?.onNetworkStateChanged(connected, type, metered)
                
                // Update notification with network status
                updateNotification()
                
                // Store network state for persistence
                storeNetworkState(connected, type, metered)
            }
            
            networkMonitor?.startMonitoring()
            Log.d(TAG, "Background network monitoring started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize background network monitoring: ${e.message}", e)
        }
    }
    
    /**
     * Store network state in SharedPreferences for persistence
     */
    private fun storeNetworkState(connected: Boolean, type: String, metered: Boolean) {
        try {
            val prefs = getSharedPreferences("network_state", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_connected", connected)
                putString("connection_type", type)
                putBoolean("is_metered", metered)
                putLong("last_updated", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Network state stored: connected=$connected, type=$type, metered=$metered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store network state: ${e.message}", e)
        }
    }
    
    /**
     * Get current network state from storage
     */
    fun getStoredNetworkState(): NetworkMonitor.NetworkState? {
        return try {
            val prefs = getSharedPreferences("network_state", Context.MODE_PRIVATE)
            val connected = prefs.getBoolean("is_connected", false)
            val type = prefs.getString("connection_type", "UNKNOWN") ?: "UNKNOWN"
            val metered = prefs.getBoolean("is_metered", true)
            val lastUpdated = prefs.getLong("last_updated", 0)
            
            // Consider state stale if older than 5 minutes
            if (System.currentTimeMillis() - lastUpdated > 5 * 60 * 1000) {
                Log.w(TAG, "Stored network state is stale, ignoring")
                null
            } else {
                NetworkMonitor.NetworkState(connected, type, metered)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stored network state: ${e.message}", e)
            null
        }
    }
}
