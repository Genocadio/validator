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
import com.here.sdk.core.Location
import com.here.sdk.core.LocationListener
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.location.LocationEngine
import com.here.sdk.location.LocationEngineStatus
import com.here.sdk.location.LocationFeature
import com.here.sdk.core.engine.SDKNativeEngine
import kotlinx.coroutines.delay
import com.here.sdk.location.LocationStatusListener
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
    
    // Location tracking
    private var locationEngine: LocationEngine? = null
    private var isLocationTrackingActive = AtomicBoolean(false)
    private var databaseManager: DatabaseManager? = null
    
    private val locationListener = LocationListener { location: com.here.sdk.core.Location ->
        handleLocationUpdate(location)
    }
    
    private val locationStatusListener = object : LocationStatusListener {
        override fun onStatusChanged(status: LocationEngineStatus) {
            Log.d(TAG, "🌍 LocationEngine status changed: ${status.name}")
            when (status) {
                LocationEngineStatus.ENGINE_STOPPED -> {
                    isLocationTrackingActive.set(false)
                    Log.d(TAG, "🌍 Location tracking STOPPED")
                }
                LocationEngineStatus.ENGINE_STARTED,
                LocationEngineStatus.ALREADY_STARTED,
                LocationEngineStatus.OK -> {
                    isLocationTrackingActive.set(true)
                    Log.d(TAG, "🌍 Location tracking STARTED successfully")
                }
                else -> {
                    Log.w(TAG, "🌍 LocationEngine status: ${status.name}")
                }
            }
        }
        
        override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
            features.forEach { feature ->
                Log.d(TAG, "🌍 Location feature not available: ${feature.name}")
            }
        }
    }
    
    private fun handleLocationUpdate(location: com.here.sdk.core.Location) {
        Log.d(TAG, "🌍 === BACKGROUND LOCATION UPDATE ===")
        Log.d(TAG, "🌍 Latitude: ${location.coordinates.latitude}")
        Log.d(TAG, "🌍 Longitude: ${location.coordinates.longitude}")
        Log.d(TAG, "🌍 Speed: ${location.speedInMetersPerSecond ?: 0.0} m/s")
        Log.d(TAG, "🌍 Bearing: ${location.bearingInDegrees} degrees")
        Log.d(TAG, "🌍 Accuracy: ${location.horizontalAccuracyInMeters ?: 0.0} meters")
        Log.d(TAG, "🌍 Timestamp: ${System.currentTimeMillis()}")
        Log.d(TAG, "🌍 ==================================")
        
        serviceScope.launch {
            try {
                // Get vehicle ID from SharedPreferences
                val prefs = getSharedPreferences("vehicle_prefs", Context.MODE_PRIVATE)
                val vehicleId = prefs.getLong("vehicle_id", -1L)
                
                if (vehicleId > 0) {
                    databaseManager?.updateVehicleCurrentLocation(
                        vehicleId = vehicleId.toInt(),
                        latitude = location.coordinates.latitude,
                        longitude = location.coordinates.longitude,
                        speed = location.speedInMetersPerSecond ?: 0.0,
                        accuracy = location.horizontalAccuracyInMeters ?: 0.0,
                        bearing = location.bearingInDegrees
                    )
                    
                    Log.d(TAG, "🌍✅ Background location SAVED to VehicleLocationEntity (vehicleId=$vehicleId)")
                } else {
                    Log.w(TAG, "🌍❌ Vehicle not registered, skipping location save")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🌍❌ Failed to save background location: ${e.message}", e)
            }
        }
    }
    
    private fun startBackgroundLocationTracking() {
        if (isLocationTrackingActive.get()) {
            Log.d(TAG, "🌍 Background location tracking already active")
            return
        }
        
        try {
            // Check if HERE SDK is initialized before starting LocationEngine
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine == null) {
                Log.w(TAG, "🌍⚠️ HERE SDK not initialized yet, deferring location tracking")
                // Schedule to retry after a delay
                serviceScope.launch {
                    delay(5000) // Wait 5 seconds for SDK to initialize
                    if (SDKNativeEngine.getSharedInstance() != null) {
                        tryStartBackgroundLocationTracking()
                    } else {
                        Log.e(TAG, "🌍❌ HERE SDK still not initialized after delay, giving up on location tracking")
                    }
                }
                return
            }
            
            tryStartBackgroundLocationTracking()
        } catch (e: Exception) {
            Log.e(TAG, "🌍❌ Failed to start background location tracking: ${e.message}", e)
        }
    }
    
    private fun tryStartBackgroundLocationTracking() {
        try {
            if (locationEngine == null) {
                locationEngine = LocationEngine()
                Log.d(TAG, "🌍 LocationEngine created")
            }
            
            locationEngine?.addLocationStatusListener(locationStatusListener)
            locationEngine?.addLocationListener(locationListener)
            locationEngine?.confirmHEREPrivacyNoticeInclusion()
            
            // Use BEST_AVAILABLE for background tracking
            val status = locationEngine?.start(LocationAccuracy.BEST_AVAILABLE)
            Log.d(TAG, "🌍 Background location tracking start status: ${status}")
            Log.d(TAG, "🌍 Using BEST_AVAILABLE accuracy")
        } catch (e: Exception) {
            Log.e(TAG, "🌍❌ Failed to start location tracking: ${e.message}", e)
        }
    }
    
    private fun stopBackgroundLocationTracking() {
        try {
            Log.d(TAG, "🌍 Stopping background location tracking...")
            Log.d(TAG, "🌍 Location tracking active: ${isLocationTrackingActive.get()}")
            Log.d(TAG, "🌍 Location engine: ${locationEngine != null}")
            
            // Remove listeners first
            locationEngine?.let { engine ->
                try {
                    engine.removeLocationListener(locationListener)
                    Log.d(TAG, "🌍 Removed location listener")
                } catch (e: Exception) {
                    Log.e(TAG, "🌍❌ Error removing location listener: ${e.message}", e)
                }
                
                try {
                    engine.removeLocationStatusListener(locationStatusListener)
                    Log.d(TAG, "🌍 Removed location status listener")
                } catch (e: Exception) {
                    Log.e(TAG, "🌍❌ Error removing location status listener: ${e.message}", e)
                }
                
                try {
                    engine.stop()
                    Log.d(TAG, "🌍 Location engine stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "🌍❌ Error stopping location engine: ${e.message}", e)
                }
            }
            
            isLocationTrackingActive.set(false)
            Log.d(TAG, "🌍 Background location tracking stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "🌍❌ Failed to stop background location tracking: ${e.message}", e)
        }
    }
    
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
        
        // Start background location tracking
        startBackgroundLocationTracking()
        
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
        
        // Stop background location tracking FIRST
        // This is critical to prevent service connection leaks
        try {
            Log.d(TAG, "Stopping background location tracking before service destruction...")
            stopBackgroundLocationTracking()
            
            // Wait a bit for LocationEngine to fully stop
            Thread.sleep(200)
            
            // Null out the reference
            locationEngine = null
            Log.d(TAG, "LocationEngine stopped and nulled")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping LocationEngine during service destruction: ${e.message}", e)
        }
        
        // Release wake lock
        releaseWakeLock()
        
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
        stopForeground(true)
        stopSelf()
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
