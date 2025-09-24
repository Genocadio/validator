package com.gocavgo.validator

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel


class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
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
    private var isSimulated by mutableStateOf(true)

    // Managers
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var remoteDataManager: RemoteDataManager
    private lateinit var databaseManager: DatabaseManager
    
    // Periodic trip fetching
    private var periodicTripFetchJob: Job? = null
    private val periodicScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isActivityActive = false

    private val networkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "Network permissions granted")
            initializeNetworkMonitoring()
        } else {
            Log.w(TAG, "Some network permissions denied")
            // Still try to initialize with basic permissions
            initializeNetworkMonitoring()
        }
    }

    private fun initializeNetworkMonitoring() {
        Log.d(TAG, "Initializing network monitoring...")

        // Log initial network state
        logDetailedNetworkInfo()

        networkMonitor = NetworkMonitor(this) { connected, type, metered ->
            Log.d(TAG, "=== NETWORK STATE CHANGED ===")
            Log.d(TAG, "Connected: $connected")
            Log.d(TAG, "Connection Type: $type")
            Log.d(TAG, "Is Metered: $metered")
            Log.d(TAG, "============================")

            // Update UI state
            isConnected = connected
            connectionType = type
            isMetered = metered

            // Log detailed network info when state changes
            logDetailedNetworkInfo()
        }

        networkMonitor?.startMonitoring()
    }

    private fun initializeMqtt() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Log.w(TAG, "Vehicle not registered, skipping MQTT initialization")
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
            Log.d(TAG, "MQTT connection status: $connected")

            if (connected) {
                Log.i(TAG, "MQTT connected successfully")
                // Optionally publish initial status
                publishVehicleStatus("READY")
            } else {
                Log.w(TAG, "MQTT connection failed or lost")
            }
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
                val statusMessage = """
                {
                    "vehicle_id": "$vehicleId",
                    "status": "$status",
                    "timestamp": ${System.currentTimeMillis()},
                    "location": null
                }
            """.trimIndent()

                mqtt.publish("car/$vehicleId/status", statusMessage)
                    .whenComplete { result, throwable ->
                        if (throwable != null) {
                            Log.e(TAG, "Failed to publish vehicle status", throwable)
                        } else {
                            Log.d(TAG, "Vehicle status published: $status")
                        }
                    }
            }
        }
    }
    private fun setupMqttMessageListeners() {
        val vehicleId = vehicleSecurityManager.getVehicleId()

        // Listen for trip assignments
        mqttService?.addMessageListener("car/$vehicleId/trip") { topic, payload ->
            Log.d(TAG, "Trip assignment received: $payload")
            // Handle new trip assignment
            handleTripAssignment(payload)
        }

        // Listen for booking updates
        mqttService?.addMessageListener("trip/+/booking") { topic, payload ->
            Log.d(TAG, "Booking update received on $topic: $payload")
            // Handle booking updates
            Log.d(TAG, "Booking update for trip ")
        }

        // Listen for ping requests
        mqttService?.addMessageListener("car/$vehicleId/ping") { topic, payload ->
            Log.d(TAG, "Ping received: $payload")
            // MQTT service automatically handles pong response
        }
    }

    private fun handleTripAssignment(payload: String) {
        // Parse the trip assignment and refresh trips
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Processing trip assignment: $payload")
                // Force refresh trips from remote to get the new assignment
                forceRefreshFromRemote()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling trip assignment", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun logDetailedNetworkInfo() {
        try {
            if (NetworkUtils.hasNetworkPermissions(this)) {
                val report = NetworkUtils.createNetworkReport(this)
                networkDebugInfo = report
                Log.d(TAG, "\n$report")

                // Additional connectivity checks
                Log.d(TAG, "=== CONNECTIVITY TESTS ===")
                Log.d(TAG, "Internet Available: ${NetworkUtils.isConnectedToInternet(this)}")
                Log.d(TAG, "Connection Metered: ${NetworkUtils.isConnectionMetered(this)}")

                // Check roaming status if available
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    Log.d(TAG, "Is Roaming: ${NetworkUtils.isRoaming(this)}")
                }
                Log.d(TAG, "==========================")
            } else {
                val basicInfo = "Network permissions not available - basic monitoring only"
                networkDebugInfo = basicInfo
                Log.w(TAG, basicInfo)
            }
        } catch (e: Exception) {
            val errorInfo = "Error getting network info: ${e.message}"
            networkDebugInfo = errorInfo
            Log.e(TAG, errorInfo, e)
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
            Log.d(TAG, "Requesting network permissions: ${missingPermissions.joinToString()}")
            networkPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "All network permissions already granted")
            initializeNetworkMonitoring()
        }
    }

    private fun initializeManagers() {
        vehicleSecurityManager = VehicleSecurityManager(this)
        remoteDataManager = RemoteDataManager.getInstance()
        databaseManager = DatabaseManager.getInstance(this)
        
        // Get vehicle info
        vehicleInfo = vehicleSecurityManager.getVehicleInfo()
        
        Log.d(TAG, "=== VEHICLE SECURITY STATUS ===")
        Log.d(TAG, "Vehicle Registered: ${vehicleSecurityManager.isVehicleRegistered()}")
        Log.d(TAG, "Vehicle ID: ${vehicleSecurityManager.getVehicleId()}")
        Log.d(TAG, "Has Key Pair: ${vehicleSecurityManager.hasKeyPair()}")
        Log.d(TAG, "Completely Setup: ${vehicleSecurityManager.isCompletelySetup()}")
        
        vehicleInfo?.let { info ->
            Log.d(TAG, "Vehicle ID: ${info.vehicleId}")
            Log.d(TAG, "Company: ${info.companyName}")
            Log.d(TAG, "License Plate: ${info.licensePlate}")
            Log.d(TAG, "Registration Date: ${info.registrationDateTime}")
        }
        Log.d(TAG, "==============================")
        
        // Test database connection
        testDatabaseConnection()
        if (vehicleSecurityManager.isVehicleRegistered()) {
            initializeMqtt()
            // Start periodic trip fetching after initialization if activity is active
            if (isActivityActive) {
                startPeriodicTripFetching()
            }
        }
    }
    
    private fun testDatabaseConnection() {
        lifecycleScope.launch {
            try {
                if (vehicleSecurityManager.isVehicleRegistered()) {
                    val vehicleId = vehicleSecurityManager.getVehicleId()
                    val tripCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                    Log.d(TAG, "=== DATABASE STATUS ===")
                    Log.d(TAG, "Database connected successfully")
                    Log.d(TAG, "Trips in database for vehicle $vehicleId: $tripCount")
                    Log.d(TAG, "=========================")
                    
                    // Test TripStatus values
                    Log.d(TAG, "=== TRIP STATUS TEST ===")
                    Log.d(TAG, "PENDING.value: '${TripStatus.PENDING.value}'")
                    Log.d(TAG, "SCHEDULED.value: '${TripStatus.SCHEDULED.value}'")
                    Log.d(TAG, "IN_PROGRESS.value: '${TripStatus.IN_PROGRESS.value}'")
                    Log.d(TAG, "PENDING.value length: ${TripStatus.PENDING.value.length}")
                    Log.d(TAG, "SCHEDULED.value length: ${TripStatus.SCHEDULED.value.length}")
                    Log.d(TAG, "IN_PROGRESS.value length: ${TripStatus.IN_PROGRESS.value.length}")
                    Log.d(TAG, "=========================")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Database connection test failed: ${e.message}", e)
            }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private fun fetchLatestVehicleTrip() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Log.w(TAG, "Vehicle not registered, cannot fetch trips")
            tripError = "Vehicle not registered. Please complete vehicle authentication first."
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()
        Log.d(TAG, "Checking trips for vehicle ID: $vehicleId")
        
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
                        Log.d(TAG, "Active trip loaded from database: ${latestTrip?.id}")
                        Log.d(TAG, "Trip details: ${latestTrip?.route?.origin?.google_place_name} → ${latestTrip?.route?.destination?.google_place_name}")
                        Log.d(TAG, "Status: ${latestTrip?.status}")
                        Log.d(TAG, "Connection mode: ${latestTrip?.connection_mode}")
                        Log.d(TAG, "DEBUG: Trip status length: ${latestTrip?.status?.length}")
                        Log.d(TAG, "DEBUG: Trip status bytes: ${latestTrip?.status?.toByteArray().contentToString()}")
                    }
                } else {
                    // No active trips, check if we have any trips at all
                    val dbTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                    if (dbTrip != null) {
                        latestTrip = dbTrip
                        tripError = null // Clear any previous errors
                        Log.d(TAG, "Latest trip loaded from database: ${latestTrip?.id}")
                        Log.d(TAG, "Trip details: ${latestTrip?.route?.origin?.google_place_name} → ${latestTrip?.route?.destination?.google_place_name}")
                        Log.d(TAG, "Status: ${latestTrip?.status}")
                        Log.d(TAG, "Connection mode: ${latestTrip?.connection_mode}")
                        Log.d(TAG, "DEBUG: Trip status length: ${latestTrip?.status?.length}")
                        Log.d(TAG, "DEBUG: Trip status bytes: ${latestTrip?.status?.toByteArray().contentToString()}")
                    } else {
                        // No trips in database, clear latest trip
                        latestTrip = null
                        Log.d(TAG, "No trips found in database")
                    }
                    
                    // Only fetch from remote if we don't have any trips or if all trips are completed
                    val tripCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                    if (tripCount == 0 || (dbTrip != null && TripStatus.isCompleted(dbTrip.status))) {
                        Log.d(TAG, "No active trips found, fetching from remote API...")
                        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 1)
                        
                        when {
                            result.isSuccess() -> {
                                val newTripCount = result.getDataOrNull() ?: 0
                                // Always check database after sync to handle cleanup cases
                                val updatedTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                                if (updatedTrip != null) {
                                    latestTrip = updatedTrip
                                    tripError = null // Clear any previous errors
                                    Log.d(TAG, "Trip updated from database: ${latestTrip?.id}")
                                    Log.d(TAG, "DEBUG: Trip status: ${latestTrip?.status}")
                                    Log.d(TAG, "DEBUG: Trip status length: ${latestTrip?.status?.length}")
                                    Log.d(TAG, "DEBUG: Trip status bytes: ${latestTrip?.status?.toByteArray().contentToString()}")
                                } else {
                                    // No trips in database after sync (either no new trips or cleanup removed all)
                                    latestTrip = null
                                    if (newTripCount == 0) {
                                        Log.w(TAG, "No trips found for vehicle after sync")
                                        tripError = "No trips available for this vehicle"
                                    } else {
                                        Log.w(TAG, "Trips were cleaned up during sync")
                                        tripError = "Previous trips are no longer available"
                                    }
                                }
                            }
                            result.isError() -> {
                                val errorMessage = result.getErrorOrNull() ?: "Unknown error occurred"
                                Log.e(TAG, "Failed to fetch trips: $errorMessage")
                                tripError = "Failed to fetch trips: $errorMessage"
                            }
                        }
                    } else {
                        Log.d(TAG, "Using existing trip from database, no remote fetch needed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while checking trips", e)
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

    private fun handleAndroidPermissions() {
        permissionsRequestor = PermissionsRequestor(this)
        permissionsRequestor!!.request(object :
            PermissionsRequestor.ResultListener {
            override fun permissionsGranted() {
                Log.d(TAG, "All permissions granted by user.")
            }

            override fun permissionsDenied() {
                Log.e(TAG, "Permissions denied by user.")
            }
        })
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Logging.setTagEnabled(TAG, false)
        permissionsRequestor = PermissionsRequestor(this)

        checkAndRequestNetworkPermissions()
        initializeManagers()


        
        setContent {
            ValidatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavigationScreen(
                        onStartNavigation = { startNavigator() },
                        onVehicleAuth = { startVehicleAuth() },
                        onRefreshTrips = { forceRefreshFromRemote() },
                        latestTrip = latestTrip,
                        isLoadingTrips = isLoadingTrips,
                        tripError = tripError,
                        vehicleInfo = vehicleInfo,
                        showMap = showMap,
                        isSimulated = isSimulated,
                        onShowMapChanged = { showMap = it },
                        onIsSimulatedChanged = { isSimulated = it },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        handleAndroidPermissions()

        // Fetch trips after UI is set up
        fetchLatestVehicleTrip()
    }

    private fun startNavigator() {
        if (latestTrip == null) {
            Log.w(TAG, "No trip available for navigation")
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
                    Log.d(TAG, "Trip ${latestTrip!!.id} status updated from $currentStatus to $newStatus")
                    
                    // Update local trip status
                    latestTrip = latestTrip!!.copy(status = newStatus)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update trip status: ${e.message}", e)
            }
        }

        val intent = Intent(this, Navigator::class.java)
        intent.putExtra(Navigator.EXTRA_TRIP_ID, latestTrip!!.id)
        intent.putExtra(Navigator.EXTRA_SHOW_MAP, showMap)
        intent.putExtra(Navigator.EXTRA_IS_SIMULATED, isSimulated)
        intent.putExtra(Navigator.EXTRA_NETWORK_CONNECTED, isConnected)
        intent.putExtra(Navigator.EXTRA_CONNECTION_TYPE, connectionType)
        intent.putExtra(Navigator.EXTRA_IS_METERED, isMetered)
        startActivity(intent)
    }

    private fun startVehicleAuth() {
        val intent = Intent(this, com.gocavgo.validator.security.VehicleAuthActivity::class.java)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        
        // Mark activity as active
        isActivityActive = true
        
        // Notify MQTT service that app is in foreground
        mqttService?.onAppForeground()
        
        // Refresh vehicle info and trips when returning from VehicleAuth
        vehicleInfo = vehicleSecurityManager.getVehicleInfo()
        if (vehicleSecurityManager.isVehicleRegistered()) {
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
                    Log.d(TAG, "Found in-progress trip ${activeTrip.id}, checking if should be completed")
                    
                    // For now, we'll just mark it as completed when returning to MainActivity
                    // In a real implementation, you'd check actual navigation completion status
//                    databaseManager.updateTripStatus(activeTrip.id, TripStatus.COMPLETED.value)
                    Log.d(TAG, "Trip ${activeTrip.id} marked as completed")
                    
                    // Update local trip if it's the current one
//                    if (latestTrip?.id == activeTrip.id) {
//                        latestTrip = latestTrip!!.copy(status = TripStatus.COMPLETED.value)
//                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check trip completion: ${e.message}", e)
            }
        }
    }
    
    private fun forceRefreshFromRemote() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Log.w(TAG, "Vehicle not registered, cannot refresh trips")
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()
        Log.d(TAG, "Force refreshing trips from remote for vehicle ID: $vehicleId")
        
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
                            Log.d(TAG, "Trips refreshed from remote: ${latestTrip?.id}")
                        } else {
                            // No trips in database after sync (either no new trips or cleanup removed all)
                            latestTrip = null
                            if (tripCount == 0) {
                                Log.w(TAG, "No trips found for vehicle after refresh")
                                tripError = "No trips available for this vehicle"
                            } else {
                                Log.w(TAG, "Trips were cleaned up during refresh")
                                tripError = "Previous trips are no longer available"
                            }
                        }
                    }
                    result.isError() -> {
                        val errorMessage = result.getErrorOrNull() ?: "Unknown error occurred"
                        Log.e(TAG, "Failed to refresh trips: $errorMessage")
                        tripError = "Failed to refresh trips: $errorMessage"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while refreshing trips", e)
                tripError = "Exception: ${e.message}"
            } finally {
                isLoadingTrips = false
            }
        }
    }
    
    private fun startPeriodicTripFetching() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Log.w(TAG, "Vehicle not registered, skipping periodic trip fetching")
            return
        }
        
        // Cancel existing job if any
        periodicTripFetchJob?.cancel()
        
        Log.d(TAG, "Starting periodic trip fetching every 3 minutes")
        
        periodicTripFetchJob = periodicScope.launch {
            while (isActivityActive) {
                try {
                    // Check internet connectivity before fetching
                    if (NetworkUtils.isConnectedToInternet(this@MainActivity)) {
                        Log.d(TAG, "Internet available, performing periodic trip fetch")
                        performPeriodicTripFetch()
                    } else {
                        Log.d(TAG, "No internet connection, skipping periodic trip fetch")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic trip fetch: ${e.message}", e)
                }
                
                // Wait 3 minutes (180,000 milliseconds)
                delay(180_000)
            }
        }
    }
    
    private fun stopPeriodicTripFetching() {
        Log.d(TAG, "Stopping periodic trip fetching")
        periodicTripFetchJob?.cancel()
        periodicTripFetchJob = null
    }
    
    private suspend fun performPeriodicTripFetch() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Log.w(TAG, "Vehicle not registered, skipping periodic trip fetch")
            return
        }
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        Log.d(TAG, "=== PERIODIC TRIP FETCH ===")
        Log.d(TAG, "Fetching trips for vehicle ID: $vehicleId")
        
        try {
            // Get current trip count before sync
            val beforeCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
            Log.d(TAG, "Current trips in database before sync: $beforeCount")
            
            val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
            
            when {
                result.isSuccess() -> {
                    val tripCount = result.getDataOrNull() ?: 0
                    Log.d(TAG, "Periodic fetch successful: $tripCount trips synced")
                    
                    // Get trip count after sync
                    val afterCount = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                    Log.d(TAG, "Current trips in database after sync: $afterCount")
                    
                    // Update latest trip on main thread
                    if (isActivityActive) {
                        lifecycleScope.launch {
                            val updatedTrip = databaseManager.getLatestTripByVehicle(vehicleId.toInt())
                            if (updatedTrip != null) {
                                latestTrip = updatedTrip
                                tripError = null // Clear any previous errors
                                Log.d(TAG, "Latest trip updated: ${latestTrip?.id}")
                            } else {
                                // Clear latest trip if no trips available after sync
                                latestTrip = null
                                Log.d(TAG, "No trips available after periodic sync, cleared latest trip")
                            }
                        }
                    }
                }
                result.isError() -> {
                    val errorMessage = result.getErrorOrNull() ?: "Unknown error occurred"
                    Log.w(TAG, "Periodic trip fetch failed: $errorMessage")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during periodic trip fetch: ${e.message}", e)
        }
        
        Log.d(TAG, "=== END PERIODIC TRIP FETCH ===")
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
    }
}

