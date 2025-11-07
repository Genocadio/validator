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
import com.gocavgo.validator.navigator.MessageViewUpdater
import com.gocavgo.validator.navigator.NavigationExample
import com.gocavgo.validator.navigator.RouteCalculator
import com.gocavgo.validator.navigator.TripSectionValidator
import com.gocavgo.validator.receiver.TripConfirmationReceiver
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.sync.SyncCoordinator
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.util.TripDataConverter
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
    
    // Service state (separate from Activity state)
    private var currentTrip: TripResponse? = null
    private val isNavigating = AtomicBoolean(false)
    private var countdownJob: Job? = null
    private var countdownText = ""
    private val isAwaitingConfirmation = AtomicBoolean(false)
    private var confirmationTimeoutJob: Job? = null
    private var confirmationTripData: TripConfirmationData? = null
    
    // HERE SDK components for service
    private var navigationExample: NavigationExample? = null
    private var messageViewUpdater: MessageViewUpdater? = null
    private var routeCalculator: RouteCalculator? = null
    private var currentRoute: Route? = null
    private var currentUserLocation: Location? = null
    private var isNavigationStarted = false
    private var currentSpeedInMetersPerSecond: Double = 0.0
    private var tripSectionValidator: TripSectionValidator? = null
    
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
        fun isAwaitingConfirmation(): Boolean = isAwaitingConfirmation.get()
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
        
        // Register Service MQTT callback immediately (not in coroutine) to ensure it's set before Activity
        registerMqttTripCallback()
        
        // Set up connection state monitoring to re-register callback on reconnect
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
        
        // Re-register MQTT callback to ensure it's always set (Activity may have unregistered)
        verifyMqttCallbackRegistration()
        
        // Check MQTT connection and re-register if needed
        checkMqttConnectionAndCallback()
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        Logging.d(TAG, "=== AutoModeHeadlessForegroundService DESTROY STARTED ===")
        
        // Cancel all jobs
        countdownJob?.cancel()
        confirmationTimeoutJob?.cancel()
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
        
        // Stop navigation
        try {
            navigationExample?.stopHeadlessNavigation()
            navigationExample?.stopLocating()
            tripSectionValidator?.reset()
        } catch (e: Exception) {
            Logging.e(TAG, "Error stopping navigation: ${e.message}", e)
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
                Logging.e(TAG, "HERE SDK not initialized, cannot create NavigationExample")
                return
            }
            
            // Initialize trip section validator
            tripSectionValidator = TripSectionValidator(applicationContext)
            
            // Initialize MQTT service in trip section validator
            mqttService?.let {
                tripSectionValidator?.initializeMqttService(it)
                Logging.d(TAG, "MQTT service initialized in trip section validator")
            }
            
            // Set callback for trip deletion - when trip is not found in DB, transition to waiting state
            tripSectionValidator?.setTripDeletedCallback { deletedTripId ->
                Logging.w(TAG, "SERVICE: Trip $deletedTripId deleted during navigation - transitioning to waiting state")
                serviceScope.launch {
                    handleTripDeletedDuringNavigation(deletedTripId)
                }
            }
            
            // Create MessageViewUpdater for navigation updates
            messageViewUpdater = MessageViewUpdater()
            
            // Create RouteCalculator for route calculation
            routeCalculator = RouteCalculator()
            
            // Create NavigationExample with Application context (separate from Activity instance)
            navigationExample = NavigationExample(
                context = applicationContext,
                mapView = null, // Headless mode - no map view
                messageView = messageViewUpdater!!,
                tripSectionValidator = tripSectionValidator!!
            )
            
            // Start location provider
            navigationExample?.startLocationProvider()
            
            // Setup headless listeners
            navigationExample?.let { navExample ->
                val navigator = navExample.getHeadlessNavigator()
                val dynamicRoutingEngine = navExample.getDynamicRoutingEngine()
                val navigationHandler = navExample.getNavigationHandler()
                
                if (dynamicRoutingEngine != null) {
                    navigationHandler.setupHeadlessListeners(navigator, dynamicRoutingEngine)
                    Logging.d(TAG, "NavigationExample initialized successfully with headless listeners")
                } else {
                    Logging.e(TAG, "DynamicRoutingEngine is null, cannot setup headless listeners")
                }
            }
            
            Logging.d(TAG, "Navigation components initialized successfully in service")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize navigation components: ${e.message}", e)
        }
    }
    
    private fun registerMqttTripCallback() {
        if (mqttService == null) {
            Logging.w(TAG, "MQTT service is null, cannot register callback")
            return
        }
        
        Logging.d(TAG, "=== REGISTERING SERVICE MQTT TRIP CALLBACK ===")
        
        mqttService?.setServiceTripEventCallback { tripEvent ->
            // Router will automatically delegate to Activity if active, so we only get here if Activity is inactive
            // But double-check anyway to be safe
            if (isActivityActive()) {
                Logging.d(TAG, "Activity is active, this callback should not be called (router should have delegated to Activity)")
                return@setServiceTripEventCallback
            }
            
            Logging.d(TAG, "=== MQTT TRIP EVENT RECEIVED IN SERVICE ===")
            Logging.d(TAG, "Event: ${tripEvent.event}")
            Logging.d(TAG, "Trip ID: ${tripEvent.data.id}")
            
            // Check if trip is for this vehicle
            val vehicleId = vehicleSecurityManager.getVehicleId()
            if (tripEvent.data.vehicle_id != vehicleId.toInt()) {
                Logging.d(TAG, "Trip is for different vehicle, ignoring")
                return@setServiceTripEventCallback
            }
            
            // Handle cancellation event
            if (tripEvent.event == "TRIP_CANCELLED") {
                Logging.d(TAG, "Processing TRIP_CANCELLED event in service")
                serviceScope.launch {
                    handleTripCancellation(tripEvent.data.id)
                }
                return@setServiceTripEventCallback
            }
            
            // Check if this is the same trip we're already handling
            val tripId = tripEvent.data.id
            val isSameTrip = currentTrip?.id == tripId
            
            // Ignore new trips if already navigating a different trip
            if (isNavigating.get() && !isSameTrip) {
                Logging.w(TAG, "Navigation active for different trip, ignoring new trip")
                return@setServiceTripEventCallback
            }
            
            serviceScope.launch {
                try {
                    val trip = convertBackendTripToAndroid(tripEvent.data)
                    
                    // MQTT service saves trip to database asynchronously, so there might be a small delay
                    // Wait a bit for database save to complete, then verify
                    var tripInDb: TripResponse? = null
                    var retries = 0
                    while (tripInDb == null && retries < 5) {
                        delay(100) // Wait 100ms between retries
                        tripInDb = try {
                            databaseManager.getTripById(trip.id)
                        } catch (e: Exception) {
                            null
                        }
                        retries++
                    }
                    
                    // If trip still not in DB after retries, it might have been cancelled or save failed
                    // But we'll still process it since MQTT is authoritative
                    if (tripInDb == null) {
                        Logging.w(TAG, "SERVICE: Trip ${trip.id} not in database after retries, but processing from MQTT data (MQTT is authoritative)")
                    }
                    
                    // If trip is IN_PROGRESS and we're awaiting confirmation, clear confirmation immediately
                    if (trip.status.equals("IN_PROGRESS", ignoreCase = true) && isAwaitingConfirmation.get()) {
                        Logging.d(TAG, "MQTT trip is IN_PROGRESS - clearing confirmation state")
                        isAwaitingConfirmation.set(false)
                        confirmationTimeoutJob?.cancel()
                        cancelConfirmationNotification()
                    }
                    
                    // Process trip - this will start countdown/navigation automatically
                    handleTripReceived(trip)
                    
                    // Record MQTT update for sync coordination
                    SyncCoordinator.recordMqttUpdate(this@AutoModeHeadlessForegroundService)
                    Logging.d(TAG, "Recorded MQTT update for sync coordination")
                } catch (e: Exception) {
                    Logging.e(TAG, "Error handling MQTT trip: ${e.message}", e)
                }
            }
        }
        
        Logging.d(TAG, "=== SERVICE MQTT TRIP CALLBACK REGISTERED ===")
        Logging.d(TAG, "Service callback will handle trips when Activity is inactive")
    }
    
    /**
     * Verify MQTT callback is registered, re-register if missing
     */
    private fun verifyMqttCallbackRegistration() {
        // Check if callback is still registered by checking if we can get MQTT service
        // Note: We can't directly check if callback is set, so we re-register to be safe
        if (mqttService == null) {
            Logging.w(TAG, "MQTT service is null, attempting to initialize...")
            initializeMqttService()
        }
        
        if (mqttService != null) {
            Logging.d(TAG, "Verifying MQTT callback registration...")
            registerMqttTripCallback()
        } else {
            Logging.e(TAG, "MQTT service is still null after initialization attempt")
        }
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
                            Logging.d(TAG, "Processing START_CONFIRMED action in service")
                            serviceScope.launch {
                                handleConfirmStart()
                            }
                        }
                        TripConfirmationReceiver.ACTION_CANCEL_CONFIRMED -> {
                            Logging.d(TAG, "Processing CANCEL_CONFIRMED action in service")
                            serviceScope.launch {
                                handleConfirmCancel()
                            }
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
            editor.putBoolean("is_awaiting_confirmation", isAwaitingConfirmation.get())
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
            
            // Add confirmation timestamp if awaiting confirmation
            if (isAwaitingConfirmation.get()) {
                editor.putLong("confirmation_start_timestamp", currentTime)
            } else {
                editor.remove("confirmation_start_timestamp")
            }
            
            editor.apply()
            Logging.d(TAG, "SERVICE: State saved to persistence (trip=${currentTrip?.id}, navigating=${isNavigating.get()}, countdown=${countdownText.isNotEmpty()}, confirmation=${isAwaitingConfirmation.get()})")
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
            val wasAwaitingConfirmation = prefs.getBoolean("is_awaiting_confirmation", false)
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
                    isAwaitingConfirmation.set(wasAwaitingConfirmation)
                    
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
        
        // For MQTT trips, we process them directly (MQTT is authoritative)
        // Only verify database if this was from periodic fetch (not MQTT)
        // For now, we'll process all trips but do a quick check
        val tripInDb = try {
            databaseManager.getTripById(trip.id)
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error checking trip in database: ${e.message}", e)
            null
        }
        
        // If trip not in DB and it's not our current trip, it might have been cancelled
        // But if it's a new trip (different ID), process it anyway (might be from MQTT before DB save)
        if (tripInDb == null && currentTrip?.id == trip.id) {
            Logging.w(TAG, "SERVICE: Trip ${trip.id} not found in database and matches current trip (likely cancelled), ignoring")
            currentTrip = null
            countdownJob?.cancel()
            countdownJob = null
            countdownText = ""
            clearPersistedState() // Clear persisted state immediately
            withContext(Dispatchers.Main) {
                updateNotification("Trip cancelled - Waiting for next trip...")
            }
            return
        }
        
        // If trip not in DB but it's a new trip, log warning but continue (MQTT might be faster than DB)
        // MQTT is authoritative - process the trip even if not in DB yet
        if (tripInDb == null && currentTrip?.id != trip.id) {
            Logging.w(TAG, "SERVICE: Trip ${trip.id} not in database yet (might be from MQTT before DB save), processing anyway - MQTT is authoritative")
        }
        
        // Cancel any existing countdown
        countdownJob?.cancel()
        countdownJob = null // Clear reference
        
        // Save as current trip
        currentTrip = trip
        saveStateToPersistence() // Persist state change immediately
        
        Logging.d(TAG, "SERVICE: Processing trip ${trip.id} - will start countdown/navigation automatically based on departure time")
        
        // CHECK FOR IN_PROGRESS STATUS FIRST - Skip all confirmation logic
        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "=== SERVICE: TRIP IS IN_PROGRESS - RESUMING NAVIGATION ===")
            val passedCount = trip.waypoints.count { it.is_passed }
            val totalCount = trip.waypoints.size
            Logging.d(TAG, "Waypoints passed: $passedCount / $totalCount")
            
            val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
            val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
            updateNotification("Resuming navigation: $origin → $destination")
            
            // Reset navigation flags to allow resuming
            isNavigationStarted = false
            currentRoute = null
            
            // Start navigation immediately, bypassing all countdown/confirmation logic
            startNavigationInternal(trip, allowResume = true)
            return
        }
        
        // Original logic for SCHEDULED trips
        val departureTimeMillis = trip.departure_time * 1000
        val currentTime = System.currentTimeMillis()
        val twoMinutesInMs = 2 * 60 * 1000
        
        Logging.d(TAG, "Departure: $departureTimeMillis")
        Logging.d(TAG, "Current time: $currentTime")
        
        if (currentTime >= departureTimeMillis) {
            // LATE: Actual departure time has passed - show confirmation
            if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                Logging.d(TAG, "Trip is IN_PROGRESS even though departure time passed - starting navigation directly")
                startNavigationInternal(trip, allowResume = true)
                return
            }
            
            val delayMinutes = ((currentTime - departureTimeMillis) / (60 * 1000)).toInt()
            Logging.d(TAG, "Trip is late by $delayMinutes minutes, requesting confirmation")
            showTripConfirmation(trip, delayMinutes)
        } else {
            val timeUntilDeparture = departureTimeMillis - currentTime
            
            if (timeUntilDeparture <= twoMinutesInMs) {
                // Within 2-minute window - start immediately (no countdown)
                Logging.d(TAG, "Within 2-minute window (${timeUntilDeparture}ms until departure), starting navigation immediately")
                val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                updateNotification("Starting navigation: $origin → $destination")
                startNavigationInternal(trip)
            } else {
                // More than 2 minutes before departure - schedule countdown
                val delayUntilCountdownStart = timeUntilDeparture - twoMinutesInMs
                Logging.d(TAG, "Scheduling countdown to start in ${delayUntilCountdownStart}ms (${delayUntilCountdownStart / 60000} minutes)")
                val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                updateNotification("Trip scheduled: $origin → $destination")
                
                startCountdown(delayUntilCountdownStart, trip)
            }
        }
    }
    
    private fun startCountdown(delayMs: Long, trip: TripResponse) {
        countdownJob?.cancel()
        countdownJob = null // Clear reference immediately
        
        // Store trip ID for validation
        val tripId = trip.id
        
        Logging.d(TAG, "SERVICE: Starting countdown for trip $tripId, delay: ${delayMs}ms")
        
        countdownJob = serviceScope.launch {
            var remainingMs = delayMs
            
            while (remainingMs > 0 && coroutineContext.isActive) {
                // FIRST: Check if job was cancelled (highest priority check)
                if (!coroutineContext.isActive) {
                    Logging.d(TAG, "SERVICE: Countdown job cancelled, stopping immediately")
                    countdownText = ""
                    break
                }
                
                // SECOND: Check if trip is still valid (not cancelled and still current trip)
                // Check BEFORE any delay to catch cancellation immediately
                val currentTripId = currentTrip?.id
                if (currentTripId != tripId) {
                    Logging.d(TAG, "SERVICE: Trip $tripId is no longer current (current: $currentTripId), stopping countdown immediately")
                    countdownText = ""
                    withContext(Dispatchers.Main) {
                        updateNotification("Auto Mode: Waiting for trip...")
                    }
                    break
                }
                
                // Verify trip still exists in database (might have been cancelled)
                val tripInDb = try {
                    databaseManager.getTripById(tripId)
                } catch (e: Exception) {
                    null
                }
                
                if (tripInDb == null) {
                    Logging.d(TAG, "SERVICE: Trip $tripId no longer exists in database (cancelled), stopping countdown")
                    countdownText = ""
                    currentTrip = null
                    withContext(Dispatchers.Main) {
                        updateNotification("Trip cancelled - Waiting for next trip...")
                    }
                    break
                }
                
                val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
                
                // Check cancellation again before updating UI (after delay might have completed)
                if (!coroutineContext.isActive) {
                    Logging.d(TAG, "SERVICE: Countdown job cancelled during delay, stopping")
                    countdownText = ""
                    break
                }
                
                val countdown = "Depart in: %02d:%02d".format(minutes, seconds)
                countdownText = countdown
                // Save state periodically during countdown (every 10 seconds to avoid excessive writes)
                if (remainingMs % 10000 < 1000) {
                    saveStateToPersistence()
                }
                
                // Final check before updating notification - ensure job is still active and trip is valid
                if (coroutineContext.isActive && currentTrip?.id == tripId && tripInDb != null) {
                    withContext(Dispatchers.Main) {
                        // Double-check cancellation hasn't happened during context switch
                        if (coroutineContext.isActive && currentTrip?.id == tripId) {
                            updateNotification("$countdown - ${trip.route.origin.google_place_name}")
                        }
                    }
                } else {
                    Logging.d(TAG, "SERVICE: Trip invalid or job cancelled during countdown update, stopping")
                    countdownText = ""
                    break
                }
                
                // Use ensureActive() before delay to check cancellation immediately
                ensureActive()
                delay(1000)
                remainingMs -= 1000
            }
            
            // Countdown complete - launch navigation only if trip is still valid
            if (coroutineContext.isActive && remainingMs <= 0) {
                // Final validation before starting navigation
                val finalTripId = currentTrip?.id
                val finalTripInDb = try {
                    databaseManager.getTripById(tripId)
                } catch (e: Exception) {
                    null
                }
                
                if (finalTripId == tripId && finalTripInDb != null) {
                    withContext(Dispatchers.Main) {
                        Logging.d(TAG, "SERVICE: Countdown complete, launching navigation")
                        countdownText = ""
                        updateNotification("Starting navigation...")
                        startNavigationInternal(trip)
                    }
                } else {
                    Logging.d(TAG, "SERVICE: Countdown completed but trip $tripId is no longer valid (current: $finalTripId, in DB: ${finalTripInDb != null}), not starting navigation")
                    countdownText = ""
                    withContext(Dispatchers.Main) {
                        updateNotification("Auto Mode: Waiting for trip...")
                    }
                }
            } else {
                countdownText = ""
            }
        }
    }
    
    private suspend fun startNavigationInternal(trip: TripResponse, allowResume: Boolean = false) {
        // Check if navigation is already active - but allow resume if explicitly requested
        if (isNavigating.get() && !allowResume) {
            Logging.w(TAG, "SERVICE: Navigation already active, ignoring start request")
            return
        }
        
        Logging.d(TAG, "=== SERVICE: STARTING NAVIGATION INTERNALLY ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Allow resume: $allowResume")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")
        
        // Clear any confirmation state (safety check)
        if (isAwaitingConfirmation.get()) {
            Logging.d(TAG, "SERVICE: Clearing confirmation state before starting navigation")
            isAwaitingConfirmation.set(false)
            confirmationTimeoutJob?.cancel()
            cancelConfirmationNotification()
            saveStateToPersistence() // Persist state change immediately
        }
        
        // Reset navigation started flag to allow route calculation
        if (allowResume) {
            Logging.d(TAG, "SERVICE: Resuming navigation - resetting navigation flags")
            isNavigationStarted = false
            currentRoute = null
        }
        
        isNavigating.set(true)
        currentTrip = trip
        saveStateToPersistence() // Persist state change immediately
        
        // Update trip status to IN_PROGRESS when navigation starts
        try {
            val currentStatus = trip.status
            if (!currentStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                Logging.d(TAG, "SERVICE: Trip ${trip.id} status updated from $currentStatus to IN_PROGRESS")
                
                // Update local trip status
                currentTrip = trip.copy(status = "IN_PROGRESS")
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
        
        // Start navigation
        Logging.d(TAG, "SERVICE: Calling startNavigation()...")
        startNavigation(trip)
        
        // Don't overwrite notification here - RouteProgressListener will update it immediately
        // with navigation info (distance, speed, waypoint name) as soon as route progress starts
    }
    
    private suspend fun startNavigation(trip: TripResponse) {
        // Get simulate value from settings
        val simulate = currentSettings?.simulate ?: false
        
        if (!simulate) {
            startLocationEngineAndWaitForLocation(trip)
        } else {
            isNavigationStarted = true
            calculateRouteAndStartNavigation(trip)
        }
    }
    
    private suspend fun startLocationEngineAndWaitForLocation(trip: TripResponse) {
        navigationExample?.let { navExample ->
            Logging.d(TAG, "SERVICE: Waiting for location from NavigationExample location provider...")
            
            // Check if we already have a valid location
            if (navExample.hasValidLocation()) {
                currentUserLocation = navExample.getLastKnownLocation()
                if (currentUserLocation != null) {
                    Logging.d(TAG, "SERVICE: Location already available, starting navigation...")
                    isNavigationStarted = true
                    calculateRouteAndStartNavigation(trip)
                    return
                }
            }
            
            // Wait for location with timeout
            var attempts = 0
            val maxAttempts = 10 // 10 seconds total
            
            while (attempts < maxAttempts) {
                delay(1000)
                attempts++
                
                if (navExample.hasValidLocation()) {
                    currentUserLocation = navExample.getLastKnownLocation()
                    if (currentUserLocation != null && !isNavigationStarted) {
                        Logging.d(TAG, "SERVICE: Location acquired after $attempts attempts, starting navigation...")
                        isNavigationStarted = true
                        calculateRouteAndStartNavigation(trip)
                        return
                    }
                }
            }
            
            Logging.w(TAG, "SERVICE: No location received within timeout, falling back to simulated navigation")
            isNavigationStarted = true
            calculateRouteAndStartNavigation(trip)
        } ?: run {
            Logging.w(TAG, "SERVICE: NavigationExample not available, falling back to simulated navigation")
            isNavigationStarted = true
            calculateRouteAndStartNavigation(trip)
        }
    }
    
    private suspend fun calculateRouteAndStartNavigation(trip: TripResponse) {
        // Get simulate value from settings
        val simulate = currentSettings?.simulate ?: false
        
        var origin: Waypoint? = null
        if (currentUserLocation == null && !simulate) {
            Logging.w(TAG, "SERVICE: No current user location available, falling back to simulated navigation")
        } else {
            currentUserLocation?.let {
                origin = Waypoint(it.coordinates)
                origin!!.headingInDegrees = it.bearingInDegrees
            }
        }
        val waypoints = createWaypointsFromTrip(trip, origin)
        
        Logging.d(TAG, "SERVICE: Calculating route")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Waypoints: ${waypoints.size}")
        
        // Use RouteCalculator for route calculation
        withContext(Dispatchers.Main) {
            routeCalculator?.calculateRouteWithWaypoints(waypoints) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val route = routes[0]
                    currentRoute = route
                    
                    Logging.d(TAG, "SERVICE: Route calculated successfully")
                    serviceScope.launch {
                        startHeadlessGuidance(route, trip)
                    }
                } else {
                    Logging.e(TAG, "SERVICE: Route calculation failed: ${routingError?.name}")
                }
            }
        }
    }
    
    private fun createWaypointsFromTrip(trip: TripResponse, origin: Waypoint? = null): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        
        if (origin != null) {
            waypoints.add(origin)
        }
        
        // Add origin if not using device location
        if (origin == null) {
            val useSavedLocation = trip.status.equals("IN_PROGRESS", ignoreCase = true) &&
                                   trip.vehicle.current_latitude != null &&
                                   trip.vehicle.current_longitude != null
            
            val originWaypoint = if (useSavedLocation) {
                Logging.d(TAG, "SERVICE: Using saved vehicle location for IN_PROGRESS trip (simulated mode)")
                Waypoint(
                    GeoCoordinates(
                        trip.vehicle.current_latitude!!,
                        trip.vehicle.current_longitude!!
                    )
                )
            } else {
                Waypoint(
                    GeoCoordinates(
                        trip.route.origin.latitude,
                        trip.route.origin.longitude
                    )
                )
            }
            
            originWaypoint.apply {
                type = WaypointType.STOPOVER
            }
            waypoints.add(originWaypoint)
        }
        
        // Filter out passed waypoints - only include unpassed waypoints
        val sortedWaypoints = trip.waypoints.sortedBy { it.order }
        val unpassedWaypoints = sortedWaypoints.filter { !it.is_passed }
        
        // Add only unpassed intermediate waypoints
        unpassedWaypoints.forEach { tripWaypoint ->
            val waypoint = Waypoint(
                GeoCoordinates(
                    tripWaypoint.location.latitude,
                    tripWaypoint.location.longitude
                )
            ).apply {
                type = WaypointType.STOPOVER
            }
            waypoints.add(waypoint)
        }
        
        // Add destination
        val destination = Waypoint(
            GeoCoordinates(
                trip.route.destination.latitude,
                trip.route.destination.longitude
            )
        ).apply {
            type = WaypointType.STOPOVER
        }
        waypoints.add(destination)
        
        return waypoints
    }
    
    private suspend fun startHeadlessGuidance(route: Route, trip: TripResponse) {
        navigationExample?.let { navExample ->
            // Verify route sections with trip section validator
            val skippedCount = trip.waypoints.count { it.is_passed }
            
            val simulate = currentSettings?.simulate ?: false
            
            val isVerified = tripSectionValidator?.verifyRouteSections(
                tripResponse = trip,
                route = route,
                isSimulated = simulate,
                deviceLocation = currentUserLocation?.coordinates,
                skippedWaypointCount = skippedCount
            ) ?: false
            Logging.d(TAG, "SERVICE: Route verification result: $isVerified")
            
            // Start headless navigation using NavigationExample
            navExample.startHeadlessNavigation(route, simulate)
            
            // Setup navigation listeners for service
            val navigator = navExample.getHeadlessNavigator()
            
            // Wrap navigable location listener to update currentSpeedInMetersPerSecond
            val originalNavigableLocationListener = navigator.navigableLocationListener
            navigator.navigableLocationListener = NavigableLocationListener { currentNavigableLocation ->
                // Call original listener if it exists
                originalNavigableLocationListener?.let { listener ->
                    listener.onNavigableLocationUpdated(currentNavigableLocation)
                }
                
                // Update current speed for navigation display
                try {
                    val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond ?: 0.0
                    currentSpeedInMetersPerSecond = speed
                } catch (e: Exception) {
                    Logging.e(TAG, "SERVICE: Error updating speed: ${e.message}", e)
                }
            }
            
            // Wrap destination reached listener
            val originalDestinationReachedListener = navigator.destinationReachedListener
            navigator.destinationReachedListener = DestinationReachedListener {
                // Call original listener if it exists
                originalDestinationReachedListener?.let {
                    it.onDestinationReached()
                }
                
                // Handle navigation complete
                serviceScope.launch {
                    onNavigationComplete()
                }
            }
            
            // Wrap route progress listener
            val originalRouteProgressListener = navigator.routeProgressListener
            // Capture trip ID for validation
            val tripIdForListener = trip.id
            navigator.routeProgressListener = RouteProgressListener { routeProgress ->
                // FIRST: Check if trip is still valid before processing anything
                if (currentTrip?.id != tripIdForListener) {
                    Logging.d(TAG, "SERVICE: Trip $tripIdForListener no longer current (current: ${currentTrip?.id}), ignoring route progress update")
                    return@RouteProgressListener
                }
                
                // SECOND: Check if navigation is still active
                if (!isNavigating.get()) {
                    Logging.d(TAG, "SERVICE: Navigation no longer active, ignoring route progress update")
                    return@RouteProgressListener
                }
                
                // Call original listener if it exists
                originalRouteProgressListener?.let { listener ->
                    listener.onRouteProgressUpdated(routeProgress)
                }
                
                // Process section progress
                try {
                    val sectionProgressList = routeProgress.sectionProgress
                    val totalSections = sectionProgressList.size
                    tripSectionValidator?.processSectionProgress(sectionProgressList, totalSections)
                    
                    // Get next waypoint info
                    var nextWaypointInfo = getNextWaypointInfo(trip)
                    
                    // If waypoint info is null but we have route progress, use first section data
                    if (nextWaypointInfo == null && sectionProgressList.isNotEmpty()) {
                        val firstSection = sectionProgressList.firstOrNull()
                        if (firstSection != null) {
                            val remainingDistance = firstSection.remainingDistanceInMeters?.toDouble() ?: 0.0
                            if (remainingDistance > 0) {
                                // Get waypoint name from trip data
                                val nextWaypoint = trip.waypoints.firstOrNull { !it.is_passed }
                                val waypointName = if (nextWaypoint != null) {
                                    nextWaypoint.location.custom_name ?: nextWaypoint.location.google_place_name
                                } else {
                                    trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                                }
                                nextWaypointInfo = NextWaypointInfo(waypointName, remainingDistance)
                                Logging.d(TAG, "SERVICE: Using route progress data directly (waypoint progress not available yet)")
                            }
                        }
                    }
                    
                    // Get current speed
                    val speedInKmh = currentSpeedInMetersPerSecond * 3.6
                    
                    // Check if trip is completed
                    val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                    val lastSection = sectionProgressList.lastOrNull()
                    val remainingDistanceToDestination = lastSection?.remainingDistanceInMeters?.toDouble() ?: 0.0
                    
                    if (nextWaypointInfo == null || (allWaypointsPassed && remainingDistanceToDestination <= 10.0)) {
                        Logging.d(TAG, "SERVICE: Trip completed - remaining distance: ${remainingDistanceToDestination}m")
                        serviceScope.launch {
                            onNavigationComplete()
                        }
                        return@RouteProgressListener
                    }
                    
                    // Format navigation text
                    val navigationText = if (nextWaypointInfo.remainingDistanceInMeters == Double.MAX_VALUE) {
                        // Under timer or no distance data - show waypoint name and speed
                        "${nextWaypointInfo.name} | ${String.format("%.1f", speedInKmh)} km/h"
                    } else {
                        val remainingDistanceKm = nextWaypointInfo.remainingDistanceInMeters / 1000.0
                        if (remainingDistanceKm < 0.01) {
                            // Very close to waypoint - show just speed
                            "Next: ${String.format("%.1f", speedInKmh)} km/h"
                        } else {
                            // Normal navigation - show distance and speed
                            "Next: ${String.format("%.1f", remainingDistanceKm)} km | Speed: ${String.format("%.1f", speedInKmh)} km/h"
                        }
                    }
                    
                    // Update notification
                    handler.post {
                        updateNotification(navigationText)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "SERVICE: Error extracting navigation info: ${e.message}", e)
                }
            }
            
            // Update current route reference
            currentRoute = route
            
            // Don't set notification here - RouteProgressListener will update it with navigation info
            // (distance, speed, waypoint name) as soon as route progress starts
            Logging.d(TAG, "SERVICE: Navigation started - RouteProgressListener will update notification with navigation info")
        }
    }
    
    private fun getNextWaypointInfo(trip: TripResponse): NextWaypointInfo? {
        try {
            // PRIORITY 1: Get waypoint progress from TripSectionValidator (most accurate, real-time data)
            // This should be available as soon as route progress starts
            val waypointProgress = tripSectionValidator?.getCurrentWaypointProgress() ?: emptyList()
            
            if (waypointProgress.isNotEmpty()) {
                // We have waypoint progress data - use it (even if departure time hasn't passed)
                // Find the waypoint with isNext = true
                val nextWaypoint = waypointProgress.find { it.isNext }
                if (nextWaypoint != null) {
                    val cleanName = extractWaypointName(nextWaypoint.waypointName)
                    val remainingDistance = nextWaypoint.remainingDistanceInMeters
                    
                    // If we have valid distance data, use it
                    if (remainingDistance != null && remainingDistance != Double.MAX_VALUE && remainingDistance > 0) {
                        return NextWaypointInfo(cleanName, remainingDistance)
                    }
                    // If distance is MAX_VALUE or null, check if trip is completed
                    val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                    if (allWaypointsPassed) {
                        // Check destination progress
                        val destinationProgress = waypointProgress.find { it.waypointName.startsWith("Destination: ") }
                        if (destinationProgress != null) {
                            val destDistance = destinationProgress.remainingDistanceInMeters
                            if (destDistance != null && destDistance > 10.0) {
                                val cleanDestName = extractWaypointName(destinationProgress.waypointName)
                                return NextWaypointInfo(cleanDestName, destDistance)
                            } else {
                                return null // Trip completed
                            }
                        } else {
                            return null // Trip completed
                        }
                    }
                }
            }
            
            // PRIORITY 2: Check if trip is under timer (departure time not started)
            // Only use this if we don't have waypoint progress data yet
            val departureTimeMillis = trip.departure_time * 1000
            val currentTime = System.currentTimeMillis()
            if (currentTime < departureTimeMillis && waypointProgress.isEmpty()) {
                val originName = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                return NextWaypointInfo(originName, Double.MAX_VALUE)
            }
            
            // PRIORITY 3: Check if trip is completed (fallback)
            val allWaypointsPassed = trip.waypoints.all { it.is_passed }
            if (allWaypointsPassed) {
                if (waypointProgress.isNotEmpty()) {
                    val destinationProgress = waypointProgress.find { it.waypointName.startsWith("Destination: ") }
                    if (destinationProgress != null) {
                        val remainingDistance = destinationProgress.remainingDistanceInMeters
                        if (remainingDistance == null || remainingDistance <= 10.0) {
                            return null // Trip completed
                        }
                        val cleanName = extractWaypointName(destinationProgress.waypointName)
                        return NextWaypointInfo(cleanName, remainingDistance)
                    }
                }
                return null // Trip completed
            }
            
            // If we have waypoint progress but no "next" waypoint, try to get first unpassed waypoint
            if (waypointProgress.isNotEmpty()) {
                val firstUnpassed = waypointProgress.firstOrNull()
                if (firstUnpassed != null) {
                    val cleanName = extractWaypointName(firstUnpassed.waypointName)
                    val remainingDistance = firstUnpassed.remainingDistanceInMeters ?: Double.MAX_VALUE
                    return NextWaypointInfo(cleanName, remainingDistance)
                }
            }
            
            // No waypoint progress available yet - return null to avoid showing stale data
            return null
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error getting next waypoint info: ${e.message}", e)
            return null
        }
    }
    
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
    
    private data class NextWaypointInfo(
        val name: String,
        val remainingDistanceInMeters: Double
    )
    
    private suspend fun showTripConfirmation(trip: TripResponse, delayMinutes: Int) {
        // Safety check: Never show confirmation for IN_PROGRESS trips
        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "SERVICE: Trip is IN_PROGRESS - skipping confirmation and starting navigation directly")
            startNavigationInternal(trip, allowResume = true)
            return
        }
        
        isAwaitingConfirmation.set(true)
        saveStateToPersistence() // Persist confirmation state immediately
        
        // Format times
        val expectedTime = formatTime(trip.departure_time * 1000)
        val currentTime = formatTime(System.currentTimeMillis())
        
        // Create confirmation data
        confirmationTripData = TripConfirmationData(
            trip = trip,
            expectedDepartureTime = expectedTime,
            currentTime = currentTime,
            delayMinutes = delayMinutes
        )
        
        // Show notification for background confirmation
        withContext(Dispatchers.Main) {
            showConfirmationNotification(trip, delayMinutes)
        }
        
        // Start 3-minute auto-start timeout
        startConfirmationTimeout(trip)
        
        withContext(Dispatchers.Main) {
            updateNotification("Trip confirmation required - ${delayMinutes}min late")
        }
    }
    
    private fun startConfirmationTimeout(trip: TripResponse) {
        confirmationTimeoutJob?.cancel()
        
        confirmationTimeoutJob = serviceScope.launch {
            delay(3 * 60 * 1000) // 3 minutes
            
            if (isAwaitingConfirmation.get()) {
                // Double-check trip status before auto-starting
                val currentTripStatus = currentTrip?.status ?: trip.status
                if (currentTripStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                    Logging.d(TAG, "SERVICE: Trip became IN_PROGRESS during confirmation timeout - starting navigation directly")
                    startNavigationInternal(trip, allowResume = true)
                } else {
                    Logging.d(TAG, "SERVICE: Confirmation timeout - auto-starting navigation")
                    handleConfirmStart()
                }
            }
        }
    }
    
    private suspend fun handleConfirmStart() {
        Logging.d(TAG, "=== SERVICE: HANDLE CONFIRM START ===")
        
        // Safety check: If trip is IN_PROGRESS, skip confirmation and start navigation directly
        val trip = confirmationTripData?.trip ?: currentTrip
        if (trip != null && trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "SERVICE: Trip is IN_PROGRESS - skipping confirmation and starting navigation directly")
            isAwaitingConfirmation.set(false)
            confirmationTimeoutJob?.cancel()
            cancelConfirmationNotification()
            startNavigationInternal(trip, allowResume = true)
            return
        }
        
        // If already navigating, just clear confirmation state
        if (isNavigating.get()) {
            Logging.d(TAG, "SERVICE: Already navigating, just clearing confirmation state")
            isAwaitingConfirmation.set(false)
            confirmationTimeoutJob?.cancel()
            cancelConfirmationNotification()
            return
        }
        
        val confirmTrip = confirmationTripData?.trip
        if (confirmTrip == null) {
            Logging.e(TAG, "SERVICE: No trip data available for confirmation")
            return
        }
        
        // Clear confirmation state
        isAwaitingConfirmation.set(false)
        confirmationTimeoutJob?.cancel()
        cancelConfirmationNotification()
        saveStateToPersistence() // Persist state change immediately
        
        Logging.d(TAG, "SERVICE: User confirmed trip start, initiating navigation")
        startNavigationInternal(confirmTrip)
    }
    
    private suspend fun handleConfirmCancel() {
        Logging.d(TAG, "=== SERVICE: HANDLE CONFIRM CANCEL ===")
        
        isAwaitingConfirmation.set(false)
        confirmationTimeoutJob?.cancel()
        cancelConfirmationNotification()
        
        Logging.d(TAG, "SERVICE: User cancelled late trip")
        currentTrip = null
        confirmationTripData = null
        clearPersistedState() // Clear persisted state immediately
        
        // Return to waiting state
        withContext(Dispatchers.Main) {
            updateNotification("Auto Mode: Waiting for trip...")
        }
    }
    
    private suspend fun handleTripCancellation(cancelledTripId: Int) {
        Logging.d(TAG, "=== SERVICE: HANDLING TRIP CANCELLATION ===")
        Logging.d(TAG, "Cancelled Trip ID: $cancelledTripId")
        Logging.d(TAG, "Current Trip ID: ${currentTrip?.id}")
        
        // ALWAYS cancel countdown if it exists - don't check currentTrip first
        // The countdown might be running even if currentTrip is null (race condition)
        val shouldCancelCountdown = countdownJob != null && countdownJob!!.isActive
        if (shouldCancelCountdown) {
            Logging.d(TAG, "SERVICE: Countdown job exists and is active, cancelling it")
            countdownJob?.cancel()
            countdownJob = null // Clear reference immediately
            countdownText = ""
            Logging.d(TAG, "SERVICE: Countdown job cancelled")
            
            // Update notification immediately to stop countdown display
            handler.post {
                updateNotification("Trip cancelled - Waiting for next trip...")
            }
        }
        
        // Check if the cancelled trip is our current trip
        if (currentTrip?.id == cancelledTripId) {
            Logging.d(TAG, "SERVICE: Current trip was cancelled - stopping all activities")
            
            // Check if navigation is active before clearing state
            val wasNavigating = isNavigating.get()
            
        // Clear current trip FIRST so listener checks fail immediately
        currentTrip = null
        confirmationTripData = null
        isNavigating.set(false)
        clearPersistedState() // Clear persisted state immediately
            
            // Stop navigation if active (this will clear listeners and reset validator)
            if (wasNavigating) {
                Logging.d(TAG, "SERVICE: Stopping active navigation")
                stopNavigationAndCleanup()
            } else {
                // Even if not navigating, still reset validator if it exists
                tripSectionValidator?.reset()
            }
            
            // Cancel confirmation if awaiting
            if (isAwaitingConfirmation.get()) {
                Logging.d(TAG, "SERVICE: Cancelling confirmation prompt")
                isAwaitingConfirmation.set(false)
                confirmationTimeoutJob?.cancel()
                cancelConfirmationNotification()
            }
            
            clearPersistedState() // Clear persisted state
            
            // Update notification immediately to clear countdown display
            handler.post {
                updateNotification("Trip cancelled - Waiting for next trip...")
            }
            
            Logging.d(TAG, "SERVICE: Returned to waiting state after cancellation")
        } else if (shouldCancelCountdown) {
            // Even if currentTrip doesn't match, if countdown was running, clear it
            // This handles race conditions where countdown is running but currentTrip was cleared
            Logging.d(TAG, "SERVICE: Current trip doesn't match cancelled trip, but countdown was running - clearing state")
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
                confirmationTripData = null
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
            
            // Clear navigator listeners FIRST to prevent callbacks from firing during cleanup
            navigationExample?.let { navExample ->
                try {
                    val navigator = navExample.getHeadlessNavigator()
                    navigator.routeProgressListener = null
                    navigator.navigableLocationListener = null
                    navigator.routeDeviationListener = null
                    navigator.milestoneStatusListener = null
                    navigator.destinationReachedListener = null
                    Logging.d(TAG, "SERVICE: Navigator listeners cleared")
                } catch (e: Exception) {
                    Logging.e(TAG, "SERVICE: Error clearing Navigator listeners: ${e.message}", e)
                }
            }
            
            // Stop headless navigation
            navigationExample?.stopHeadlessNavigation()
            
            // Reset trip section validator (stops processing)
            tripSectionValidator?.reset()
            
            // Clear current route reference
            currentRoute = null
            
            Logging.d(TAG, "SERVICE: Navigation stopped and cleaned up")
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Error during navigation cleanup: ${e.message}", e)
        }
    }
    
    private suspend fun onNavigationComplete() {
        Logging.d(TAG, "=== SERVICE: NAVIGATION COMPLETE ===")
        
        isNavigating.set(false)
        currentTrip = null
        clearPersistedState() // Clear persisted state immediately
        
        // Fetch settings on trip completion
        val vehicleId = vehicleSecurityManager.getVehicleId()
        try {
            val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
            result.onSuccess { settings ->
                currentSettings = settings
                settingsManager.applySettings(this@AutoModeHeadlessForegroundService, settings)
            }.onFailure { error ->
                Logging.e(TAG, "SERVICE: Failed to fetch settings on trip completion: ${error.message}")
                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                savedSettings?.let { currentSettings = it }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "SERVICE: Exception fetching settings on trip completion: ${e.message}", e)
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
    fun isAwaitingConfirmationForSync(): Boolean = isAwaitingConfirmation.get()
    fun getConfirmationTripIdForSync(): Int? = confirmationTripData?.trip?.id
    fun getConfirmationExpectedTimeForSync(): String? = confirmationTripData?.expectedDepartureTime
    fun getConfirmationCurrentTimeForSync(): String? = confirmationTripData?.currentTime
    fun getConfirmationDelayMinutesForSync(): Int? = confirmationTripData?.delayMinutes
    
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

