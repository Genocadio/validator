package com.gocavgo.validator.trip

import android.app.Service
import android.content.Context
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripEventMessage
import com.gocavgo.validator.dataclass.VehicleSettings
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.navigator.App
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.sync.SyncCoordinator
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.util.TripDataConverter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Callback interface for active trip events
 */
interface ActiveTripCallback {
    /**
     * Called when an active trip is found (first time or after being cleared)
     */
    fun onActiveTripFound(trip: TripResponse)
    
    /**
     * Called when the active trip changes (different trip ID)
     */
    fun onActiveTripChanged(newTrip: TripResponse, oldTrip: TripResponse?)
    
    /**
     * Called when a trip is cancelled
     */
    fun onTripCancelled(tripId: Int)
    
    /**
     * Called when an error occurs during trip fetching
     */
    fun onError(error: String)
    
    /**
     * Called when countdown text updates (for UI display)
     */
    fun onCountdownTextUpdate(countdownText: String)
    
    /**
     * Called when countdown completes and navigation should start
     */
    fun onCountdownComplete(trip: TripResponse)
    
    /**
     * Called when navigation should start immediately (no countdown needed)
     */
    fun onNavigationStartRequested(trip: TripResponse, allowResume: Boolean = false)
    
    /**
     * Called when a SCHEDULED trip is received but countdown hasn't started yet (more than 2 minutes before departure)
     * @param trip The scheduled trip
     * @param departureTimeMillis The departure time in milliseconds
     */
    fun onTripScheduled(trip: TripResponse, departureTimeMillis: Long)
    
    /**
     * Called when settings are synced and need to be handled (logout/deactivate detection)
     * @param settings The synced settings
     * @param isNavigating Whether navigation is currently active
     * @return true if settings require immediate action (logout/deactivate), false otherwise
     */
    fun onSettingsSynced(settings: VehicleSettings, isNavigating: Boolean): Boolean
    
    /**
     * Called when trip state needs to be verified after network restore
     * @param trip The trip to verify
     */
    fun onTripStateVerificationNeeded(trip: TripResponse)
    
    /**
     * Called when immediate backend fetch completes
     * @param trip The active trip found, or null if none
     */
    fun onImmediateBackendFetchComplete(trip: TripResponse?)
    
    /**
     * Called when silent backend fetch finds an active trip
     * @param trip The active trip found
     */
    fun onSilentBackendFetchFoundTrip(trip: TripResponse)
}

/**
 * Listens for active trips from multiple sources:
 * - Database (local cache)
 * - HTTP Backend (periodic sync)
 * - MQTT (real-time updates)
 * 
 * This class encapsulates all active trip fetching logic and can be reused
 * across different activities/services.
 */