@Composable
fun NavigationScreen(
    onStartNavigation: () -> Unit,
    onVehicleAuth: () -> Unit,
    onRefreshTrips: () -> Unit,
    latestTrip: TripResponse?,
    isLoadingTrips: Boolean,
    tripError: String?,
    vehicleInfo: VehicleSecurityManager.VehicleInfo?,
    showMap: Boolean,
    isSimulated: Boolean,
    onShowMapChanged: (Boolean) -> Unit,
    onIsSimulatedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
                    
                    // Is Simulated Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Simulated Mode",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isSimulated,
                            onCheckedChange = onIsSimulatedChanged
                        )
                    }
                }
            }
        }

        // Start Navigation Button
        item {
            // Debug info for trip status
            latestTrip?.let { trip ->
                Log.d("MainActivity", "=== TRIP STATUS DEBUG ===")
                Log.d("MainActivity", "Trip ID: ${trip.id}")
                Log.d("MainActivity", "Trip Status: '${trip.status}'")
                Log.d("MainActivity", "Status matches PENDING: ${trip.status == TripStatus.PENDING.value}")
                Log.d("MainActivity", "Status matches SCHEDULED: ${trip.status == TripStatus.SCHEDULED.value}")
                Log.d("MainActivity", "Status matches IN_PROGRESS: ${trip.status == TripStatus.IN_PROGRESS.value}")
                Log.d("MainActivity", "Is Loading: $isLoadingTrips")
                Log.d("MainActivity", "Is Active Status: ${TripStatus.isActive(trip.status)}")
                Log.d("MainActivity", "Navigation allowed: ${TripStatus.isActive(trip.status)}")
                Log.d("MainActivity", "Button should be enabled: ${TripStatus.isActive(trip.status)}")
                Log.d("MainActivity", "===============================")
            }
            
            Button(
                onClick = onStartNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                enabled = latestTrip != null && !isLoadingTrips && TripStatus.isActive(latestTrip.status)
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
                onClick = onVehicleAuth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    "Vehicle Authentication Setup",
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

@Preview(showBackground = true)
@Composable
fun NavigationScreenPreview() {
    ValidatorTheme {
        NavigationScreen(
            onStartNavigation = { },
            onVehicleAuth = { },
            onRefreshTrips = { },
            latestTrip = null,
            isLoadingTrips = false,
            tripError = null,
            vehicleInfo = null,
            showMap = true,
            isSimulated = true,
            onShowMapChanged = { },
            onIsSimulatedChanged = { }
        )
    }
}
