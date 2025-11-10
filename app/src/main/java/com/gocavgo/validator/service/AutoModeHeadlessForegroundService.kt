package com.gocavgo.validator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.gocavgo.validator.R
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.navigator.App
import com.gocavgo.validator.navigator.MessageViewUpdater
import com.gocavgo.validator.navigator.TripSectionValidator
import com.gocavgo.validator.receiver.TripConfirmationReceiver
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.sync.SyncCoordinator
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.util.TripDataConverter
import com.gocavgo.validator.trip.ActiveTripListener
import com.gocavgo.validator.trip.ActiveTripCallback
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.navigation.DestinationReachedListener
import com.here.sdk.navigation.NavigableLocationListener
import com.here.sdk.navigation.RouteProgressListener
import com.here.sdk.routing.Route
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that handles trip logic, navigation, and MQTT in background
 * Works independently when Activity is paused, syncs with Activity when Activity is active
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
        
        // Synchronization lock for SDK initialization
        private val sdkInitLock = Any()
        
        // Check if Activity is active
        fun isActivityActive(): Boolean = AutoModeHeadlessActivity.isActive()
    }
    
    private val binder = LocalBinder()
    private var notificationMessage = "Auto Mode Active"
    
    /**
     * Get current navigation text from notification message
     * This contains the latest navigation state (distance, speed) that Service is displaying
     */
    fun getCurrentNavigationTextForSync(): String = notificationMessage
    
    /**
     * Sync cache from Activity to Service
     * Called by Activity to share cache state so Service can use it when Activity goes to background
     */
    fun syncCacheFromActivity(distance: Double?, time: Long?, waypointName: String?) {
        lastKnownDistance = distance
        lastKnownTime = time
        lastKnownWaypointName = waypointName
        Logging.d(TAG, "SERVICE: Cache synced from Activity: distance=$distance, time=$time, waypoint=$waypointName")
    }
    
    /**
     * Get cache state for sync
     * Returns current cache values
     */
    fun getCacheForSync(): Triple<Double?, Long?, String?> {
        return Triple(lastKnownDistance, lastKnownTime, lastKnownWaypointName)
    }
    
    // Service state (separate from Activity state)
    private var currentTrip: TripResponse? = null
    private val isNavigating = AtomicBoolean(false)
    private var countdownText = ""
    
    // Route calculation state (track if route is calculated)
    private var isRouteCalculated = false
    
    // Cache for notification persistence (prevent flickering when data is temporarily null)
    private var lastKnownDistance: Double? = null
    private var lastKnownTime: Long? = null
    private var lastKnownWaypointName: String? = null
    
    // Active trip listener
    private var activeTripListener: ActiveTripListener? = null
    
    // HERE SDK components for service - using App class like HeadlessNavigActivity
    private var app: App? = null
    private var messageViewUpdater: MessageViewUpdater? = null
    
    // Database and managers
    private lateinit var databaseManager: DatabaseManager
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var settingsManager: VehicleSettingsManager
    private var mqttService: MqttService? = null
    private var currentSettings: com.gocavgo.validator.dataclass.VehicleSettings? = null
    
    // Background coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var periodicFetchJob: Job? = null
    
    // Confirmation receiver
    private var confirmationReceiver: BroadcastReceiver? = null
    
    // Handler for main thread operations
    private val handler = Handler(Looper.getMainLooper())
    
    // SharedPreferences for state persistence
    private val prefs by lazy {
        getSharedPreferences("automode_service_state", Context.MODE_PRIVATE)
    }
    
    // Data class for confirmation (internal for Activity sync)
    internal data class TripConfirmationData(
        val trip: TripResponse,
        val expectedDepartureTime: String,
        val currentTime: String,
        val delayMinutes: Int
    )
    
    inner class LocalBinder : Binder() {
        fun getService(): AutoModeHeadlessForegroundService = this@AutoModeHeadlessForegroundService
        
        // Methods to query service state for Activity sync (deprecated - use service methods directly)
        fun getCurrentTrip(): TripResponse? = currentTrip
        fun isNavigating(): Boolean = isNavigating.get()
        fun getCountdownText(): String = countdownText
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Logging.d(TAG, "Service bound")
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Logging.d(TAG, "=== AutoModeHeadlessForegroundService CREATED ===")
        
        createNotificationChannel()
        
        // Initialize managers
        databaseManager = DatabaseManager.getInstance(this)
        vehicleSecurityManager = VehicleSecurityManager(this)
        settingsManager = VehicleSettingsManager.getInstance(this)
        
        // Initialize MQTT service if not already initialized
        initializeMqttService()
        
        // Initialize ActiveTripListener
        activeTripListener = ActiveTripListener(this)
        activeTripListener?.start(
            scope = serviceScope,
            callback = object : ActiveTripCallback {
                override fun onActiveTripFound(trip: TripResponse) {
                    Logging.d(TAG, "Active trip found: ${trip.id}")
                    serviceScope.launch {
                        handleTripReceived(trip)
                    }
                }
                
                override fun onActiveTripChanged(newTrip: TripResponse, oldTrip: TripResponse?) {
                    Logging.d(TAG, "Active trip changed: ${oldTrip?.id} → ${newTrip.id}")
                    serviceScope.launch {
                        handleTripReceived(newTrip)
                    }
                }
                
                override fun onTripCancelled(tripId: Int) {
                    Logging.d(TAG, "Trip cancelled: $tripId")
                    serviceScope.launch {
                        handleTripCancellation(tripId)
                    }
                }
                
                override fun onError(error: String) {
                    Logging.e(TAG, "Error from ActiveTripListener: $error")
                    updateNotification("Error: $error")
                }
                
                override fun onCountdownTextUpdate(countdownText: String) {
                    this@AutoModeHeadlessForegroundService.countdownText = countdownText
                    if (countdownText.isNotEmpty()) {
                        val trip = currentTrip
                        if (trip != null) {
                            handler.post {
                                updateNotification("$countdownText - ${trip.route.origin.google_place_name}")
                            }
                        }
                    }
                }
                
                override fun onCountdownComplete(trip: TripResponse) {
                    Logging.d(TAG, "Countdown complete, starting navigation")
                    countdownText = ""
                    handler.post {
                        updateNotification("Starting navigation...")
                    }
                    serviceScope.launch {
                        startNavigationInternal(trip)
                    }
                }
                
                override fun onNavigationStartRequested(trip: TripResponse, allowResume: Boolean) {
                    Logging.d(TAG, "Navigation start requested (allowResume=$allowResume)")
                    if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                        val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                        handler.post {
                            updateNotification("Resuming navigation: $origin → $destination")
                        }
                    } else {
                        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                        val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                        handler.post {
                            updateNotification("Starting navigation: $origin → $destination")
                        }
                    }
                    serviceScope.launch {
                        startNavigationInternal(trip, allowResume)
                    }
                }
                
                override fun onTripScheduled(trip: TripResponse, departureTimeMillis: Long) {
                    Logging.d(TAG, "Trip scheduled: ${trip.id}, departure time: $departureTimeMillis")
                    // Format departure time for display
                    val departureDate = java.util.Date(departureTimeMillis)
                    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    val formattedTime = timeFormat.format(departureDate)
                    
                    val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                    val departureMessage = "Trip will depart at $formattedTime - $origin"
                    
                    handler.post {
                        updateNotification(departureMessage)
                    }
                }
                
                override fun onSettingsSynced(settings: com.gocavgo.validator.dataclass.VehicleSettings, isNavigating: Boolean): Boolean {
                    // Settings sync not implemented in service
                    return false
                }
                
                override fun onTripStateVerificationNeeded(trip: TripResponse) {
                    // Not used in service
                }
                
                override fun onImmediateBackendFetchComplete(trip: TripResponse?) {
                    // Not used in service
                }
                
                override fun onSilentBackendFetchFoundTrip(trip: TripResponse) {
                    // Not used in service
                }
            }
        )
        
        // Set up connection state monitoring (for MQTT health checks)
        setupMqttConnectionMonitoring()
        
        // Fetch settings
        serviceScope.launch {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            try {
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                result.onSuccess { settings ->
                    currentSettings = settings
                    settingsManager.applySettings(this@AutoModeHeadlessForegroundService, settings)
                }.onFailure { error ->
                    Logging.e(TAG, "Failed to fetch settings: ${error.message}")
                    val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                    savedSettings?.let { currentSettings = it }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception fetching settings: ${e.message}", e)
                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                savedSettings?.let { currentSettings = it }
            }
        }
        
        // Initialize HERE SDK and navigation components in background
        serviceScope.launch {
            try {
                initializeHERESDK()
                initializeNavigationComponents()
                
                // MQTT callback already registered in onCreate() - verify it's still set
                verifyMqttCallbackRegistration()
                
                // Register confirmation receiver
                registerConfirmationReceiver()
                
                // Start periodic backend fetch
                startPeriodicBackendFetch()
                
                // Restore state from database if Activity is not active
                if (!isActivityActive()) {
                    restoreStateFromDatabase()
                } else {
                    // If Activity is active, restore state from persistence in case service was restarted
                    restoreStateFromPersistence()
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error during service initialization: ${e.message}", e)
            }
        }
        
        // Start foreground immediately
        val notification = createNotification("Auto Mode Active")
        startForeground(NOTIFICATION_ID, notification)
        
        Logging.d(TAG, "=== AutoModeHeadlessForegroundService INITIALIZED ===")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logging.d(TAG, "Service started")
        
        // Ensure MQTT is initialized and connected
        if (mqttService == null) {
            Logging.d(TAG, "MQTT service is null in onStartCommand, initializing...")
            initializeMqttService()
        } else {
            // Verify MQTT connection
            val isConnected = mqttService!!.isConnected()
            val isActive = mqttService!!.isServiceActive()
            
            Logging.d(TAG, "MQTT status in onStartCommand: connected=$isConnected, active=$isActive")
            
            if (!isConnected) {
                if (!isActive) {
                    // Service is not active, connect it
                    Logging.d(TAG, "MQTT service is not active, connecting...")
                    mqttService?.connect(
                        username = "cavgocars",
                        password = "Cadio*11."
                    )
                } else {
                    // Service is active but not connected, trigger reconnection
                    Logging.d(TAG, "MQTT service is active but not connected, triggering reconnection...")
                    mqttService?.onAppForeground() // This will trigger reconnection if needed
                }
            }
        }
        
        // Re-register MQTT callback to ensure it's always set (Activity may have unregistered)
        verifyMqttCallbackRegistration()
        
        // Check MQTT connection and re-register if needed
        checkMqttConnectionAndCallback()
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        Logging.d(TAG, "=== AutoModeHeadlessForegroundService DESTROY STARTED ===")
        
        // Stop ActiveTripListener
        activeTripListener?.stop()
        activeTripListener = null
        
        // Cancel periodic fetch job
        periodicFetchJob?.cancel()
        
        // Unregister receivers
        try {
            confirmationReceiver?.let { unregisterReceiver(it) }
            confirmationReceiver = null
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering confirmation receiver: ${e.message}")
        }
        
        // Don't unregister Service callback - it should always be registered
        // Only unregister if explicitly shutting down
        try {
            Logging.d(TAG, "Service destroying - keeping Service callback registered for background operation")
            // Service callback should remain registered even after service destroy
            // (service will restart if needed)
        } catch (e: Exception) {
            Logging.w(TAG, "Error during callback cleanup: ${e.message}")
        }
        
        // CRITICAL: Stop navigation and location services FIRST to prevent service connection leaks
        // This ensures HERE SDK's internal service connections are properly released
        try {
            Logging.d(TAG, "Stopping navigation and location services...")
            
            // Use App.detach() to clean up (same as HeadlessNavigActivity)
            app?.detach()
            app = null
            
            Logging.d(TAG, "SERVICE: Navigation and location services stopped")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error stopping navigation: ${e.message}", e)
        }
        
        // Wait for HERE SDK service connections to unbind before completing destruction
        try {
            Logging.d(TAG, "SERVICE: Waiting for HERE SDK service connections to unbind...")
            Thread.sleep(300) // 300ms grace period for service connections to unbind
            Logging.d(TAG, "SERVICE: Grace period completed")
        } catch (e: InterruptedException) {
            Logging.w(TAG, "SERVICE: Interrupted while waiting for service cleanup: ${e.message}")
        }
        
        // Cancel service scope
        serviceScope.cancel()
        
        // Stop foreground service and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Logging.d(TAG, "=== AutoModeHeadlessForegroundService DESTROYED ===")
    }
    
    // ========== INITIALIZATION METHODS ==========
    
    /**
     * Initialize MQTT service if not already initialized
     */
    private fun initializeMqttService() {
        // Check if MQTT service already exists
        mqttService = MqttService.getInstance()
        
        if (mqttService == null) {
            Logging.d(TAG, "MQTT service not initialized, initializing now...")
            
            // Check if vehicle is registered
            if (!vehicleSecurityManager.isVehicleRegistered()) {
                Logging.w(TAG, "Vehicle not registered, cannot initialize MQTT service")
                return
            }
            
            val vehicleId = vehicleSecurityManager.getVehicleId()
            
            // Initialize MQTT service with broker details
            mqttService = MqttService.getInstance(
                context = this,
                brokerHost = "73d5ec93cbe843ab83b8f29e68f6979e.s1.eu.hivemq.cloud",
                brokerPort = 8883,
                carId = vehicleId.toString()
            )
            
            Logging.d(TAG, "MQTT service initialized successfully")
            
            // Set notification manager for MQTT notifications
            try {
                val notificationManager = com.gocavgo.validator.service.MqttNotificationManager(this)
                mqttService?.setNotificationManager(notificationManager)
                Logging.d(TAG, "MQTT notification manager set")
            } catch (e: Exception) {
                Logging.w(TAG, "Failed to set MQTT notification manager: ${e.message}")
            }
            
            // Set connection callback to re-register service callback on connect
            mqttService?.setConnectionCallback { connected ->
                Logging.d(TAG, "MQTT connection status changed: $connected")
                if (connected) {
                    Logging.d(TAG, "MQTT connected - ensuring Service callback is registered")
                    // Re-register callback after connection is established
                    verifyMqttCallbackRegistration()
                }
            }
            
            // Connect to MQTT broker
            mqttService?.connect(
                username = "cavgocars",
                password = "Cadio*11."
            )
            
            Logging.d(TAG, "MQTT connection initiated")
        } else {
            Logging.d(TAG, "MQTT service already initialized")
            
            // Service instance exists - ensure it's connected
            if (!mqttService!!.isConnected()) {
                Logging.d(TAG, "MQTT service exists but not connected, ensuring connection...")
                val isActive = mqttService!!.isServiceActive()
                if (!isActive) {
                    // Service is not active, connect it
                    mqttService?.connect(
                        username = "cavgocars",
                        password = "Cadio*11."
                    )
                    Logging.d(TAG, "MQTT connection initiated (service was inactive)")
                } else {
                    // Service is active but not connected, trigger reconnection
                    Logging.d(TAG, "MQTT service is active but not connected, triggering reconnection...")
                    mqttService?.onAppForeground() // This will trigger reconnection if needed
                }
            } else {
                Logging.d(TAG, "MQTT service is already connected")
            }
        }
    }
    
    private fun initializeHERESDK() {
        synchronized(sdkInitLock) {
            try {
                // Check if SDK is already initialized (by Activity)
                if (SDKNativeEngine.getSharedInstance() != null) {
                    Logging.d(TAG, "HERE SDK already initialized by Activity, reusing")
                    return
                }
                
                Logging.d(TAG, "Initializing HERE SDK in service...")
                val accessKeyID = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_ID
                val accessKeySecret = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_SECRET
                val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
                val options = SDKOptions(authenticationMode)
                
                // Initialize SDK with Application context
                SDKNativeEngine.makeSharedInstance(applicationContext, options)
                Logging.d(TAG, "HERE SDK initialized successfully in service")
            } catch (e: InstantiationErrorException) {
                Logging.e(TAG, "Initialization of HERE SDK failed: ${e.error.name}", e)
            } catch (e: Exception) {
                Logging.e(TAG, "Unexpected error during HERE SDK initialization: ${e.message}", e)
            }
        }
    }
    
    private fun initializeNavigationComponents() {
        try {
            Logging.d(TAG, "Initializing navigation components in service...")
            
            // Verify SDK is initialized
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine == null) {
                Logging.e(TAG, "HERE SDK not initialized, cannot create App")
                return
            }
            
            // Create MessageViewUpdater for navigation updates
            messageViewUpdater = MessageViewUpdater()
            
            // Create App with null mapView for headless mode (same as HeadlessNavigActivity)
            app = App(applicationContext, null, messageViewUpdater!!, null)
            Logging.d(TAG, "App instance created in service (headless mode): $app")
            
            // Set up route calculation callback
            app?.setOnRouteCalculatedCallback {
                isRouteCalculated = true
                Logging.d(TAG, "SERVICE: Route calculation status updated: true")
                // Start progress updates only after route is calculated
                if (isNavigating.get()) {
                    startNavigationProgressUpdates()
                }
            }
            
            // Set up MQTT service and callbacks on App's TripSectionValidator
            app?.getTripSectionValidator()?.let { validator: TripSectionValidator ->
                // Initialize MQTT service in trip section validator
                mqttService?.let { mqtt: com.gocavgo.validator.service.MqttService ->
                    validator.initializeMqttService(mqtt)
                    Logging.d(TAG, "MQTT service initialized in App's trip section validator")
                }
                
                // Set callback for trip deletion - when trip is not found in DB, transition to waiting state
                validator.setTripDeletedCallback { deletedTripId: Int ->
                    Logging.w(TAG, "SERVICE: Trip $deletedTripId deleted during navigation - transitioning to waiting state")
                    serviceScope.launch {
                        handleTripDeletedDuringNavigation(deletedTripId)
                    }
                }
            }
            
            Logging.d(TAG, "Navigation components initialized successfully in service")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize navigation components: ${e.message}", e)
        }
    }
    
    /**
     * Verify MQTT callback is registered (now handled by ActiveTripListener)
     */
    private fun verifyMqttCallbackRegistration() {
        // MQTT trip callbacks are now handled by ActiveTripListener
        // This method is kept for compatibility but does nothing
        Logging.d(TAG, "MQTT trip callbacks are handled by ActiveTripListener")
    }
    
    /**
     * Check MQTT connection status and ensure callback is registered
     */
    private fun checkMqttConnectionAndCallback() {
        if (mqttService == null) {
            Logging.w(TAG, "MQTT service is null, attempting to initialize...")
            initializeMqttService()
        }
        
        if (mqttService == null) {
            Logging.e(TAG, "MQTT service is still null after initialization attempt")
            return
        }
        
        val isConnected = mqttService?.isConnected() ?: false
        Logging.d(TAG, "MQTT connection status: $isConnected")
        
        if (!isConnected) {
            Logging.w(TAG, "MQTT is not connected - callback will be registered when connection is restored")
            // Ensure callback is registered anyway (will work when connection is established)
            verifyMqttCallbackRegistration()
        } else {
            // Connection is active, ensure callback is registered
            verifyMqttCallbackRegistration()
        }
    }
    
    /**
     * Set up connection state monitoring to re-register callback when MQTT reconnects
     */
    private fun setupMqttConnectionMonitoring() {
        // Check connection periodically and re-register callback if needed
        serviceScope.launch {
            var lastConnectionState = false
            var lastCallbackCheck = System.currentTimeMillis()
            
            while (isActive) {
                delay(5000) // Check every 5 seconds
                
                try {
                    // If MQTT service is null, try to initialize it
                    if (mqttService == null) {
                        Logging.d(TAG, "MQTT service is null in monitoring, attempting initialization...")
                        initializeMqttService()
                        delay(2000) // Wait a bit for initialization
                    }
                    
                    val currentConnectionState = mqttService?.isConnected() ?: false
                    val currentTime = System.currentTimeMillis()
                    
                    // If connection state changed from false to true, re-register callback
                    if (currentConnectionState && !lastConnectionState) {
                        Logging.d(TAG, "MQTT connection restored - re-registering Service callback")
                        verifyMqttCallbackRegistration()
                        lastConnectionState = true
                    } else if (!currentConnectionState) {
                        lastConnectionState = false
                    }
                    
                    // Also check callback registration periodically (every 30 seconds)
                    if (currentTime - lastCallbackCheck > 30000) {
                        if (currentConnectionState) {
                            Logging.d(TAG, "Periodic callback verification...")
                            verifyMqttCallbackRegistration()
                        } else if (mqttService == null) {
                            // Try to initialize if service is null
                            Logging.d(TAG, "MQTT service is null during periodic check, attempting initialization...")
                            initializeMqttService()
                        }
                        lastCallbackCheck = currentTime
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in MQTT connection monitoring: ${e.message}", e)
                }
            }
        }
        
        Logging.d(TAG, "MQTT connection monitoring started")
    }
    
    private fun registerConfirmationReceiver() {
        try {
            confirmationReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Logging.d(TAG, "=== CONFIRMATION RECEIVER TRIGGERED IN SERVICE ===")
                    Logging.d(TAG, "Received action: ${intent?.action}")
                    
                    when (intent?.action) {
                        TripConfirmationReceiver.ACTION_START_CONFIRMED -> {
                            Logging.d(TAG, "Processing START_CONFIRMED action in service (confirmation removed, ignoring)")
                        }
                        TripConfirmationReceiver.ACTION_CANCEL_CONFIRMED -> {
                            Logging.d(TAG, "Processing CANCEL_CONFIRMED action in service (confirmation removed, ignoring)")
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(TripConfirmationReceiver.ACTION_START_CONFIRMED)
                addAction(TripConfirmationReceiver.ACTION_CANCEL_CONFIRMED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(confirmationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(confirmationReceiver, filter)
            }
            Logging.d(TAG, "Confirmation receiver registered in service")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to register confirmation receiver: ${e.message}", e)
        }
    }
    
    private fun restoreStateFromDatabase() {
        serviceScope.launch {
            try {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                
                if (activeTrip != null) {
                    Logging.d(TAG, "Restoring state for active trip: ${activeTrip.id} (${activeTrip.status})")
                    currentTrip = activeTrip
                    
                    if (activeTrip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                        Logging.d(TAG, "Trip is IN_PROGRESS, will resume navigation after initialization")
                        isNavigating.set(true)
                        updateNotification("Resuming navigation: ${activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name}")
                    } else if (activeTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                        val departureTimeMillis = activeTrip.departure_time * 1000
                        val currentTime = System.currentTimeMillis()
                        if (currentTime >= departureTimeMillis) {
                            Logging.d(TAG, "Trip departure time has passed, will start navigation after initialization")
                            updateNotification("Resuming navigation: ${activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name}")
                        } else {
                            updateNotification("Trip scheduled: ${activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name}")
                        }
                    }
                } else {
                    updateNotification("Auto Mode: Waiting for trip...")
                }
            } catch (e: Exception) {
                Logging.w(TAG, "State restore failed: ${e.message}")
            }
        }
    }
    
    private suspend fun convertBackendTripToAndroid(backendTrip: com.gocavgo.validator.dataclass.TripData): TripResponse {
        return TripDataConverter.convertBackendTripToAndroid(backendTrip)
    }
    
    // ========== STATE PERSISTENCE ==========
    
    /**
     * Save current state to SharedPreferences for recovery after app restart
     * Includes all state fields with timestamps for detecting stale state
     */
    private fun saveStateToPersistence() {
        try {
            val currentTime = System.currentTimeMillis()
            val editor = prefs.edit()
            editor.putInt("current_trip_id", currentTrip?.id ?: -1)
            editor.putBoolean("is_navigating", isNavigating.get())
            editor.putString("countdown_text", countdownText)
            editor.putLong("state_timestamp", currentTime)
            
            // Add navigation timestamp if navigating
            if (isNavigating.get()) {
                editor.putLong("navigation_start_timestamp", currentTime)
            } else {
                editor.remove("navigation_start_timestamp")
            }
            
            // Add countdown timestamp if countdown is active
            if (countdownText.isNotEmpty()) {
                editor.putLong("countdown_start_timestamp", currentTime)
            } else {
                editor.remove("countdown_start_timestamp")
            }
            
            editor.apply()
            Logging.d(TAG, "SERVICE: State saved to persistence (trip=${currentTrip?.id}, navigating=${isNavigating.get()}, countdown=${countdownText.isNotEmpty()})")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Failed to save state to persistence: ${e.message}", e)
        }
    }
    
    /**
     * Restore state from SharedPreferences (called on service creation)
     */
    private suspend fun restoreStateFromPersistence() {
        try {
            val tripId = prefs.getInt("current_trip_id", -1)
            val wasNavigating = prefs.getBoolean("is_navigating", false)
            val savedCountdownText = prefs.getString("countdown_text", "") ?: ""
            val stateTimestamp = prefs.getLong("state_timestamp", 0)
            
            // Only restore if state is recent (within last hour)
            val stateAge = System.currentTimeMillis() - stateTimestamp
            if (stateAge > 3600000) { // 1 hour
                Logging.d(TAG, "SERVICE: Persisted state is too old, ignoring")
                return
            }
            
            if (tripId > 0) {
                Logging.d(TAG, "SERVICE: Restoring state from persistence: tripId=$tripId, navigating=$wasNavigating")
                
                // Load trip from database
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val trip = databaseManager.getTripById(tripId)
                
                if (trip != null) {
                    currentTrip = trip
                    isNavigating.set(wasNavigating)
                    countdownText = savedCountdownText
                    
                    Logging.d(TAG, "SERVICE: State restored from persistence")
                } else {
                    Logging.d(TAG, "SERVICE: Trip $tripId not found in database, clearing persisted state")
                    clearPersistedState()
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Failed to restore state from persistence: ${e.message}", e)
        }
    }
    
    /**
     * Clear persisted state
     */
    private fun clearPersistedState() {
        try {
            prefs.edit().clear().apply()
            Logging.d(TAG, "SERVICE: Persisted state cleared")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Failed to clear persisted state: ${e.message}", e)
        }
    }
    
    // ========== TRIP HANDLING METHODS (Background Operation) ==========
    
    private suspend fun handleTripReceived(trip: TripResponse) {
        Logging.d(TAG, "=== SERVICE: HANDLING RECEIVED TRIP ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Status: ${trip.status}")
        Logging.d(TAG, "Departure time: ${trip.departure_time}")
        
        // Save as current trip
        currentTrip = trip
        saveStateToPersistence() // Persist state change immediately
        
        // Sync with Activity (Service takes precedence when Activity is paused, but keep Activity in sync)
        // Note: State syncing is now handled by ActiveTripListener

        // Countdown and navigation start logic is now handled by ActiveTripListener
        // This method just updates local state and syncs with Activity
    }
    
    private suspend fun startNavigationInternal(trip: TripResponse, allowResume: Boolean = false) {
        // Check if navigation is already active - but allow resume if explicitly requested
        if (isNavigating.get() && !allowResume) {
            Logging.w(TAG, "SERVICE: Navigation already active, ignoring start request")
            return
        }
        
        // Clear cache when navigation starts
        lastKnownDistance = null
        lastKnownTime = null
        lastKnownWaypointName = null
        
        // Reset route calculation status
        isRouteCalculated = false
        
        Logging.d(TAG, "=== SERVICE: STARTING NAVIGATION INTERNALLY ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Allow resume: $allowResume")
        Logging.d(TAG, "IsNavigating BEFORE: ${isNavigating.get()}")
        Logging.d(TAG, "HasActiveRoute BEFORE: ${hasActiveRouteForSync()}")
        
        
        // App handles navigation state internally - no need to track flags
        
        // Set isNavigating flag EARLY - before route calculation starts
        // This ensures Activity can detect navigation state even during route calculation
        isNavigating.set(true)
        currentTrip = trip
        saveStateToPersistence() // Persist state change immediately
        Logging.d(TAG, "SERVICE: isNavigating set to true EARLY (before route calculation)")
        Logging.d(TAG, "SERVICE: Current trip set to ${trip.id}, status: ${trip.status}")
        
        // Update trip status to IN_PROGRESS when navigation starts or resumes
        // This ensures database always has correct status when navigation is active
        try {
            val currentStatus = trip.status
            if (!currentStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                Logging.d(TAG, "SERVICE: Trip ${trip.id} status updated from $currentStatus to IN_PROGRESS (navigation ${if (allowResume) "resumed" else "started"})")
                
                // Update local trip status
                currentTrip = trip.copy(status = "IN_PROGRESS")
            } else {
                Logging.d(TAG, "SERVICE: Trip ${trip.id} already IN_PROGRESS (navigation ${if (allowResume) "resumed" else "started"})")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Failed to update trip status to IN_PROGRESS: ${e.message}", e)
        }
        
        // Immediately update notification to reflect navigation starting
        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
        withContext(Dispatchers.Main) {
            updateNotification("Starting navigation: $origin")
        }
        Logging.d(TAG, "SERVICE: Navigation state set, notification updated")
        
        // Read settings from DB before starting navigation (not from memory)
        // This ensures we use the latest simulate value even if settings changed while Service was running
        try {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            val dbSettings = settingsManager.getSettings(vehicleId.toInt())
            if (dbSettings != null) {
                currentSettings = dbSettings
                Logging.d(TAG, "SERVICE: Settings read from DB before navigation start: simulate=${dbSettings.simulate}, devmode=${dbSettings.devmode}")
            } else {
                Logging.w(TAG, "SERVICE: No settings found in DB before navigation start, using current settings")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Failed to read settings from DB before navigation start: ${e.message}", e)
        }
        
        // Start navigation (route calculation happens inside startNavigation)
        Logging.d(TAG, "SERVICE: Calling startNavigation()... (isNavigating=${isNavigating.get()})")
        startNavigation(trip)
        
        // Verify state after navigation start
        Logging.d(TAG, "SERVICE: After startNavigation() - isNavigating=${isNavigating.get()}, hasActiveRoute=${hasActiveRouteForSync()}")
        
        // Don't overwrite notification here - RouteProgressListener will update it immediately
        // with navigation info (distance, speed, waypoint name) as soon as route progress starts
    }
    
    private suspend fun startNavigation(trip: TripResponse) {
        // Get simulate value from settings
        val simulate = currentSettings?.simulate ?: false
        
        // Use App.updateTripData() to start navigation (same as HeadlessNavigActivity)
        // App handles route calculation and navigation start internally
        if (app != null) {
            Logging.d(TAG, "SERVICE: Starting navigation via App.updateTripData()")
            withContext(Dispatchers.Main) {
                app?.updateTripData(newTripResponse = trip, isSimulated = simulate)
            }
            
            // Progress updates will start automatically when route calculation callback is invoked
            // (see initializeNavigationComponents() route calculation callback)
        } else {
            Logging.e(TAG, "SERVICE: App not initialized, cannot start navigation")
        }
    }
    
    // Route calculation and navigation start code removed - App handles this internally
    // App.updateTripData() handles:
    // - Location waiting (if not simulated)
    // - Route calculation
    // - Waypoint creation
    // - Navigation start
    // - Listener setup
    
    // Get progress from App's TripSectionValidator (same as HeadlessNavigActivity)
    private fun getProgressFromApp(): List<TripSectionValidator.WaypointProgressInfo> {
        return app?.getTripSectionValidator()?.getCurrentWaypointProgress() ?: emptyList()
    }
    
    // Update navigation display using App's progress data (same pattern as HeadlessNavigActivity)
    private fun updateNavigationDisplay() {
        if (!isNavigating.get()) return
        
        val waypointProgress = getProgressFromApp()
        val trip = currentTrip ?: return
        
        Logging.d(TAG, "SERVICE: updateNavigationDisplay: waypointProgress size=${waypointProgress.size}")
        
        // Get next waypoint info from progress - try multiple strategies
        var nextWaypoint = waypointProgress.find { it.isNext }
        
        // Fallback: if no waypoint has isNext=true, find first unpassed waypoint
        if (nextWaypoint == null && waypointProgress.isNotEmpty()) {
            nextWaypoint = waypointProgress.firstOrNull { !it.isPassed }
            Logging.d(TAG, "SERVICE: No waypoint with isNext=true, using first unpassed waypoint: ${nextWaypoint?.waypointName}")
        }
        
        val speedInKmh = getCurrentSpeedFromApp() * 3.6
        
        if (nextWaypoint != null) {
            val name = extractWaypointName(nextWaypoint.waypointName)
            
            // Update cache when valid data is available
            val distance = nextWaypoint.remainingDistanceInMeters
            val time = nextWaypoint.remainingTimeInSeconds
            
            if (distance != null && distance != Double.MAX_VALUE && distance > 0) {
                lastKnownDistance = distance
                lastKnownTime = time
                lastKnownWaypointName = name
            } else if (lastKnownWaypointName == name && lastKnownDistance != null) {
                // Use cached values if waypoint hasn't changed
                Logging.d(TAG, "SERVICE: Using cached distance for waypoint: $name")
            } else {
                // Waypoint changed or no cache - clear cache
                lastKnownDistance = null
                lastKnownTime = null
                lastKnownWaypointName = null
            }
            
            // Use current data if available, otherwise use cached data
            val displayDistance = distance ?: lastKnownDistance
            val displayTime = time ?: lastKnownTime
            
            Logging.d(TAG, "SERVICE: Next waypoint: $name, distance=$displayDistance, isNext=${nextWaypoint.isNext}")
            
            // Format navigation text - use displayDistance (current or cached)
            val navigationText = if (displayDistance != null && displayDistance != Double.MAX_VALUE && displayDistance > 0) {
                val distanceKm = displayDistance / 1000.0
                if (distanceKm < 0.01) {
                    "Speed: ${String.format("%.1f", speedInKmh)} km/h"
                } else {
                    "Next: ${String.format("%.1f", distanceKm)} km | Speed: ${String.format("%.1f", speedInKmh)} km/h"
                }
            } else {
                // Distance not available yet - show only speed
                "Speed: ${String.format("%.1f", speedInKmh)} km/h"
            }
            
            // Only update notification if Activity is NOT active
            // When Activity is active, Activity will update notification
            if (!isActivityActive()) {
                handler.post {
                    updateNotification(navigationText)
                }
            } else {
                Logging.d(TAG, "SERVICE: Activity is active, skipping notification update (Activity will update)")
            }
        } else {
            // No waypoint data available - show only speed
            val navigationText = "Speed: ${String.format("%.1f", speedInKmh)} km/h"
            
            // Only update notification if Activity is NOT active
            if (!isActivityActive()) {
                handler.post {
                    updateNotification(navigationText)
                }
            } else {
                Logging.d(TAG, "SERVICE: Activity is active, skipping notification update (Activity will update)")
            }
            Logging.d(TAG, "SERVICE: No waypoint progress data available, showing speed only")
        }
        
        // Check for completion - only if route is calculated and we have waypoint progress data
        if (isRouteCalculated && waypointProgress.isNotEmpty()) {
            val allWaypointsPassed = trip.waypoints.all { it.is_passed }
            val lastProgress = waypointProgress.lastOrNull()
            val remainingDistance = lastProgress?.remainingDistanceInMeters ?: 0.0
            
            if (allWaypointsPassed && remainingDistance <= 10.0) {
                isNavigating.set(false)
                handler.post {
                    updateNotification("Auto Mode: Waiting for trip...")
                }
                serviceScope.launch {
                    onNavigationComplete()
                }
            }
        } else if (!isRouteCalculated && waypointProgress.isEmpty()) {
            // Route not calculated yet - show calculating message
            if (!isActivityActive()) {
                handler.post {
                    updateNotification("Calculating route...")
                }
            }
        }
    }
    
    // Get current speed from App's NavigationExample (same as HeadlessNavigActivity)
    private fun getCurrentSpeedFromApp(): Double {
        return app?.getNavigationExample()?.let { navExample: com.gocavgo.validator.navigator.NavigationExample ->
            try {
                // Get speed from NavigationHandler's continuously updated speed (updated via NavigableLocationListener)
                navExample.getNavigationHandler()?.currentSpeedInMetersPerSecond ?: 0.0
            } catch (e: Exception) {
                0.0
            }
        } ?: 0.0
    }
    
    // Extract waypoint name (same as HeadlessNavigActivity)
    private fun extractWaypointName(waypointName: String): String {
        return when {
            waypointName.startsWith("Origin: ") -> waypointName.removePrefix("Origin: ")
            waypointName.startsWith("Destination: ") -> waypointName.removePrefix("Destination: ")
            waypointName.contains(": ") -> {
                val colonIndex = waypointName.indexOf(": ")
                if (colonIndex >= 0 && colonIndex < waypointName.length - 2) {
                    waypointName.substring(colonIndex + 2)
                } else {
                    waypointName
                }
            }
            else -> waypointName
        }
    }
    
    // Set up periodic progress updates (same pattern as HeadlessNavigActivity waypoint overlay)
    private fun startNavigationProgressUpdates() {
        // Start periodic updates to read progress from App's TripSectionValidator
        serviceScope.launch {
            while (isActive && isNavigating.get()) {
                updateNavigationDisplay()
                delay(2000) // Update every 2 seconds (same as HeadlessNavigActivity)
            }
        }
    }
    
    // Navigation is handled by App internally - no listener wrapping needed
    // Progress updates are done via periodic polling (same as HeadlessNavigActivity waypoint overlay)
    
    
    private suspend fun handleTripCancellation(cancelledTripId: Int) {
        Logging.d(TAG, "=== SERVICE: HANDLING TRIP CANCELLATION ===")
        Logging.d(TAG, "Cancelled Trip ID: $cancelledTripId")
        Logging.d(TAG, "Current Trip ID: ${currentTrip?.id}")
        
        // Clear countdown text
        countdownText = ""
        
        // Check if the cancelled trip is our current trip
        if (currentTrip?.id == cancelledTripId) {
            Logging.d(TAG, "SERVICE: Current trip was cancelled - stopping all activities")
            
            // Check if navigation is active before clearing state
            val wasNavigating = isNavigating.get()
            
            // Clear current trip FIRST so listener checks fail immediately
            currentTrip = null
            isNavigating.set(false)
            clearPersistedState() // Clear persisted state immediately
            
            // Stop navigation if active (this will clear listeners and reset validator)
            if (wasNavigating) {
                Logging.d(TAG, "SERVICE: Stopping active navigation")
                stopNavigationAndCleanup()
            } else {
                // Even if not navigating, still reset validator if it exists
                // App handles trip section validator cleanup internally
            }
            
            // Update notification immediately
            handler.post {
                updateNotification("Trip cancelled - Waiting for next trip...")
            }
            
            Logging.d(TAG, "SERVICE: Returned to waiting state after cancellation")
        } else {
            // Even if currentTrip doesn't match, clear countdown if it was running
            Logging.d(TAG, "SERVICE: Current trip doesn't match cancelled trip, clearing countdown state")
            currentTrip = null
            countdownText = ""
            handler.post {
                updateNotification("Trip cancelled - Waiting for next trip...")
            }
        }
        
        // Delete trip from database regardless
        try {
            databaseManager.deleteTripById(cancelledTripId)
            Logging.d(TAG, "SERVICE: Trip $cancelledTripId deleted from database")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Failed to delete trip from database: ${e.message}", e)
        }
    }
    
    /**
     * Handle trip deletion detected during navigation (from TripSectionValidator)
     * Called when TripSectionValidator detects trip is not found in database
     */
    private suspend fun handleTripDeletedDuringNavigation(deletedTripId: Int) {
        try {
            Logging.d(TAG, "=== SERVICE: HANDLING TRIP DELETION DURING NAVIGATION ===")
            Logging.d(TAG, "Deleted Trip ID: $deletedTripId")
            Logging.d(TAG, "Current Trip ID: ${currentTrip?.id}")
            
            // Check if this is the current trip
            if (currentTrip?.id == deletedTripId) {
                Logging.d(TAG, "SERVICE: Current trip was deleted during navigation - stopping navigation and transitioning to waiting state")
                
                // Check if navigation is active
                val wasNavigating = isNavigating.get()
                
                // Clear current trip
                currentTrip = null
                isNavigating.set(false)
                
                // Stop navigation if active
                if (wasNavigating) {
                    Logging.d(TAG, "SERVICE: Stopping navigation due to trip deletion")
                    stopNavigationAndCleanup()
                }
                
                // Clear persisted state
                clearPersistedState()
                
                // Transition to waiting state
                handler.post {
                    updateNotification("Auto Mode: Waiting for trip...")
                }
                
                Logging.d(TAG, "SERVICE: Transitioned to waiting state after trip deletion")
            } else {
                Logging.d(TAG, "SERVICE: Deleted trip $deletedTripId is not current trip (current: ${currentTrip?.id}), ignoring")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error handling trip deletion during navigation: ${e.message}", e)
        }
    }
    
    private fun stopNavigationAndCleanup() {
        try {
            Logging.d(TAG, "SERVICE: Stopping navigation and cleaning up...")
            
            // Clear cache when navigation stops
            lastKnownDistance = null
            lastKnownTime = null
            lastKnownWaypointName = null
            
            // Reset route calculation status
            isRouteCalculated = false
            
            // Stop navigation using App (same as HeadlessNavigActivity)
            app?.getNavigationExample()?.let { navExample: com.gocavgo.validator.navigator.NavigationExample ->
                navExample.stopHeadlessNavigation()
            }
            
            Logging.d(TAG, "SERVICE: Navigation stopped")
            
            // Reset trip section validator (stops processing)
            // App handles trip section validator cleanup internally
            
            // App handles route state internally
            
            Logging.d(TAG, "SERVICE: Navigation stopped and cleaned up")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error during navigation cleanup: ${e.message}", e)
        }
    }
    
    private suspend fun onNavigationComplete() {
        Logging.d(TAG, "=== SERVICE: NAVIGATION COMPLETE ===")
        
        // Clear cache when navigation completes
        lastKnownDistance = null
        lastKnownTime = null
        lastKnownWaypointName = null
        
        // Reset route calculation status
        isRouteCalculated = false
        
        isNavigating.set(false)
        currentTrip = null
        clearPersistedState() // Clear persisted state immediately
        
        // Read settings from DB on navigation completion (not from API)
        // Check devmode/deactivate/logout - Service doesn't route, but updates settings
        val vehicleId = vehicleSecurityManager.getVehicleId()
        try {
            val dbSettings = settingsManager.getSettings(vehicleId.toInt())
            if (dbSettings != null) {
                currentSettings = dbSettings
                Logging.d(TAG, "SERVICE: Settings read from DB after navigation: simulate=${dbSettings.simulate}, devmode=${dbSettings.devmode}, deactivate=${dbSettings.deactivate}, logout=${dbSettings.logout}")
                
                // Apply settings (Service doesn't route - Activity will handle routing)
                settingsManager.applySettings(this@AutoModeHeadlessForegroundService, dbSettings)
            } else {
                Logging.w(TAG, "SERVICE: No settings found in DB after navigation completion")
                // Optionally fetch from API in background to update DB
                serviceScope.launch {
        try {
            val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
            result.onSuccess { settings ->
                currentSettings = settings
                settingsManager.applySettings(this@AutoModeHeadlessForegroundService, settings)
            }.onFailure { error ->
                            Logging.e(TAG, "SERVICE: Failed to fetch settings from API after navigation: ${error.message}")
            }
        } catch (e: Exception) {
                        Logging.e(TAG, "SERVICE: Exception fetching settings from API after navigation: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Exception reading settings from DB on trip completion: ${e.message}", e)
        }
        
        // Reset to listening state
        withContext(Dispatchers.Main) {
            updateNotification("Auto Mode: Waiting for trip...")
        }
        
        Logging.d(TAG, "SERVICE: Returned to listening state")
    }
    
    // ========== PERIODIC BACKEND FETCH ==========
    
    private fun startPeriodicBackendFetch() {
        periodicFetchJob?.cancel()
        
        Logging.d(TAG, "SERVICE: Starting periodic backend fetch every 7 minutes")
        
        periodicFetchJob = serviceScope.launch {
            while (isActive) {
                delay(7 * 60 * 1000) // 7 minutes
                
                try {
                    // Skip if navigating
                    if (isNavigating.get()) {
                        Logging.d(TAG, "SERVICE: Navigation active, skipping periodic fetch")
                        continue
                    }
                    
                    // Only fetch if Activity is not active (to avoid duplicate fetches)
                    if (isActivityActive()) {
                        Logging.d(TAG, "SERVICE: Activity is active, deferring backend fetch to Activity")
                        continue
                    }
                    
                    performPeriodicFetch()
                } catch (e: Exception) {
                    Logging.e(TAG, "SERVICE: Error in periodic fetch: ${e.message}", e)
                }
            }
        }
    }
    
    private suspend fun performPeriodicFetch() {
        Logging.d(TAG, "=== SERVICE: PERIODIC BACKEND FETCH ===")
        
        // Check MQTT freshness (skip if MQTT updated within 2 minutes)
        if (!SyncCoordinator.shouldFetchFromBackend(this@AutoModeHeadlessForegroundService)) {
            Logging.d(TAG, "SERVICE: Skipping - MQTT data is fresh (within 2 minutes)")
            return
        }
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
        
        when {
            result.isSuccess() -> {
                val tripCount = result.getDataOrNull() ?: 0
                Logging.d(TAG, "SERVICE: Periodic sync successful: $tripCount trips")
                
                // Check if active trip changed - but only process if we're not in a cancelled state
                // Give a small delay after cancellation to avoid race conditions
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                
                if (activeTrip != null) {
                    // Verify trip still exists in database (wasn't just cancelled)
                    val tripInDb = databaseManager.getTripById(activeTrip.id)
                    if (tripInDb == null) {
                        Logging.d(TAG, "SERVICE: Active trip ${activeTrip.id} not found in database (likely cancelled), skipping")
                        return
                    }
                    
                    // Only handle if trip ID changed (new trip) or if we don't have a current trip
                    if (activeTrip.id != currentTrip?.id) {
                        Logging.d(TAG, "SERVICE: Active trip changed, updating: ${activeTrip.id} (previous: ${currentTrip?.id})")
                        handleTripReceived(activeTrip)
                    } else {
                        Logging.d(TAG, "SERVICE: Active trip ${activeTrip.id} is same as current trip, no update needed")
                    }
                } else {
                    // No active trip in database - if we have a current trip, it might have been cancelled
                    val currentTripId = currentTrip?.id
                    if (currentTripId != null) {
                        Logging.d(TAG, "SERVICE: No active trip in database but have current trip $currentTripId - might be cancelled")
                        // Don't clear currentTrip here - let MQTT cancellation handle it
                    }
                }
            }
            result.isError() -> {
                Logging.e(TAG, "SERVICE: Periodic sync failed: ${result.getErrorOrNull()}")
            }
        }
        
        Logging.d(TAG, "=== SERVICE: END PERIODIC FETCH ===")
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
    
    // Public methods for Activity state synchronization
    fun getCurrentTripForSync(): TripResponse? = currentTrip
    fun isNavigatingForSync(): Boolean = isNavigating.get()
    fun getCountdownTextForSync(): String = countdownText
    
    /**
     * Sync countdown from Activity state
     * Called when Activity goes to background to ensure Service continues countdown
     * @param trip The trip for which countdown is active
     * @param countdownText The current countdown text from Activity
     * @param remainingMs The remaining milliseconds until countdown completes
     */
    fun syncCountdownFromActivity(trip: TripResponse, countdownText: String, remainingMs: Long) {
        try {
            Logging.d(TAG, "SERVICE: Syncing countdown from Activity: trip=${trip.id}, remaining=${remainingMs}ms, text=$countdownText")
            
            // Validate trip matches (or set it if Service doesn't have one)
            if (currentTrip?.id != trip.id) {
                if (currentTrip == null) {
                    // Service doesn't have a trip yet - set it from Activity
                    Logging.d(TAG, "SERVICE: Setting trip from Activity: ${trip.id}")
                    currentTrip = trip
                } else {
                    Logging.d(TAG, "SERVICE: Trip mismatch - Activity trip ${trip.id} doesn't match Service trip ${currentTrip?.id}, not syncing")
                    return
                }
            }
            
            // Countdown is now handled by ActiveTripListener, so this method is no longer needed
            // Keeping it for compatibility but it does nothing
            Logging.d(TAG, "SERVICE: Countdown syncing is now handled by ActiveTripListener")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error syncing countdown from Activity: ${e.message}", e)
        }
    }
    
    /**
     * Check if Service's Navigator has an active route
     * This is used by Activity to verify if Service is actually navigating
     * (not just has isNavigating flag set)
     */
    fun hasActiveRouteForSync(): Boolean {
        return try {
            val route = app?.getNavigationExample()?.let { navExample: com.gocavgo.validator.navigator.NavigationExample ->
                navExample.getHeadlessNavigator()?.route
            }
            val hasRoute = route != null
            Logging.d(TAG, "SERVICE: hasActiveRouteForSync() = $hasRoute (route is ${if (hasRoute) "not null" else "null"})")
            hasRoute
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error checking active route: ${e.message}", e)
            false
        }
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
        
        // Show "Tap to reopen" subtitle when Activity is not active
        val subtitle = if (!isActivityActive()) {
            "Tap to reopen Auto Mode"
        } else {
            null
        }
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GoCavGo Auto Mode")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        
        // Add subtitle if Activity is not active
        if (subtitle != null) {
            builder.setSubText(subtitle)
        }
        
        return builder.build()
    }
    
    
    private fun formatTime(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}

