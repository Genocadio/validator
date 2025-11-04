package com.gocavgo.validator

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.service.RemoteDataManager
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripStatus
import com.gocavgo.validator.database.DatabaseManager
import android.Manifest
import android.os.Build
import androidx.compose.material3.LinearProgressIndicator
import androidx.core.app.ActivityCompat
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.sync.SyncCoordinator
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.ui.components.NetworkStatusIndicator
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.dataclass.VehicleSettings
import com.gocavgo.validator.security.ACTION_SETTINGS_CHANGED
import com.gocavgo.validator.security.ACTION_SETTINGS_LOGOUT
import com.gocavgo.validator.security.ACTION_SETTINGS_DEACTIVATE
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout


class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    private var networkMonitor: NetworkMonitor? = null
    private var permissionsRequestor: PermissionsRequestor? = null
    private var isConnected by mutableStateOf(true)
    private var connectionType by mutableStateOf("UNKNOWN")
    private var isMetered by mutableStateOf(true)
    private var networkDebugInfo by mutableStateOf("")
    private var mqttService: MqttService? = null
    private var mqttConnected by mutableStateOf(false)

    // Trip management
    private var latestTrip: TripResponse? = null
    private var isLoadingTrips by mutableStateOf(false)
    private var tripError by mutableStateOf<String?>(null)
    private var vehicleInfo by mutableStateOf<VehicleSecurityManager.VehicleInfo?>(null)
    
    // Navigation options
    private var showMap by mutableStateOf(true)
    // isSimulated removed - now uses settings.simulate
    
    // Settings management
    private lateinit var settingsManager: VehicleSettingsManager
    private var currentSettings by mutableStateOf<VehicleSettings?>(null)
    private var isDeactivated by mutableStateOf(false)
    
    // Map downloader
    private var mapDownloaderManager: MapDownloaderManager? = null
    private var mapDownloadProgress by mutableStateOf(0)
    private var mapDownloadTotalSize by mutableStateOf(0)
    private var mapDownloadMessage by mutableStateOf("")
    private var mapDownloadStatus by mutableStateOf("")
    private var showMapDownloadDialog by mutableStateOf(false)
    private var isMapDataReady by mutableStateOf(false)
    
    // HERE SDK offline mode state
    private var pendingOfflineMode: Boolean? = null

    // Managers
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var remoteDataManager: RemoteDataManager
    private lateinit var databaseManager: DatabaseManager
    
    // Periodic trip fetching
    private var periodicTripFetchJob: Job? = null
    private val periodicScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isActivityActive = false
    
    // Settings change broadcast receiver
    private var settingsChangeReceiver: BroadcastReceiver? = null

    private val networkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Logging.d(TAG, "Network permissions granted")
            initializeNetworkMonitoring()
        } else {
            Logging.w(TAG, "Some network permissions denied")
            // Still try to initialize with basic permissions
            initializeNetworkMonitoring()
        }
    }

    private fun initializeNetworkMonitoring() {
        Logging.d(TAG, "Initializing network monitoring...")

        // Log initial network state
        logDetailedNetworkInfo()

        networkMonitor = NetworkMonitor(this) { connected, type, metered ->
            Logging.d(TAG, "=== NETWORK STATE CHANGED ===")
            Logging.d(TAG, "Connected: $connected")
            Logging.d(TAG, "Connection Type: $type")
            Logging.d(TAG, "Is Metered: $metered")
            Logging.d(TAG, "============================")

            // Update UI state
            isConnected = connected
            connectionType = type
            isMetered = metered

            // Update HERE SDK offline mode
            updateHERESDKOfflineMode(connected)

            // Notify map downloader of network availability
            if (connected) {
                mapDownloaderManager?.onNetworkAvailable()
            }

            // Ensure MQTT service state matches network state
            ensureMqttServiceStateMatchesNetwork(connected)

            // Log detailed network info when state changes
            logDetailedNetworkInfo()
        }

        networkMonitor?.startMonitoring()
    }

    private fun ensureMqttServiceStateMatchesNetwork(connected: Boolean) {
        mqttService?.let { mqtt ->
            val isWaiting = mqtt.isWaitingForNetwork()
            val isActive = mqtt.isServiceActive()
            val isConnected = mqtt.isConnected()
            
            Logging.d(TAG, "=== MQTT SERVICE STATE CHECK ===")
            Logging.d(TAG, "Network connected: $connected")
            Logging.d(TAG, "MQTT waiting for network: $isWaiting")
            Logging.d(TAG, "MQTT active: $isActive")
            Logging.d(TAG, "MQTT connected: $isConnected")
            Logging.d(TAG, "===============================")
            
            // Check for mismatches and log them
            if (!connected && isActive && !isWaiting) {
                Logging.w(TAG, "MISMATCH: Network disconnected but MQTT service is active and not waiting")
            } else if (connected && isWaiting) {
                Logging.w(TAG, "MISMATCH: Network connected but MQTT service is waiting for network")
            } else if (connected && !isActive && !isWaiting) {
                Logging.w(TAG, "MISMATCH: Network connected but MQTT service is not active")
            }
            
            // The MQTT service's network monitoring should handle state transitions automatically
            // This is just for logging and verification
        }
    }

    private fun initializeMqtt() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, skipping MQTT initialization")
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()

        // Initialize MQTT service with your broker details
        mqttService = MqttService.getInstance(
            context = this,
            brokerHost = "73d5ec93cbe843ab83b8f29e68f6979e.s1.eu.hivemq.cloud", // Replace with your broker
            brokerPort = 8883, // Or 8883 for SSL
            carId = vehicleId.toString()
        )

        // Set connection callback
        mqttService?.setConnectionCallback { connected ->
            mqttConnected = connected
            Logging.d(TAG, "MQTT connection status: $connected")

            if (connected) {
                Logging.i(TAG, "MQTT connected successfully")
                // Optionally publish initial status
                publishVehicleStatus("READY")
            } else {
                Logging.w(TAG, "MQTT connection failed or lost")
            }
        }

        // Set trip event callback for direct UI updates
        mqttService?.setTripEventCallback { tripEvent ->
            Logging.d(TAG, "Trip event callback received: ${tripEvent.event}")
            handleTripEventFromMqtt(tripEvent)
        }

        // Add message listeners for specific topics
        setupMqttMessageListeners()

        // Connect to MQTT broker
        mqttService?.connect(
            username = "cavgocars", // Optional
            password = "Cadio*11."  // Optional
        )
    }

    private fun publishVehicleStatus(status: String) {
        mqttService?.let { mqtt ->
            if (mqtt.isConnected()) {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                
                // Fetch current location from database
                lifecycleScope.launch {
                    var currentLat: Double? = null
                    var currentLng: Double? = null
                    var currentSpeed: Double? = null
                    
                    try {
                        val vehicleLocation = withContext(Dispatchers.IO) {
                            val db = com.gocavgo.validator.database.AppDatabase.getDatabase(this@MainActivity)
                            val vehicleLocationDao = db.vehicleLocationDao()
                            vehicleLocationDao.getVehicleLocation(vehicleId.toInt())
                        }
                        
                        if (vehicleLocation != null) {
                            currentLat = vehicleLocation.latitude
                            currentLng = vehicleLocation.longitude
                            currentSpeed = vehicleLocation.speed
                            Logging.d(TAG, "Including location in $status status: lat=$currentLat, lng=$currentLng, speed=$currentSpeed")
                        } else {
                            Logging.d(TAG, "No location data available for $status status message (sending nulls)")
                        }
                    } catch (e: Exception) {
                        Logging.e(TAG, "Failed to fetch location for $status status: ${e.message}", e)
                    }
                    
                    // Build status message with location data
                    val statusMessage = """
                    {
                        "vehicle_id": "$vehicleId",
                        "status": "$status",
                        "timestamp": ${System.currentTimeMillis()},
                        "current_latitude": ${currentLat ?: "null"},
                        "current_longitude": ${currentLng ?: "null"},
                        "current_speed": ${currentSpeed ?: "null"}
                    }
                """.trimIndent()

                    mqtt.publish("car/$vehicleId/status", statusMessage)
                        .whenComplete { result, throwable ->
                            if (throwable != null) {
                                Logging.e(TAG, "Failed to publish vehicle status", throwable)
                            } else {
                                Logging.d(TAG, "Vehicle status published: $status with location data")
                            }
                        }
                }
            }
        }
    }
    private fun setupMqttMessageListeners() {
        val vehicleId = vehicleSecurityManager.getVehicleId()

        // Listen for booking updates
        mqttService?.addMessageListener("trip/+/booking") { topic, payload ->
            Logging.d(TAG, "Booking update received on $topic: $payload")
            // Handle booking updates
            Logging.d(TAG, "Booking update for trip ")
        }

        // Listen for ping requests
        mqttService?.addMessageListener("car/$vehicleId/ping") { topic, payload ->
            Logging.d(TAG, "Ping received: $payload")
            // MQTT service automatically handles pong response
        }

        // Listen for settings updates
        mqttService?.addMessageListener("car/$vehicleId/settings") { topic, payload ->
            Logging.d(TAG, "Settings update received on $topic: $payload")
            // Settings are handled by MQTT service, but refresh UI
            lifecycleScope.launch {
                fetchSettings()
            }
        }
    }

    /**
     * Fetch settings from API and apply them
     */
    private fun fetchSettings() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, cannot fetch settings")
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "Fetching settings for vehicle ID: $vehicleId")

        lifecycleScope.launch {
            try {
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                
                result.onSuccess { settings ->
                    Logging.d(TAG, "Settings fetched successfully")
                    currentSettings = settings
                    
                    // Update deactivated state
                    isDeactivated = settings.deactivate
                    
                    // Apply settings (check logout/deactivate)
                    settingsManager.applySettings(this@MainActivity, settings)
                    
                    // Check if all settings are false - redirect to AutoModeHeadlessActivity immediately
                    if (settingsManager.areAllSettingsFalse(settings)) {
                        Logging.d(TAG, "All settings are false - redirecting to AutoModeHeadlessActivity immediately")
                        val intent = Intent(this@MainActivity, com.gocavgo.validator.navigator.AutoModeHeadlessActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                        return@launch
                    }
                    
                    // Check devmode - if false, redirect to AutoModeHeadlessActivity
                    if (!settings.devmode) {
                        Logging.d(TAG, "Devmode is false - redirecting to AutoModeHeadlessActivity")
                        val intent = Intent(this@MainActivity, com.gocavgo.validator.navigator.AutoModeHeadlessActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                        return@launch
                    }
                }.onFailure { error ->
                    Logging.e(TAG, "Failed to fetch settings: ${error.message}")
                    // Use saved settings if available
                    val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                    savedSettings?.let {
                        currentSettings = it
                        isDeactivated = it.deactivate
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception fetching settings: ${e.message}", e)
                // Use saved settings if available
                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                savedSettings?.let {
                    currentSettings = it
                    isDeactivated = it.deactivate
                }
            }
        }
    }

    private fun handleTripEventFromMqtt(tripEvent: com.gocavgo.validator.dataclass.TripEventMessage) {
        // Handle trip event directly from MQTT callback
        lifecycleScope.launch {
            try {
                Logging.d(TAG, "Processing MQTT trip event: ${tripEvent.event}")
                Logging.d(TAG, "Trip ID: ${tripEvent.data.id}")
                
                // Handle trip cancellation
                if (tripEvent.event == "TRIP_CANCELLED") {
                    Logging.d(TAG, "Trip cancelled: ${tripEvent.data.id}")
                    
                    // Delete from database
                    databaseManager.deleteTripById(tripEvent.data.id)
                    
                    // Clear UI if this was the latest trip
                    if (latestTrip?.id == tripEvent.data.id) {
                        latestTrip = null
                        tripError = "Trip was cancelled"
                    }
                    
                    Logging.d(TAG, "Trip cancellation processed")
                    return@launch
                }
                
                // Convert backend trip data to Android format
                val tripResponse = convertBackendTripToAndroid(tripEvent.data)
                Logging.d(TAG, "Converted trip response: ${tripResponse.id}")
                
                // Check if trip is already in database (MQTT service may have saved it)
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val existingTrip = databaseManager.getTripById(tripResponse.id)
                
                if (existingTrip != null) {
                    Logging.d(TAG, "Trip already exists in database, updating UI")
                    // Trip was already saved by MQTT service, just update UI
                    
                    // Record MQTT update for sync coordination
                    SyncCoordinator.recordMqttUpdate(this@MainActivity)
                    Logging.d(TAG, "Recorded MQTT update for sync coordination (existing trip)")
                    
                    // Reset periodic fetch timer to avoid redundant backend calls
                    Logging.d(TAG, "Resetting periodic fetch timer after MQTT update")
                    stopPeriodicTripFetching()
                    startPeriodicTripFetching()
                    
                    latestTrip = existingTrip
                    tripError = null
                    isLoadingTrips = false
                    
                    Logging.d(TAG, "UI updated with existing trip: ${existingTrip.id}")
                    Logging.d(TAG, "Trip status: ${existingTrip.status}")
                    Logging.d(TAG, "Route: ${existingTrip.route.origin.google_place_name} → ${existingTrip.route.destination.google_place_name}")
                } else {
                    Logging.d(TAG, "Trip not found in database, saving now")
                    // Trip not saved yet, save it
                    val result = databaseManager.saveTripFromMqtt(tripResponse, vehicleId.toInt())
                    
                    if (result.isSuccess()) {
                        Logging.d(TAG, "Trip saved to database successfully")
                        
                        // Record MQTT update for sync coordination
                        SyncCoordinator.recordMqttUpdate(this@MainActivity)
                        Logging.d(TAG, "Recorded MQTT update for sync coordination")
                        
                        // Reset periodic fetch timer to avoid redundant backend calls
                        Logging.d(TAG, "Resetting periodic fetch timer after MQTT update")
                        stopPeriodicTripFetching()
                        startPeriodicTripFetching()
                        
                        // Update UI immediately
                        latestTrip = tripResponse
                        tripError = null
                        isLoadingTrips = false
                        
                        Logging.d(TAG, "UI updated with new trip: ${tripResponse.id}")
                        Logging.d(TAG, "Trip status: ${tripResponse.status}")
                        Logging.d(TAG, "Route: ${tripResponse.route.origin.google_place_name} → ${tripResponse.route.destination.google_place_name}")
                    } else {
                        val errorMessage = result.getErrorOrNull() ?: "Unknown error"
                        Logging.e(TAG, "Failed to save trip to database: $errorMessage")
                        tripError = "Failed to save trip: $errorMessage"
                    }
                }
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error handling MQTT trip event", e)
                tripError = "Error processing trip event: ${e.message}"
            }
        }
    }

    
    /**
     * Convert backend trip data to Android trip response format
     */
    private fun convertBackendTripToAndroid(backendTrip: com.gocavgo.validator.dataclass.TripData): TripResponse {
        return TripResponse(
            id = backendTrip.id,
            route_id = backendTrip.route_id,
            vehicle_id = backendTrip.vehicle_id,
            vehicle = com.gocavgo.validator.dataclass.VehicleInfo(
                id = backendTrip.vehicle.id,
                company_id = backendTrip.vehicle.company_id,
                company_name = backendTrip.vehicle.company_name,
                capacity = backendTrip.vehicle.capacity,
                license_plate = backendTrip.vehicle.license_plate,
                driver = backendTrip.vehicle.driver?.let { driver ->
                    com.gocavgo.validator.dataclass.DriverInfo(
                        name = driver.name,
                        phone = driver.phone
                    )
                }
            ),
            status = backendTrip.status,
            departure_time = backendTrip.departure_time,
            connection_mode = backendTrip.connection_mode,
            notes = backendTrip.notes,
            seats = backendTrip.seats,
            remaining_time_to_destination = backendTrip.remaining_time_to_destination,
            remaining_distance_to_destination = backendTrip.remaining_distance_to_destination,
            is_reversed = backendTrip.is_reversed,
            has_custom_waypoints = backendTrip.has_custom_waypoints,
            created_at = backendTrip.created_at,
            updated_at = backendTrip.updated_at,
            completion_timestamp = backendTrip.completion_time,
            route = com.gocavgo.validator.dataclass.TripRoute(
                id = backendTrip.route.id,
                origin = com.gocavgo.validator.dataclass.SavePlaceResponse(
                    id = backendTrip.route.origin.id,
                    latitude = backendTrip.route.origin.latitude,
                    longitude = backendTrip.route.origin.longitude,
                    code = backendTrip.route.origin.code,
                    google_place_name = backendTrip.route.origin.google_place_name,
                    custom_name = backendTrip.route.origin.custom_name,
                    province = "", // Backend doesn't have this field
                    district = "", // Backend doesn't have this field
                    place_id = backendTrip.route.origin.place_id,
                    created_at = backendTrip.route.origin.created_at ?: "",
                    updated_at = backendTrip.route.origin.updated_at ?: ""
                ),
                destination = com.gocavgo.validator.dataclass.SavePlaceResponse(
                    id = backendTrip.route.destination.id,
                    latitude = backendTrip.route.destination.latitude,
                    longitude = backendTrip.route.destination.longitude,
                    code = backendTrip.route.destination.code,
                    google_place_name = backendTrip.route.destination.google_place_name,
                    custom_name = backendTrip.route.destination.custom_name,
                    province = "", // Backend doesn't have this field
                    district = "", // Backend doesn't have this field
                    place_id = backendTrip.route.destination.place_id,
                    created_at = backendTrip.route.destination.created_at ?: "",
                    updated_at = backendTrip.route.destination.updated_at ?: ""
                )
            ),
            waypoints = backendTrip.waypoints.map { waypoint ->
                com.gocavgo.validator.dataclass.TripWaypoint(
                    id = waypoint.id,
                    trip_id = waypoint.trip_id,
                    location_id = waypoint.location_id,
                    order = waypoint.order,
                    price = waypoint.price,
                    is_passed = waypoint.is_passed,
                    is_next = waypoint.is_next,
                    is_custom = waypoint.is_custom,
                    remaining_time = waypoint.remaining_time,
                    remaining_distance = waypoint.remaining_distance,
                    waypoint_length_meters = waypoint.waypoint_length_meters,
                    waypoint_time_seconds = waypoint.waypoint_time_seconds,
                    passed_timestamp = waypoint.passed_timestamp,
                    location = com.gocavgo.validator.dataclass.SavePlaceResponse(
                        id = waypoint.location.id,
                        latitude = waypoint.location.latitude,
                        longitude = waypoint.location.longitude,
                        code = waypoint.location.code,
                        google_place_name = waypoint.location.google_place_name,
                        custom_name = waypoint.location.custom_name,
                        province = "", // Backend doesn't have this field
                        district = "", // Backend doesn't have this field
                        place_id = waypoint.location.place_id,
                        created_at = waypoint.location.created_at ?: "",
                        updated_at = waypoint.location.updated_at ?: ""
                    )
                )
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun logDetailedNetworkInfo() {
        try {
            if (NetworkUtils.hasNetworkPermissions(this)) {
                val report = NetworkUtils.createNetworkReport(this)
                networkDebugInfo = report
                Logging.d(TAG, "\n$report")

                // Additional connectivity checks
                Logging.d(TAG, "=== CONNECTIVITY TESTS ===")
                Logging.d(TAG, "Internet Available: ${NetworkUtils.isConnectedToInternet(this)}")
                Logging.d(TAG, "Connection Metered: ${NetworkUtils.isConnectionMetered(this)}")

                // Check roaming status if available
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    Logging.d(TAG, "Is Roaming: ${NetworkUtils.isRoaming(this)}")
                }
                Logging.d(TAG, "==========================")
            } else {
                val basicInfo = "Network permissions not available - basic monitoring only"
                networkDebugInfo = basicInfo
                Logging.w(TAG, basicInfo)
            }
        } catch (e: Exception) {
            val errorInfo = "Error getting network info: ${e.message}"
            networkDebugInfo = errorInfo
            Logging.e(TAG, errorInfo, e)
        }
    }

    private fun checkAndRequestNetworkPermissions() {
        val requiredPermissions = NetworkUtils.getRequiredPermissions()
        val optionalPermissions = NetworkUtils.getOptionalPermissions()
        val allPermissions = requiredPermissions + optionalPermissions

        val missingPermissions = allPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Logging.d(TAG, "Requesting network permissions: ${missingPermissions.joinToString()}")
            networkPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Logging.d(TAG, "All network permissions already granted")
            initializeNetworkMonitoring()
        }
    }

    
    private fun initializeManagers() {
        // vehicleSecurityManager already initialized in onCreate
        remoteDataManager = RemoteDataManager.getInstance()
        databaseManager = DatabaseManager.getInstance(this)
        
        // Get vehicle info
        vehicleInfo = vehicleSecurityManager.getVehicleInfo()

        
        // Test database connection
        testDatabaseConnection()
        if (vehicleSecurityManager.isVehicleRegistered()) {
            initializeMqtt()
            // Start periodic trip fetching after initialization if activity is active
            if (isActivityActive) {
                startPeriodicTripFetching()
            }
            
            // Auto-start disabled - user must manually start Auto Mode via button
            // Uncomment below to enable auto-start on app launch:
            // startAutoModeHeadlessActivityIfNeeded()
        }
    }
    
    /**
     * Start AutoModeHeadlessActivity automatically if vehicle is registered
     * This enables autonomous trip monitoring and navigation via MQTT
     */
    private fun startAutoModeHeadlessActivityIfNeeded() {
        try {
            // Check if AutoModeHeadlessActivity is already running
            if (com.gocavgo.validator.navigator.AutoModeHeadlessActivity.isActive()) {
                Logging.d(TAG, "AutoModeHeadlessActivity already active, skipping start")
                return
            }
            
            Logging.d(TAG, "Starting AutoModeHeadlessActivity for autonomous trip monitoring")
            
            val activityIntent = Intent(this, com.gocavgo.validator.navigator.AutoModeHeadlessActivity::class.java)
            startActivity(activityIntent)
            
            Logging.d(TAG, "AutoModeHeadlessActivity started successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to start AutoModeHeadlessActivity: ${e.message}", e)
        }
    }
    
    private fun initializeMapDownloader() {
        Logging.d(TAG, "=== INITIALIZING MAP DOWNLOADER ===")
        
        // Check if HERE SDK is initialized
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine == null) {
            Logging.e(TAG, "HERE SDK not initialized! Cannot initialize map downloader.")
            isMapDataReady = true // Proceed without offline maps
            return
        }
        
        Logging.d(TAG, "HERE SDK is initialized, proceeding with map downloader")
        
        mapDownloaderManager = MapDownloaderManager(
            context = this,
            onProgressUpdate = { message, progress, totalSizeMB ->
                mapDownloadMessage = message
                mapDownloadProgress = progress
                mapDownloadTotalSize = totalSizeMB
                Logging.d(TAG, "Map download progress: $message - $progress% (${totalSizeMB}MB)")
            },
            onStatusUpdate = { status ->
                mapDownloadStatus = status
                Logging.d(TAG, "Map download status: $status")
            },
            onDownloadComplete = {
                isMapDataReady = true
                showMapDownloadDialog = false
                Logging.d(TAG, "Map download completed successfully")
            },
            onError = { error ->
                Logging.e(TAG, "Map download error: $error")
                showMapDownloadDialog = false
            },
            onToastMessage = { message ->
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            },
            onShowProgressDialog = {
                runOnUiThread {
                    showMapDownloadDialog = true
                }
            }
        )
        
        // Initialize the map downloader in background
        try {
            mapDownloaderManager?.initialize()
            Logging.d(TAG, "Map downloader initialized successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize map downloader: ${e.message}", e)
            isMapDataReady = true // Proceed without offline maps
        }
        
        Logging.d(TAG, "================================")
    }
    
    private fun testDatabaseConnection() {
        lifecycleScope.launch {
            try {
                if (vehicleSecurityManager.isVehicleRegistered()) {
                    val vehicleId = vehicleSecurityManager.getVehicleId()
                    val tripCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                    Logging.d(TAG, "=== DATABASE STATUS ===")
                    Logging.d(TAG, "Database connected successfully")
                    Logging.d(TAG, "Trips in database for vehicle $vehicleId: $tripCount")
                    Logging.d(TAG, "=========================")
                    
                    // Test TripStatus values
                    Logging.d(TAG, "=== TRIP STATUS TEST ===")
                    Logging.d(TAG, "PENDING.value: '${TripStatus.PENDING.value}'")
                    Logging.d(TAG, "SCHEDULED.value: '${TripStatus.SCHEDULED.value}'")
                    Logging.d(TAG, "IN_PROGRESS.value: '${TripStatus.IN_PROGRESS.value}'")
                    Logging.d(TAG, "PENDING.value length: ${TripStatus.PENDING.value.length}")
                    Logging.d(TAG, "SCHEDULED.value length: ${TripStatus.SCHEDULED.value.length}")
                    Logging.d(TAG, "IN_PROGRESS.value length: ${TripStatus.IN_PROGRESS.value.length}")
                    Logging.d(TAG, "=========================")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Database connection test failed: ${e.message}", e)
            }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private fun fetchLatestVehicleTrip() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, cannot fetch trips")
            tripError = "Vehicle not registered. Please complete vehicle authentication first."
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "Checking trips for vehicle ID: $vehicleId")
        
        isLoadingTrips = true
        tripError = null

        lifecycleScope.launch {
            try {
                // First check if we have any active trips in the database
                val hasActiveTrips = databaseManager.hasActiveTrips(vehicleId.toInt())
                
                if (hasActiveTrips) {
                    // We have active trips, just load from database
                    val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                    if (activeTrip != null) {
                        latestTrip = activeTrip
                        Logging.d(TAG, "Active trip loaded from database: ${latestTrip?.id}")
                        Logging.d(TAG, "Trip details: ${latestTrip?.route?.origin?.google_place_name} → ${latestTrip?.route?.destination?.google_place_name}")
                        Logging.d(TAG, "Status: ${latestTrip?.status}")
                        Logging.d(TAG, "Connection mode: ${latestTrip?.connection_mode}")
                        Logging.d(TAG, "DEBUG: Trip status length: ${latestTrip?.status?.length}")
                        Logging.d(TAG, "DEBUG: Trip status bytes: ${latestTrip?.status?.toByteArray().contentToString()}")
                    }
                } else {
                    // No active trips, check if we have any trips at all
                    val dbTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                    if (dbTrip != null) {
                        latestTrip = dbTrip
                        tripError = null // Clear any previous errors
                        Logging.d(TAG, "Latest trip loaded from database: ${latestTrip?.id}")
                        Logging.d(TAG, "Trip details: ${latestTrip?.route?.origin?.google_place_name} → ${latestTrip?.route?.destination?.google_place_name}")
                        Logging.d(TAG, "Status: ${latestTrip?.status}")
                        Logging.d(TAG, "Connection mode: ${latestTrip?.connection_mode}")
                        Logging.d(TAG, "DEBUG: Trip status length: ${latestTrip?.status?.length}")
                        Logging.d(TAG, "DEBUG: Trip status bytes: ${latestTrip?.status?.toByteArray().contentToString()}")
                    } else {
                        // No trips in database, clear latest trip
                        latestTrip = null
                        Logging.d(TAG, "No trips found in database")
                    }
                    
                    // Only fetch from remote if we don't have any trips or if all trips are completed
                    val tripCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                    val shouldFetchFromBackend = tripCount == 0 || (dbTrip != null && TripStatus.isCompleted(dbTrip.status))
                    
                    // Check if we should skip backend fetch due to fresh MQTT data
                    // shouldFetchFromBackend returns true if we SHOULD fetch (data is stale)
                    val shouldSkipBackendFetch = !SyncCoordinator.shouldFetchFromBackend(this@MainActivity)
                    
                    if (shouldFetchFromBackend && shouldSkipBackendFetch) {
                        Logging.d(TAG, "Skipping backend fetch - data is fresh from MQTT (within 2 minutes)")
                    } else if (shouldFetchFromBackend) {
                        Logging.d(TAG, "Fetching from remote API (data is stale or no MQTT updates)...")
                        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 1)
                        
                        when {
                            result.isSuccess() -> {
                                val newTripCount = result.getDataOrNull() ?: 0
                                // Always check database after sync to handle cleanup cases
                                val updatedTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                                if (updatedTrip != null) {
                                    latestTrip = updatedTrip
                                    tripError = null // Clear any previous errors
                                    Logging.d(TAG, "Trip updated from database: ${latestTrip?.id}")
                                    Logging.d(TAG, "DEBUG: Trip status: ${latestTrip?.status}")
                                    Logging.d(TAG, "DEBUG: Trip status length: ${latestTrip?.status?.length}")
                                    Logging.d(TAG, "DEBUG: Trip status bytes: ${latestTrip?.status?.toByteArray().contentToString()}")
                                } else {
                                    // No trips in database after sync (either no new trips or cleanup removed all)
                                    latestTrip = null
                                    if (newTripCount == 0) {
                                        Logging.w(TAG, "No trips found for vehicle after sync")
                                        tripError = "No trips available for this vehicle"
                                    } else {
                                        Logging.w(TAG, "Trips were cleaned up during sync")
                                        tripError = "Previous trips are no longer available"
                                    }
                                }
                            }
                            result.isError() -> {
                                val errorMessage = result.getErrorOrNull() ?: "Unknown error occurred"
                                Logging.e(TAG, "Failed to fetch trips: $errorMessage")
                                tripError = "Failed to fetch trips: $errorMessage"
                            }
                        }
                    } else {
                        Logging.d(TAG, "Using existing trip from database, no remote fetch needed")
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception while checking trips", e)
                tripError = "Exception: ${e.message}"
            } finally {
                isLoadingTrips = false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsRequestor!!.onRequestPermissionsResult(requestCode, grantResults)
    }


    private fun initializeHERESDK() {
        // Initialize HERE SDK in background to prevent ANR
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Set your credentials for the HERE SDK.
                val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID
                val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET
                val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
                val options = SDKOptions(authenticationMode)
                
                val context: Context = this@MainActivity
                
                // Add timeout protection for HERE SDK initialization
                withTimeout(15000) { // 15 second timeout
                    SDKNativeEngine.makeSharedInstance(context, options)
                }
                
                Logging.d(TAG, "HERE SDK initialized successfully in MainActivity")
                
                // Apply pending offline mode if any
                pendingOfflineMode?.let { offlineMode ->
                    try {
                        SDKNativeEngine.getSharedInstance()?.setOfflineMode(offlineMode)
                        Logging.d(TAG, "Applied pending HERE SDK offline mode: $offlineMode")
                        pendingOfflineMode = null
                    } catch (e: Exception) {
                        Logging.e(TAG, "Failed to apply pending offline mode: ${e.message}", e)
                    }
                }
                
                // Initialize map downloader after HERE SDK is ready
                withContext(Dispatchers.Main) {
                    initializeMapDownloader()
                }
            } catch (e: TimeoutCancellationException) {
                Logging.e(TAG, "HERE SDK initialization timed out after 15 seconds", e)
                withContext(Dispatchers.Main) {
                    isMapDataReady = true // Proceed without offline maps
                }
            } catch (e: InstantiationErrorException) {
                Logging.e(TAG, "Initialization of HERE SDK failed: ${e.error.name}", e)
                // Don't throw RuntimeException to prevent ANR, just log and continue
                // The app can still function without HERE SDK in some cases
                withContext(Dispatchers.Main) {
                    isMapDataReady = true // Proceed without offline maps
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Unexpected error during HERE SDK initialization: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isMapDataReady = true // Proceed without offline maps
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Set this activity as active and disable its logging
        Logging.setActivityLoggingEnabled(TAG, false)

        // Initialize managers
        vehicleSecurityManager = VehicleSecurityManager(this)
        vehicleInfo = vehicleSecurityManager.getVehicleInfo()

        // Initialize settings manager
        settingsManager = VehicleSettingsManager.getInstance(this)

        // Initialize HERE SDK in background to prevent ANR
        initializeHERESDK()
        
        checkAndRequestNetworkPermissions()
        initializeManagers()

        // Register settings change broadcast receiver
        registerSettingsChangeReceiver()

        // Fetch settings from API on create
        fetchSettings()
        
        setContent {
            ValidatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavigationScreen(
                            onStartNavigation = { startNavigator() },
                            onRefreshTrips = { forceRefreshFromRemote() },
                            latestTrip = latestTrip,
                            isLoadingTrips = isLoadingTrips,
                            tripError = tripError,
                            vehicleInfo = vehicleInfo,
                            showMap = showMap,
                            isDeactivated = isDeactivated,
                            onShowMapChanged = { showMap = it },
                            modifier = Modifier.padding(innerPadding)
                        )
                        
                        // Network status indicator overlay
                        NetworkStatusIndicator(
                            isConnected = isConnected,
                            connectionType = connectionType,
                            isMetered = isMetered
                        )
                        
                        // Map download dialog overlay
                        if (showMapDownloadDialog) {
                            MapDownloadDialog(
                                progress = mapDownloadProgress,
                                totalSize = mapDownloadTotalSize,
                                message = mapDownloadMessage,
                                status = mapDownloadStatus,
                                onCancel = {
                                    mapDownloaderManager?.cancelDownloads()
                                    showMapDownloadDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Fetch trips after UI is set up
        fetchLatestVehicleTrip()
    }

    private fun startNavigator() {
        if (latestTrip == null) {
            Logging.w(TAG, "No trip available for navigation")
            return
        }

        // Mark trip as in progress when starting navigation
        lifecycleScope.launch {
            try {
                val currentStatus = latestTrip!!.status
                val normalizedCurrentStatus = TripStatus.normalizeStatus(currentStatus)
                val newStatus = when (normalizedCurrentStatus) {
                    TripStatus.SCHEDULED.value -> TripStatus.IN_PROGRESS.value
                    TripStatus.PENDING.value -> TripStatus.IN_PROGRESS.value
                    else -> normalizedCurrentStatus
                }
                
                if (newStatus != normalizedCurrentStatus) {
                    databaseManager.updateTripStatus(latestTrip!!.id, newStatus)
                    Logging.d(TAG, "Trip ${latestTrip!!.id} status updated from $currentStatus to $newStatus")
                    
                    // Update local trip status
                    latestTrip = latestTrip!!.copy(status = newStatus)
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update trip status: ${e.message}", e)
            }
        }

        // Get simulate value from settings
        val simulate = currentSettings?.simulate ?: false
        
        val intent = if (showMap) {
            Intent(this, com.gocavgo.validator.navigator.NavigActivity::class.java)
        } else {
            Intent(this, com.gocavgo.validator.navigator.HeadlessNavigActivity::class.java)
        }
        
        intent.putExtra(com.gocavgo.validator.navigator.NavigActivity.EXTRA_TRIP_ID, latestTrip!!.id)
        intent.putExtra(com.gocavgo.validator.navigator.NavigActivity.EXTRA_SHOW_MAP, showMap)
        intent.putExtra(com.gocavgo.validator.navigator.NavigActivity.EXTRA_IS_SIMULATED, simulate)
        startActivity(intent)
    }

    private fun startVehicleAuth() {
        val intent = Intent(this, com.gocavgo.validator.security.VehicleAuthActivity::class.java)
        startActivity(intent)
    }
    
    private fun redirectToActiveNavigation() {
        try {
            val intent = Intent(this, com.gocavgo.validator.navigator.HeadlessNavigActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
            Logging.d(TAG, "Redirected to active navigation screen")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to redirect to navigation: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Check if navigation is active and redirect to it
        if (com.gocavgo.validator.navigator.HeadlessNavigActivity.isActive()) {
            Logging.d(TAG, "Navigation is active, redirecting to navigation screen")
            redirectToActiveNavigation()
            return // Don't continue with normal resume
        }
        
        // Mark activity as active
        isActivityActive = true
        
        // Set this activity as the active one for logging
        Logging.setActiveActivity(TAG)
        
        // Notify MQTT service that app is in foreground
        mqttService?.onAppForeground()
        
        // Refresh vehicle info and trips when returning from VehicleAuth
        vehicleInfo = vehicleSecurityManager.getVehicleInfo()
        if (vehicleSecurityManager.isVehicleRegistered()) {
            // Fetch settings on resume
            fetchSettings()
            
            // Check if we're returning from navigation and need to complete any in-progress trips
            checkAndCompleteTrips()
            fetchLatestVehicleTrip()
            
            // Start periodic trip fetching if not already running
            if (periodicTripFetchJob?.isActive != true) {
                startPeriodicTripFetching()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // Mark activity as inactive
        isActivityActive = false
        
        // Stop periodic trip fetching
        stopPeriodicTripFetching()
        
        // Notify MQTT service that app is in background
        mqttService?.onAppBackground()
    }

    
    private fun checkAndCompleteTrips() {
        lifecycleScope.launch {
            try {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                
                if (activeTrip != null && activeTrip.status == TripStatus.IN_PROGRESS.value) {
                    // If we have an in-progress trip, check if it should be completed
                    // This is a simple check - in a real app, you might want to check actual navigation completion
                    Logging.d(TAG, "Found in-progress trip ${activeTrip.id}, checking if should be completed")
                    
                    // For now, we'll just mark it as completed when returning to MainActivity
                    // In a real implementation, you'd check actual navigation completion status
//                    databaseManager.updateTripStatus(activeTrip.id, TripStatus.COMPLETED.value)
                    Logging.d(TAG, "Trip ${activeTrip.id} marked as completed")
                    
                    // Update local trip if it's the current one
//                    if (latestTrip?.id == activeTrip.id) {
//                        latestTrip = latestTrip!!.copy(status = TripStatus.COMPLETED.value)
//                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to check trip completion: ${e.message}", e)
            }
        }
    }
    
    private fun forceRefreshFromRemote() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, cannot refresh trips")
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "Force refreshing trips from remote for vehicle ID: $vehicleId")
        
        isLoadingTrips = true
        tripError = null

        lifecycleScope.launch {
            try {
                val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 1)
                
                when {
                    result.isSuccess() -> {
                        val tripCount = result.getDataOrNull() ?: 0
                        // Always check database after sync to handle cleanup cases
                        val updatedTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                        if (updatedTrip != null) {
                            latestTrip = updatedTrip
                            tripError = null // Clear any previous errors
                            Logging.d(TAG, "Trips refreshed from remote: ${latestTrip?.id}")
                        } else {
                            // No trips in database after sync (either no new trips or cleanup removed all)
                            latestTrip = null
                            if (tripCount == 0) {
                                Logging.w(TAG, "No trips found for vehicle after refresh")
                                tripError = "No trips available for this vehicle"
                            } else {
                                Logging.w(TAG, "Trips were cleaned up during refresh")
                                tripError = "Previous trips are no longer available"
                            }
                        }
                    }
                    result.isError() -> {
                        val errorMessage = result.getErrorOrNull() ?: "Unknown error occurred"
                        Logging.e(TAG, "Failed to refresh trips: $errorMessage")
                        tripError = "Failed to refresh trips: $errorMessage"
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception while refreshing trips", e)
                tripError = "Exception: ${e.message}"
            } finally {
                isLoadingTrips = false
            }
        }
    }
    
    private fun startPeriodicTripFetching() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, skipping periodic trip fetching")
            return
        }
        
        // Cancel existing job if any
        periodicTripFetchJob?.cancel()
        
        Logging.d(TAG, "Starting periodic trip fetching every 10 minutes (reduced frequency due to MQTT handling)")
        
        periodicTripFetchJob = periodicScope.launch {
            while (isActivityActive) {
                try {
                    // Check internet connectivity before fetching
                    if (NetworkUtils.isConnectedToInternet(this@MainActivity)) {
                        Logging.d(TAG, "Internet available, performing periodic trip fetch")
                        performPeriodicTripFetch()
                    } else {
                        Logging.d(TAG, "No internet connection, skipping periodic trip fetch")
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in periodic trip fetch: ${e.message}", e)
                }
                
                // Wait 10 minutes (600,000 milliseconds) - reduced frequency since MQTT handles real-time updates
                delay(600_000)
            }
        }
    }
    
    private fun stopPeriodicTripFetching() {
        Logging.d(TAG, "Stopping periodic trip fetching")
        periodicTripFetchJob?.cancel()
        periodicTripFetchJob = null
    }
    
    private suspend fun performPeriodicTripFetch() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, skipping periodic trip fetch")
            return
        }
        
        // Check if data is fresh from MQTT before performing periodic fetch
        val shouldFetchFromBackend = SyncCoordinator.shouldFetchFromBackend(this@MainActivity)
        
        if (!shouldFetchFromBackend) {
            Logging.d(TAG, "=== PERIODIC TRIP FETCH SKIPPED ===")
            Logging.d(TAG, "Data is fresh from MQTT (within 2 minutes), skipping backend fetch")
            Logging.d(TAG, "Sync status: ${SyncCoordinator.getSyncStatus(this@MainActivity)}")
            return
        }
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "=== PERIODIC TRIP FETCH ===")
        Logging.d(TAG, "Fetching trips for vehicle ID: $vehicleId")
        Logging.d(TAG, "Sync status: ${SyncCoordinator.getSyncStatus(this@MainActivity)}")
        
        try {
            // Get current trip count before sync
            val beforeCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
            Logging.d(TAG, "Current trips in database before sync: $beforeCount")
            
            val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
            
            when {
                result.isSuccess() -> {
                    val tripCount = result.getDataOrNull() ?: 0
                    Logging.d(TAG, "Periodic fetch successful: $tripCount trips synced")
                    
                    // Get trip count after sync
                    val afterCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                    Logging.d(TAG, "Current trips in database after sync: $afterCount")
                    
                    // Update latest trip on main thread
                    if (isActivityActive) {
                        lifecycleScope.launch {
                            val updatedTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                            if (updatedTrip != null) {
                                latestTrip = updatedTrip
                                tripError = null // Clear any previous errors
                                Logging.d(TAG, "Latest trip updated: ${latestTrip?.id}")
                            } else {
                                // Clear latest trip if no trips available after sync
                                latestTrip = null
                                Logging.d(TAG, "No trips available after periodic sync, cleared latest trip")
                            }
                        }
                    }
                }
                result.isError() -> {
                    val errorMessage = result.getErrorOrNull() ?: "Unknown error occurred"
                    Logging.w(TAG, "Periodic trip fetch failed: $errorMessage")
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Exception during periodic trip fetch: ${e.message}", e)
        }
        
        Logging.d(TAG, "=== END PERIODIC TRIP FETCH ===")
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Mark activity as inactive
        isActivityActive = false
        
        // Stop periodic trip fetching
        stopPeriodicTripFetching()
        
        // Cancel the periodic scope
        periodicScope.cancel()
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        
        // Clean up map downloader
        mapDownloaderManager?.cleanup()
        
        // Unregister settings change receiver
        unregisterSettingsChangeReceiver()
        
        // Dispose HERE SDK only after service cleanup
        disposeHERESDK()
    }
    
    /**
     * Register broadcast receiver for settings changes
     */
    private fun registerSettingsChangeReceiver() {
        settingsChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                
                when (intent.action) {
                    ACTION_SETTINGS_LOGOUT -> {
                        Logging.d(TAG, "Received logout broadcast - closing activity")
                        // Logout is handled by VehicleSettingsManager, just finish activity
                        finish()
                    }
                    ACTION_SETTINGS_DEACTIVATE -> {
                        val isDeactivated = intent.getBooleanExtra("is_deactivated", false)
                        Logging.d(TAG, "Received deactivate broadcast - isDeactivated: $isDeactivated")
                        // Update UI state
                        this@MainActivity.isDeactivated = isDeactivated
                    }
                    ACTION_SETTINGS_CHANGED -> {
                        Logging.d(TAG, "Received settings changed broadcast - refreshing settings")
                        // Refresh settings from database without re-applying (to prevent loop)
                        lifecycleScope.launch {
                            val vehicleId = vehicleSecurityManager.getVehicleId()
                            val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                            savedSettings?.let {
                                // Only update if different from current to prevent loops
                                if (currentSettings == null ||
                                    currentSettings?.logout != it.logout ||
                                    currentSettings?.devmode != it.devmode ||
                                    currentSettings?.deactivate != it.deactivate ||
                                    currentSettings?.appmode != it.appmode ||
                                    currentSettings?.simulate != it.simulate) {
                                    currentSettings = it
                                    isDeactivated = it.deactivate
                                    Logging.d(TAG, "Settings updated from broadcast (no re-apply to prevent loop)")
                                    
                                    // Check if should redirect
                                    if (settingsManager.areAllSettingsFalse(it) || !it.devmode) {
                                        val redirectIntent = Intent(this@MainActivity, com.gocavgo.validator.navigator.AutoModeHeadlessActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        }
                                        startActivity(redirectIntent)
                                        finish()
                                    }
                                } else {
                                    Logging.d(TAG, "Settings unchanged, skipping update")
                                }
                            }
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(ACTION_SETTINGS_LOGOUT)
            addAction(ACTION_SETTINGS_DEACTIVATE)
            addAction(ACTION_SETTINGS_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsChangeReceiver, filter)
        }
        Logging.d(TAG, "Settings change receiver registered")
    }
    
    /**
     * Unregister settings change receiver
     */
    private fun unregisterSettingsChangeReceiver() {
        settingsChangeReceiver?.let {
            try {
                unregisterReceiver(it)
                Logging.d(TAG, "Settings change receiver unregistered")
            } catch (e: Exception) {
                Logging.e(TAG, "Error unregistering settings change receiver: ${e.message}", e)
            }
            settingsChangeReceiver = null
        }
    }
    
    private fun updateHERESDKOfflineMode(isConnected: Boolean) {
        try {
            // Check if HERE SDK is initialized before accessing it
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                sdkNativeEngine.setOfflineMode(!isConnected)
                Logging.d(TAG, "HERE SDK offline mode set to: ${!isConnected}")
            } else {
                Logging.d(TAG, "HERE SDK not yet initialized, will set offline mode when ready")
                // Store the desired offline mode state for when SDK is ready
                pendingOfflineMode = !isConnected
            }
        } catch (e: UnsatisfiedLinkError) {
            Logging.d(TAG, "HERE SDK native library not loaded yet, will set offline mode when ready")
            // Store the desired offline mode state for when SDK is ready
            pendingOfflineMode = !isConnected
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to update HERE SDK offline mode: ${e.message}", e)
        }
    }

    private fun disposeHERESDK() {
        // Free HERE SDK resources before the application shuts down.
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine != null) {
            sdkNativeEngine.dispose()
            // For safety reasons, we explicitly set the shared instance to null to avoid situations,
            // where a disposed instance is accidentally reused.
            SDKNativeEngine.setSharedInstance(null)
            Logging.d(TAG, "HERE SDK disposed in MainActivity")
        }
    }
}

@Composable
fun NavigationScreen(
    onStartNavigation: () -> Unit,
    onRefreshTrips: () -> Unit,
    latestTrip: TripResponse?,
    isLoadingTrips: Boolean,
    tripError: String?,
    vehicleInfo: VehicleSecurityManager.VehicleInfo?,
    showMap: Boolean,
    isDeactivated: Boolean,
    onShowMapChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Text(
                text = "Navigation Options",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Vehicle Status Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Vehicle Status",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    vehicleInfo?.let { info ->
                        Text(
                            "Company: ${info.companyName ?: "N/A"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "License: ${info.licensePlate ?: "N/A"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Registered: ${info.registrationDateTime ?: "N/A"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Security: ${if (info.hasValidKeyPair) "✓ Key Pair Ready" else "⚠ No Key Pair"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } ?: run {
                        Text(
                            "No vehicle registered", 
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Trip Status Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Latest Trip",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        IconButton(onClick = onRefreshTrips) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh trips")
                        }
                    }
                    
                    when {
                        isLoadingTrips -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                        latestTrip != null -> {
                            Text(
                                "From: ${latestTrip.route.origin.custom_name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "To: ${latestTrip.route.destination.custom_name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Status: ${latestTrip.status}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = when (latestTrip.status) {
                                    TripStatus.IN_PROGRESS.value -> MaterialTheme.colorScheme.primary
                                    TripStatus.SCHEDULED.value -> MaterialTheme.colorScheme.tertiary
                                    TripStatus.COMPLETED.value -> MaterialTheme.colorScheme.secondary
                                    TripStatus.PENDING.value -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                "Seats: ${latestTrip.seats}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        tripError != null -> {
                            Text(
                                text = tripError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> {
                            Text(
                                "No trip data available",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        // Navigation Options
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Navigation Options",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    // Show Map Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Show Map",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = showMap,
                            onCheckedChange = onShowMapChanged
                        )
                    }
                }
            }
        }

        // Start Navigation Button
        item {
            // Debug info for trip status
            latestTrip?.let { trip ->
                Logging.d("MainActivity", "=== TRIP STATUS DEBUG ===")
                Logging.d("MainActivity", "Trip ID: ${trip.id}")
                Logging.d("MainActivity", "Trip Status: '${trip.status}'")
                Logging.d("MainActivity", "Status matches PENDING: ${trip.status == TripStatus.PENDING.value}")
                Logging.d("MainActivity", "Status matches SCHEDULED: ${trip.status == TripStatus.SCHEDULED.value}")
                Logging.d("MainActivity", "Status matches IN_PROGRESS: ${trip.status == TripStatus.IN_PROGRESS.value}")
                Logging.d("MainActivity", "Is Loading: $isLoadingTrips")
                Logging.d("MainActivity", "Is Active Status: ${TripStatus.isActive(trip.status)}")
                Logging.d("MainActivity", "Navigation allowed: ${TripStatus.isActive(trip.status)}")
                Logging.d("MainActivity", "Button should be enabled: ${TripStatus.isActive(trip.status)}")
                Logging.d("MainActivity", "===============================")
            }
            
            Button(
                onClick = onStartNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                enabled = !isDeactivated && latestTrip != null && !isLoadingTrips && TripStatus.isActive(latestTrip.status)
            ) {
                Text(
                    when (latestTrip?.status) {
                        TripStatus.IN_PROGRESS.value -> "Continue Navigation"
                        TripStatus.SCHEDULED.value -> "Start Scheduled Trip"
                        TripStatus.PENDING.value -> "Start Trip"
                        else -> "Start Navigation"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }


        item {
            Button(
                onClick = {
                    val intent = Intent(context, com.gocavgo.validator.navigator.AutoModeHeadlessActivity::class.java)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                enabled = !isDeactivated,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text(
                    "Auto Mode",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Trip Route Display
        latestTrip?.let { trip ->
            item {
                Text(
                    text = "Route: ${trip.route.origin.google_place_name} → ${trip.route.destination.google_place_name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            if (trip.waypoints.isNotEmpty()) {
                item {
                    val waypointNames = trip.waypoints
                        .sortedBy { it.order }.joinToString(" → ") { it.location.google_place_name }

                    Text(
                        text = "Waypoints: $waypointNames",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MapDownloadDialog(
    progress: Int,
    totalSize: Int,
    message: String,
    status: String,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Dialog cannot be dismissed during download */ },
        title = {
            Text(
                text = "Downloading Map Data",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status message
                Text(
                    text = status.ifEmpty { "Preparing download..." },
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Progress bar
                LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = ProgressIndicatorDefaults.linearColor,
                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
                
                // Progress text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (totalSize > 0) {
                        Text(
                            text = "${totalSize}MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                // Detailed message
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = "This may take several minutes depending on your internet connection. The app will work normally once complete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun NavigationScreenPreview() {
    ValidatorTheme {
        NavigationScreen(
            onStartNavigation = { },
            onRefreshTrips = { },
            latestTrip = null,
            isLoadingTrips = false,
            tripError = null,
            vehicleInfo = null,
            showMap = true,
            isDeactivated = false,
            onShowMapChanged = { },
        )
    }
}