class ActiveTripListener(
    private val context: Context
) {
    companion object {
        private const val TAG = "ActiveTripListener"
        private const val PERIODIC_FETCH_INTERVAL_MS = 7 * 60 * 1000L // 7 minutes
        private const val TWO_MINUTES_IN_MS = 2 * 60 * 1000L
    }
    
    private lateinit var databaseManager: DatabaseManager
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private var mqttService: MqttService? = null
    private var networkMonitor: NetworkMonitor? = null
    private var settingsManager: VehicleSettingsManager? = null
    private var app: App? = null
    
    private var callback: ActiveTripCallback? = null
    private var lifecycleScope: CoroutineScope? = null
    private val periodicScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State tracking
    private var currentActiveTrip: TripResponse? = null
    private val isActive = AtomicBoolean(false)
    private val isMqttCallbackRegistered = AtomicBoolean(false)
    private var periodicFetchJob: Job? = null
    private var lastBackendFetchTime = AtomicLong(0)
    private var networkOfflineTime = AtomicLong(0)
    
    // Network state
    private var isConnected = true
    
    // Countdown state
    private val _countdownText = MutableStateFlow<String>("")
    val countdownText: StateFlow<String> = _countdownText.asStateFlow()
    private var countdownJob: Job? = null
    
    /**
     * Start listening for active trips
     * 
     * @param scope Lifecycle scope for coroutines (will be cancelled when scope is cancelled)
     * @param callback Callback to receive trip events
     * @param settingsManager Optional settings manager for network restore sync
     * @param app Optional App instance for MQTT state publishing
     */
    fun start(
        scope: CoroutineScope,
        callback: ActiveTripCallback,
        settingsManager: VehicleSettingsManager? = null,
        app: App? = null
    ) {
        if (isActive.get()) {
            Logging.w(TAG, "ActiveTripListener already started, stopping first")
            stop()
        }
        
        Logging.d(TAG, "=== STARTING ACTIVE TRIP LISTENER ===")
        
        this.lifecycleScope = scope
        this.callback = callback
        
        // Initialize managers
        databaseManager = DatabaseManager.getInstance(context)
        vehicleSecurityManager = VehicleSecurityManager(context)
        this.settingsManager = settingsManager
        this.app = app
        
        // Get MQTT service instance
        mqttService = MqttService.getInstance()
        
        isActive.set(true)
        
        // Initialize network monitoring
        initializeNetworkMonitoring()
        
        // Register MQTT callback
        registerMqttTripCallback()
        
        // Fetch from backend on launch
        scope.launch(Dispatchers.IO) {
            fetchTripsFromBackend()
        }
        
        // Start periodic backend fetch
        startPeriodicBackendFetch()
        
        Logging.d(TAG, "=== ACTIVE TRIP LISTENER STARTED ===")
    }
    
    /**
     * Stop listening for active trips and clean up resources
     */
    fun stop() {
        if (!isActive.get()) {
            Logging.d(TAG, "ActiveTripListener not active, nothing to stop")
            return
        }
        
        Logging.d(TAG, "=== STOPPING ACTIVE TRIP LISTENER ===")
        
        isActive.set(false)
        
        // Cancel periodic fetch
        periodicFetchJob?.cancel()
        periodicFetchJob = null
        
        // Cancel countdown
        countdownJob?.cancel()
        countdownJob = null
        _countdownText.value = ""
        
        // Unregister MQTT callback
        unregisterMqttTripCallback()
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        
        // Clear state
        currentActiveTrip = null
        callback = null
        lifecycleScope = null
        
        Logging.d(TAG, "=== ACTIVE TRIP LISTENER STOPPED ===")
    }
    
    /**
     * Get the current active trip from database
     */
    suspend fun getCurrentActiveTrip(): TripResponse? {
        return try {
            if (!vehicleSecurityManager.isVehicleRegistered()) {
                return null
            }
            val vehicleId = vehicleSecurityManager.getVehicleId()
            databaseManager.getActiveTripByVehicle(vehicleId.toInt())
        } catch (e: Exception) {
            Logging.e(TAG, "Error getting current active trip: ${e.message}", e)
            null
        }
    }
    
    /**
     * Manually trigger a backend fetch
     */
    fun fetchFromBackend() {
        if (!isActive.get()) {
            Logging.w(TAG, "Listener not active, cannot fetch from backend")
            return
        }
        
        lifecycleScope?.launch(Dispatchers.IO) {
            fetchTripsFromBackend()
        }
    }
    
    // ==================== Private Methods ====================
    
    private fun initializeNetworkMonitoring() {
        if (!NetworkUtils.hasNetworkPermissions(context)) {
            Logging.w(TAG, "Network permissions not available, using basic monitoring")
            return
        }
        
        networkMonitor = NetworkMonitor(context) { connected, _, _ ->
            Logging.d(TAG, "Network state changed: connected=$connected")
            isConnected = connected
            
            if (connected) {
                handleNetworkRestored()
            } else {
                handleNetworkLost()
            }
        }
        
        networkMonitor?.startMonitoring()
        Logging.d(TAG, "Network monitoring started")
    }
    
    private fun handleNetworkLost() {
        val offlineTime = System.currentTimeMillis()
        networkOfflineTime.set(offlineTime)
        Logging.d(TAG, "Network lost at: $offlineTime")
    }
    
    private fun handleNetworkRestored() {
        val offlineTime = networkOfflineTime.get()
        if (offlineTime == 0L) return
        
        val offlineDurationMs = System.currentTimeMillis() - offlineTime
        val offlineMinutes = offlineDurationMs / (60 * 1000)
        
        Logging.d(TAG, "=== NETWORK RESTORED ===")
        Logging.d(TAG, "Offline duration: $offlineMinutes minutes")
        
        // Use the public method with default values (not navigating, no trip)
        lifecycleScope?.launch(Dispatchers.IO) {
            performNetworkRestoreApiSync(isNavigating = false)
        }
        
        networkOfflineTime.set(0)
    }
    
    /**
     * Handle network restore with navigation state check
     * @param isNavigating Whether navigation is currently active
     * @param currentTrip The current trip if navigating
     */
    fun handleNetworkRestored(isNavigating: Boolean, currentTrip: TripResponse?) {
        val offlineTime = networkOfflineTime.get()
        if (offlineTime == 0L) return
        
        val offlineDurationMs = System.currentTimeMillis() - offlineTime
        val offlineMinutes = offlineDurationMs / (60 * 1000)
        
        Logging.d(TAG, "=== NETWORK RESTORED ===")
        Logging.d(TAG, "Offline duration: $offlineMinutes minutes")
        Logging.d(TAG, "Is navigating: $isNavigating")
        
        lifecycleScope?.launch(Dispatchers.IO) {
            if (isNavigating && mqttService != null && currentTrip != null) {
                Logging.d(TAG, "Navigating - waiting for MQTT connection then publishing MQTT states")
                waitForMqttConnectionAndPublish(currentTrip)
            } else {
                Logging.d(TAG, "Not navigating - syncing trips and settings via API")
                performNetworkRestoreApiSync()
            }
        }
        
        networkOfflineTime.set(0)
    }
    
    private suspend fun performNetworkRestoreSync() {
        try {
            Logging.d(TAG, "=== NETWORK RESTORE SYNC ===")
            
            val vehicleId = vehicleSecurityManager.getVehicleId()
            val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
            
            when {
                result.isSuccess() -> {
                    val tripCount = result.getDataOrNull() ?: 0
                    Logging.d(TAG, "Network restore sync successful: $tripCount trips")
                    
                    // Check if active trip changed
                    val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                    if (activeTrip != null) {
                        handleActiveTripFound(activeTrip)
                    }
                }
                result.isError() -> {
                    Logging.e(TAG, "Network restore sync failed: ${result.getErrorOrNull()}")
                }
            }
            
            Logging.d(TAG, "=== END NETWORK RESTORE SYNC ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during network restore sync: ${e.message}", e)
        }
    }
    
    /**
     * Sync trips and settings via API when network is restored and not navigating
     * This runs even if countdown is present
     * @param isNavigating Whether navigation is currently active
     */
    suspend fun performNetworkRestoreApiSync(isNavigating: Boolean = false) {
        try {
            Logging.d(TAG, "=== NETWORK RESTORE API SYNC ===")
            
            val vehicleId = vehicleSecurityManager.getVehicleId()
            
            // 1. Sync trips from API
            Logging.d(TAG, "Syncing trips from API...")
            val tripResult = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
            
            when {
                tripResult.isSuccess() -> {
                    val tripCount = tripResult.getDataOrNull() ?: 0
                    Logging.d(TAG, "Trip sync successful: $tripCount trips")
                    
                    // Check if active trip changed
                    val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                    if (activeTrip != null) {
                        val currentTripId = currentActiveTrip?.id
                        if (activeTrip.id != currentTripId) {
                            Logging.d(TAG, "Active trip changed after network restore: ${activeTrip.id}")
                            handleActiveTripFound(activeTrip)
                        } else {
                            // Same trip - verify state matches
                            Logging.d(TAG, "Same trip - verifying state after network restore")
                            callback?.onTripStateVerificationNeeded(activeTrip)
                        }
                    }
                }
                tripResult.isError() -> {
                    Logging.e(TAG, "Trip sync failed: ${tripResult.getErrorOrNull()}")
                }
            }
            
            // 2. Sync settings from API (if settings manager is available)
            val settingsMgr = settingsManager
            if (settingsMgr != null) {
                Logging.d(TAG, "Syncing settings from API...")
                val settingsResult = settingsMgr.fetchSettingsFromApi(vehicleId.toInt())
                
                settingsResult.onSuccess { settings ->
                    Logging.d(TAG, "Settings sync successful")
                    val requiresAction = callback?.onSettingsSynced(settings, isNavigating) ?: false
                    if (requiresAction) {
                        Logging.d(TAG, "Settings require immediate action (logout/deactivate)")
                    }
                }.onFailure { error ->
                    Logging.e(TAG, "Settings sync failed: ${error.message}")
                    // Use saved settings if available
                    val savedSettings = settingsMgr.getSettings(vehicleId.toInt())
                    savedSettings?.let {
                        callback?.onSettingsSynced(it, isNavigating)
                    }
                }
            }
            
            Logging.d(TAG, "=== NETWORK RESTORE API SYNC COMPLETE ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during network restore API sync: ${e.message}", e)
        }
    }
    
    /**
     * Wait for MQTT connection to be established, then publish trip status and heartbeat
     */
    suspend fun waitForMqttConnectionAndPublish(trip: TripResponse) {
        try {
            val mqttServiceInstance = mqttService
            if (mqttServiceInstance == null) {
                Logging.w(TAG, "MQTT service is null, cannot publish states on network restore")
                return
            }
            
            // Wait for MQTT connection (with timeout)
            var attempts = 0
            val maxAttempts = 30 // 30 seconds timeout
            while (!mqttServiceInstance.isConnected() && attempts < maxAttempts) {
                delay(1000) // Wait 1 second between checks
                attempts++
                if (attempts % 5 == 0) {
                    Logging.d(TAG, "Waiting for MQTT connection... (attempt $attempts/$maxAttempts)")
                }
            }
            
            if (mqttServiceInstance.isConnected()) {
                Logging.d(TAG, "MQTT connected after network restore, publishing states...")
                publishMqttStatesOnNetworkRestore(trip)
            } else {
                Logging.w(TAG, "MQTT connection timeout after network restore, will retry when connected")
                // Set up connection callback to publish when connected
                mqttServiceInstance.setConnectionCallback { connected ->
                    if (connected && currentActiveTrip != null) {
                        Logging.d(TAG, "MQTT connected via callback after network restore, publishing states...")
                        lifecycleScope?.launch {
                            publishMqttStatesOnNetworkRestore(trip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error waiting for MQTT connection: ${e.message}", e)
        }
    }
    
    /**
     * Publish MQTT states immediately when network is restored during navigation
     * Publishes trip status and heartbeat (omits trip data if not navigating)
     */
    suspend fun publishMqttStatesOnNetworkRestore(trip: TripResponse) {
        try {
            Logging.d(TAG, "=== PUBLISHING MQTT STATES ON NETWORK RESTORE ===")
            
            val mqttServiceInstance = mqttService
            if (mqttServiceInstance == null || !mqttServiceInstance.isConnected()) {
                Logging.w(TAG, "MQTT service not connected - cannot publish states")
                return
            }
            
            // Get current location for MQTT messages from App
            val currentLocation = app?.getNavigationExample()?.getLastKnownLocation()?.coordinates
            val mqttLocation = if (currentLocation != null) {
                MqttService.Location(
                    latitude = currentLocation.latitude,
                    longitude = currentLocation.longitude
                )
            } else {
                null
            }
            
            // 1. Publish trip status update immediately
            Logging.d(TAG, "Publishing trip status update for trip ${trip.id}")
            mqttServiceInstance.sendTripStatusUpdateBackend(
                tripId = trip.id.toString(),
                status = trip.status,
                location = mqttLocation
            ).whenComplete { result, throwable ->
                if (throwable != null) {
                    Logging.e(TAG, "Failed to publish trip status on network restore: ${throwable.message}", throwable)
                } else {
                    Logging.d(TAG, "Trip status published successfully on network restore")
                }
            }
            
            // 2. Publish heartbeat immediately
            Logging.d(TAG, "Publishing heartbeat on network restore")
            mqttServiceInstance.sendHeartbeatResponse(
                pingTime = System.currentTimeMillis(),
                additionalData = emptyMap()
            ).whenComplete { result, throwable ->
                if (throwable != null) {
                    Logging.e(TAG, "Failed to publish heartbeat on network restore: ${throwable.message}", throwable)
                } else {
                    Logging.d(TAG, "Heartbeat published successfully on network restore")
                }
            }
            
            // 3. Trigger immediate trip progress update (bypass throttling)
            app?.getTripSectionValidator()?.let { validator ->
                Logging.d(TAG, "Triggering immediate trip progress update (bypass throttling)")
                validator.publishTripProgressUpdateImmediate()
            }
            
            // 4. Check settings even when navigating (to detect logout/deactivate immediately)
            Logging.d(TAG, "Checking settings during navigation (to detect logout/deactivate)")
            checkSettingsDuringNavigation()
            
            Logging.d(TAG, "=== MQTT STATES PUBLISHED ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing MQTT states on network restore: ${e.message}", e)
        }
    }
    
    /**
     * Check settings during navigation to detect logout/deactivate immediately
     * This allows immediate exit even during navigation
     */
    fun checkSettingsDuringNavigation() {
        if (settingsManager == null) {
            Logging.w(TAG, "Settings manager not available, cannot check settings")
            return
        }
        
        lifecycleScope?.launch(Dispatchers.IO) {
            try {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val result = settingsManager!!.fetchSettingsFromApi(vehicleId.toInt())
                
                result.onSuccess { settings ->
                    // If logout or deactivate = true, notify callback
                    if (settings.logout || settings.deactivate) {
                        Logging.w(TAG, "Logout or deactivate detected during navigation")
                        callback?.onSettingsSynced(settings, isNavigating = true)
                    } else {
                        Logging.d(TAG, "Settings checked during navigation - no logout/deactivate detected")
                        callback?.onSettingsSynced(settings, isNavigating = true)
                    }
                }.onFailure { error ->
                    Logging.w(TAG, "Failed to check settings during navigation: ${error.message}")
                    // Don't exit on API failure - continue navigation
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception checking settings during navigation: ${e.message}", e)
            }
        }
    }
    
    /**
     * Perform immediate backend fetch (for network recovery)
     */
    suspend fun performImmediateBackendFetch() {
        Logging.d(TAG, "=== IMMEDIATE BACKEND FETCH (NETWORK RECOVERY) ===")
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
        
        val activeTrip = when {
            result.isSuccess() -> {
                val tripCount = result.getDataOrNull() ?: 0
                Logging.d(TAG, "Network recovery sync successful: $tripCount trips")
                
                databaseManager.getActiveTripByVehicle(vehicleId.toInt())
            }
            result.isError() -> {
                Logging.e(TAG, "Network recovery sync failed: ${result.getErrorOrNull()}")
                null
            }
            else -> null
        }
        
        callback?.onImmediateBackendFetchComplete(activeTrip)
        
        Logging.d(TAG, "=== END NETWORK RECOVERY FETCH ===")
    }
    
    /**
     * Perform silent backend fetch (returns true if active trip found)
     */
    suspend fun performSilentBackendFetch(): Boolean {
        Logging.d(TAG, "Silent backend fetch")
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
        
        return when {
            result.isSuccess() -> {
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                if (activeTrip != null) {
                    // Skip trips with status COMPLETED to prevent restarting navigation
                    if (activeTrip.status.equals("COMPLETED", ignoreCase = true)) {
                        Logging.d(TAG, "Active trip ${activeTrip.id} is COMPLETED - skipping to prevent restart")
                        return false
                    }
                    
                    Logging.d(TAG, "Active trip found: ${activeTrip.id}")
                    callback?.onSilentBackendFetchFoundTrip(activeTrip)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
    
    private suspend fun fetchTripsFromBackend() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, skipping backend fetch")
            return
        }
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "=== FETCHING TRIPS FROM BACKEND ===")
        Logging.d(TAG, "Vehicle ID: $vehicleId")
        
        try {
            // Check database before sync
            val beforeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
            Logging.d(TAG, "Active trip in DB BEFORE sync: ${beforeTrip?.id ?: "none"} (status: ${beforeTrip?.status ?: "N/A"})")
            
            // Fetch from backend
            val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
            
            when {
                result.isSuccess() -> {
                    val tripCount = result.getDataOrNull() ?: 0
                    Logging.d(TAG, "Backend sync successful: $tripCount trips synced")
                    lastBackendFetchTime.set(System.currentTimeMillis())
                    
                    // Load active trip from database after sync
                    val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                    Logging.d(TAG, "Active trip in DB AFTER sync: ${activeTrip?.id ?: "none"} (status: ${activeTrip?.status ?: "N/A"})")
                    
                    if (activeTrip != null) {
                        Logging.d(TAG, "Active trip found after sync:")
                        Logging.d(TAG, "  - ID: ${activeTrip.id}")
                        Logging.d(TAG, "  - Status: ${activeTrip.status}")
                        Logging.d(TAG, "  - Route: ${activeTrip.route.origin.google_place_name} → ${activeTrip.route.destination.google_place_name}")
                        handleActiveTripFound(activeTrip)
                    } else {
                        Logging.d(TAG, "No active trips after backend sync")
                    }
                }
                result.isError() -> {
                    val error = result.getErrorOrNull() ?: "Unknown error"
                    Logging.e(TAG, "Backend sync failed: $error")
                    callback?.onError("Backend sync failed: $error")
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Exception during backend fetch: ${e.message}", e)
            callback?.onError("Exception during backend fetch: ${e.message}")
        }
        
        Logging.d(TAG, "=== BACKEND FETCH COMPLETE ===")
    }
    
    private fun startPeriodicBackendFetch() {
        periodicFetchJob?.cancel()
        
        Logging.d(TAG, "Starting periodic backend fetch every 7 minutes")
        
        periodicFetchJob = periodicScope.launch {
            while (this@ActiveTripListener.isActive.get()) {
                delay(PERIODIC_FETCH_INTERVAL_MS)
                
                try {
                    // Check internet connectivity
                    if (!NetworkUtils.isConnectedToInternet(context)) {
                        Logging.d(TAG, "No internet, skipping periodic fetch")
                        continue
                    }
                    
                    performPeriodicFetch()
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in periodic fetch: ${e.message}", e)
                }
            }
        }
    }
    
    private suspend fun performPeriodicFetch() {
        Logging.d(TAG, "=== PERIODIC BACKEND FETCH ===")
        
        // Check MQTT freshness (skip if MQTT updated within 2 minutes)
        if (!SyncCoordinator.shouldFetchFromBackend(context)) {
            Logging.d(TAG, "Skipping - MQTT data is fresh (within 2 minutes)")
            Logging.d(TAG, "Sync status: ${SyncCoordinator.getSyncStatus(context)}")
            return
        }
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
        
        when {
            result.isSuccess() -> {
                val tripCount = result.getDataOrNull() ?: 0
                Logging.d(TAG, "Periodic sync successful: $tripCount trips")
                lastBackendFetchTime.set(System.currentTimeMillis())
                
                // Check if active trip changed
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                if (activeTrip != null && activeTrip.id != currentActiveTrip?.id) {
                    Logging.d(TAG, "Active trip changed, updating: ${activeTrip.id}")
                    handleActiveTripFound(activeTrip)
                }
            }
            result.isError() -> {
                Logging.e(TAG, "Periodic sync failed: ${result.getErrorOrNull()}")
            }
        }
        
        Logging.d(TAG, "=== END PERIODIC FETCH ===")
    }
    
    private fun registerMqttTripCallback() {
        if (!isActive.get()) {
            Logging.w(TAG, "Listener not active, skipping MQTT callback registration")
            return
        }
        
        if (mqttService == null) {
            Logging.w(TAG, "MQTT service is null, cannot register callback")
            return
        }
        
        // Prevent duplicate registration
        if (isMqttCallbackRegistered.get()) {
            Logging.d(TAG, "MQTT trip callback already registered, skipping")
            return
        }
        
        Logging.d(TAG, "Registering MQTT trip callback")
        isMqttCallbackRegistered.set(true)
        
        // Extract the callback logic into a variable
        val callback: (com.gocavgo.validator.dataclass.TripEventMessage) -> Unit = { tripEvent ->
            if (isActive.get()) {
                Logging.d(TAG, "=== MQTT TRIP EVENT RECEIVED ===")
                Logging.d(TAG, "Event: ${tripEvent.event}")
                Logging.d(TAG, "Trip ID: ${tripEvent.data.id}")
                
                // Check if trip is for this vehicle
                val vehicleId = vehicleSecurityManager.getVehicleId()
                if (tripEvent.data.vehicle_id == vehicleId.toInt()) {
                    // Handle cancellation event
                    if (tripEvent.event == "TRIP_CANCELLED") {
                        Logging.d(TAG, "Processing TRIP_CANCELLED event")
                        lifecycleScope?.launch(Dispatchers.Main) {
                            handleTripCancellation(tripEvent.data.id)
                        }
                    } else {
                        // Check if this is the same trip we're already handling
                        val tripId = tripEvent.data.id
                        val isSameTrip = currentActiveTrip?.id == tripId
                        
                        lifecycleScope?.launch(Dispatchers.Main) {
                            try {
                                val trip = TripDataConverter.convertBackendTripToAndroid(tripEvent.data)
                                
                                // Record MQTT update for sync coordination
                                SyncCoordinator.recordMqttUpdate(context)
                                Logging.d(TAG, "Recorded MQTT update for sync coordination")
                                
                                // Handle trip found or changed
                                if (isSameTrip) {
                                    // Same trip - update current trip reference
                                    currentActiveTrip = trip
                                    Logging.d(TAG, "MQTT trip update for same trip: ${trip.id}")
                                    // Still trigger countdown check to ensure it's running
                                    handleTripWithCountdown(trip)
                                } else {
                                    // Different trip or new trip
                                    handleActiveTripFound(trip)
                                }
                            } catch (e: Exception) {
                                Logging.e(TAG, "Error handling MQTT trip: ${e.message}", e)
                                callback?.onError("Error handling MQTT trip: ${e.message}")
                            }
                        }
                    }
                } else {
                    Logging.d(TAG, "Trip is for different vehicle, ignoring")
                }
            }
        }
        
        // Register based on context type
        if (context is Service) {
            // Register with Service callback (for background handling)
            mqttService?.setServiceTripEventCallback(callback)
            Logging.d(TAG, "Registered MQTT callback with Service callback")
        } else {
            // Register with Activity callback (for foreground handling)
            mqttService?.setTripEventCallback(callback)
            Logging.d(TAG, "Registered MQTT callback with Activity callback")
        }
    }
    
    private fun unregisterMqttTripCallback() {
        if (isMqttCallbackRegistered.get()) {
            if (context is Service) {
                mqttService?.setServiceTripEventCallback(null)
                Logging.d(TAG, "Unregistered MQTT callback from Service callback")
            } else {
                mqttService?.setTripEventCallback(null)
                Logging.d(TAG, "Unregistered MQTT callback from Activity callback")
            }
            isMqttCallbackRegistered.set(false)
        }
    }
    
    private fun handleActiveTripFound(trip: TripResponse) {
        val oldTrip = currentActiveTrip
        currentActiveTrip = trip
        
        // Cancel any existing countdown
        countdownJob?.cancel()
        countdownJob = null
        _countdownText.value = ""
        
        // Handle trip based on status
        handleTripWithCountdown(trip)
        
        if (oldTrip == null) {
            // First trip found
            Logging.d(TAG, "Active trip found: ${trip.id}")
            callback?.onActiveTripFound(trip)
        } else if (oldTrip.id != trip.id) {
            // Trip changed
            Logging.d(TAG, "Active trip changed: ${oldTrip.id} → ${trip.id}")
            callback?.onActiveTripChanged(trip, oldTrip)
        } else {
            // Same trip, just update reference
            Logging.d(TAG, "Active trip updated: ${trip.id}")
        }
    }
    
    /**
     * Handle trip countdown logic based on trip status and departure time
     */
    private fun handleTripWithCountdown(trip: TripResponse) {
        Logging.d(TAG, "=== HANDLING TRIP COUNTDOWN ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Status: ${trip.status}")
        Logging.d(TAG, "Departure time: ${trip.departure_time}")
        
        // CHECK FOR IN_PROGRESS STATUS FIRST - Start navigation immediately
        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "=== TRIP IS IN_PROGRESS - STARTING NAVIGATION IMMEDIATELY ===")
            callback?.onNavigationStartRequested(trip, allowResume = true)
            return
        }
        
        // Calculate times for SCHEDULED trips
        val departureTimeMillis = trip.departure_time * 1000
        val currentTime = System.currentTimeMillis()
        
        Logging.d(TAG, "Departure: $departureTimeMillis")
        Logging.d(TAG, "Current time: $currentTime")
        
        if (currentTime >= departureTimeMillis) {
            // LATE: Actual departure time has passed - start navigation directly (no confirmation)
            Logging.d(TAG, "Trip departure time has passed - starting navigation directly")
            callback?.onNavigationStartRequested(trip, allowResume = false)
        } else {
            val timeUntilDeparture = departureTimeMillis - currentTime
            
            if (timeUntilDeparture <= TWO_MINUTES_IN_MS) {
                // Within 2-minute window - start immediately (no countdown)
                Logging.d(TAG, "Within 2-minute window (${timeUntilDeparture}ms until departure), starting navigation immediately")
                callback?.onNavigationStartRequested(trip, allowResume = false)
            } else {
                // More than 2 minutes before departure - notify about scheduled trip and schedule countdown
                Logging.d(TAG, "Trip scheduled but countdown hasn't started yet (${timeUntilDeparture / 60000} minutes until departure)")
                callback?.onTripScheduled(trip, departureTimeMillis)
                
                val delayUntilCountdownStart = timeUntilDeparture - TWO_MINUTES_IN_MS
                Logging.d(TAG, "Scheduling countdown to start in ${delayUntilCountdownStart}ms (${delayUntilCountdownStart / 60000} minutes)")
                startCountdown(delayUntilCountdownStart, trip)
            }
        }
    }
    
    /**
     * Start countdown timer until navigation should begin
     */
    private fun startCountdown(delayMs: Long, trip: TripResponse) {
        countdownJob?.cancel()
        
        countdownJob = lifecycleScope?.launch(Dispatchers.IO) {
            // Wait until countdown should start
            delay(delayMs)
            
            // Start the 2-minute countdown
            var remainingMs = TWO_MINUTES_IN_MS
            
            while (remainingMs > 0 && coroutineContext.isActive) {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
                
                val countdown = "Depart in: %02d:%02d".format(minutes, seconds)
                
                _countdownText.value = countdown
                callback?.onCountdownTextUpdate(countdown)
                
                delay(1000)
                remainingMs -= 1000
            }
            
            // Countdown complete - launch navigation
            if (coroutineContext.isActive && remainingMs <= 0) {
                Logging.d(TAG, "Countdown complete, launching navigation")
                _countdownText.value = ""
                callback?.onCountdownTextUpdate("")
                callback?.onCountdownComplete(trip)
            }
        }
    }
    
    /**
     * Check departure time for a trip and start countdown/navigation if needed
     * This can be called when Activity resumes and finds a SCHEDULED trip
     * to ensure countdown/navigation starts immediately
     */
    fun checkAndStartCountdownForTrip(trip: TripResponse) {
        Logging.d(TAG, "Checking and starting countdown for trip: ${trip.id}")
        
        // If this is the current active trip, just ensure countdown is running
        if (currentActiveTrip?.id == trip.id) {
            Logging.d(TAG, "Trip ${trip.id} is already active - ensuring countdown is running")
            handleTripWithCountdown(trip)
        } else {
            // New trip - handle it normally
            Logging.d(TAG, "Trip ${trip.id} is new - handling as new trip")
            handleActiveTripFound(trip)
        }
    }
    
    private fun handleTripCancellation(cancelledTripId: Int) {
        Logging.d(TAG, "=== HANDLING TRIP CANCELLATION ===")
        Logging.d(TAG, "Cancelled Trip ID: $cancelledTripId")
        Logging.d(TAG, "Current Trip ID: ${currentActiveTrip?.id}")
        
        // Check if the cancelled trip is our current trip
        if (currentActiveTrip?.id == cancelledTripId) {
            Logging.d(TAG, "Current trip was cancelled - clearing state")
            currentActiveTrip = null
        }
        
        // Delete trip from database
        lifecycleScope?.launch(Dispatchers.IO) {
            try {
                databaseManager.deleteTripById(cancelledTripId)
                Logging.d(TAG, "Trip $cancelledTripId deleted from database")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to delete trip from database: ${e.message}", e)
            }
        }
        
        // Notify callback
        callback?.onTripCancelled(cancelledTripId)
    }
}

