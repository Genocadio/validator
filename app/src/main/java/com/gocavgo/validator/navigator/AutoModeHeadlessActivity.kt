package com.gocavgo.validator.navigator

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.RingtoneManager
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.nfc.NFCReaderHelper
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.service.AutoModeHeadlessForegroundService
import com.gocavgo.validator.service.MqttForegroundService
import com.gocavgo.validator.service.MqttHealthCheckWorker
import com.gocavgo.validator.service.NetworkMonitorWorker
import com.gocavgo.validator.service.SettingsTimeoutWorker
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.ui.components.NetworkStatusIndicator
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.core.LocationListener
import com.here.sdk.core.Location
import com.here.sdk.navigation.RouteProgressListener
import com.here.sdk.navigation.NavigableLocationListener
import com.here.sdk.navigation.Milestone
import com.here.sdk.navigation.MilestoneStatus
import com.here.sdk.navigation.MilestoneStatusListener
import com.here.sdk.navigation.DestinationReachedListener
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.Route
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import com.here.sdk.core.GeoCoordinates
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.security.ACTION_SETTINGS_CHANGED
import com.gocavgo.validator.security.ACTION_SETTINGS_LOGOUT
import com.gocavgo.validator.security.ACTION_SETTINGS_DEACTIVATE
import com.gocavgo.validator.dataclass.VehicleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import com.gocavgo.validator.sync.SyncCoordinator
import java.text.SimpleDateFormat
import java.util.Date
import com.gocavgo.validator.ui.components.TripConfirmationData
import com.gocavgo.validator.ui.components.TripConfirmationDialog
import com.gocavgo.validator.receiver.TripConfirmationReceiver
import com.gocavgo.validator.MapDownloaderManager
import android.widget.Toast
import java.util.Locale

class AutoModeHeadlessActivity : ComponentActivity() {
    companion object {
        private const val TAG = "AutoModeHeadlessActivity"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        
        // Track if Activity is currently active (in foreground)
        private var isActivityActive = AtomicBoolean(false)
        
        fun isActive(): Boolean = isActivityActive.get()

        // Synchronization lock for SDK initialization
        private val sdkInitLock = Any()
    }

    private lateinit var databaseManager: DatabaseManager
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var settingsManager: VehicleSettingsManager
    private var tripResponse: TripResponse? = null
    // isSimulated removed - now uses settings.simulate
    private var currentSettings by mutableStateOf<VehicleSettings?>(null)
    private var messageViewText by mutableStateOf("Auto Mode: Waiting for trip...")

    // Auto mode state
    private var currentTrip: TripResponse? = null
    private val isNavigating = AtomicBoolean(false)
    private var countdownJob: Job? = null
    private var countdownText by mutableStateOf("")

    // Trip confirmation state
    private var isAwaitingConfirmation = AtomicBoolean(false)
    private var confirmationTimeoutJob: Job? = null
    private var showConfirmationDialog by mutableStateOf(false)
    private var confirmationTripData by mutableStateOf<TripConfirmationData?>(null)

    // HERE SDK components - using NavigationExample instead of direct Navigator
    private var navigationExample: NavigationExample? = null
    private var messageViewUpdater: MessageViewUpdater? = null
    private var routeCalculator: RouteCalculator? = null
    private var currentRoute: Route? = null
    private var currentUserLocation: Location? = null
    private var isNavigationStarted = false
    private var currentSpeedInMetersPerSecond: Double = 0.0

    // Trip section validator for route validation and waypoint tracking
    private lateinit var tripSectionValidator: TripSectionValidator

    // NFC functionality
    private var nfcReaderHelper: NFCReaderHelper? = null
    private var mqttService: MqttService? = null
    private var bookingBundleReceiver: BroadcastReceiver? = null
    private var confirmationReceiver: BroadcastReceiver? = null
    private var settingsChangeReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // Foreground service
    private var foregroundService: AutoModeHeadlessForegroundService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as AutoModeHeadlessForegroundService.LocalBinder
            foregroundService = serviceBinder.getService()
            foregroundService?.updateNotification(messageViewText)
            Logging.d(TAG, "Bound to AutoModeHeadlessForegroundService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            foregroundService = null
            Logging.d(TAG, "Disconnected from AutoModeHeadlessForegroundService")
        }
    }

    // Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    private var isConnected by mutableStateOf(true)
    private var connectionType by mutableStateOf("UNKNOWN")
    private var isMetered by mutableStateOf(true)
    private var networkOfflineTime = AtomicLong(0)
    private var wasOfflineForExtendedPeriod = AtomicBoolean(false)

    // HERE SDK offline mode state
    private var pendingOfflineMode: Boolean? = null

    // Map downloader
    private var mapDownloaderManager: MapDownloaderManager? = null
    private var mapDownloadProgress by mutableStateOf(0)
    private var mapDownloadTotalSize by mutableStateOf(0)
    private var mapDownloadMessage by mutableStateOf("")
    private var mapDownloadStatus by mutableStateOf("")
    private var showMapDownloadDialog by mutableStateOf(false)
    private var isMapDataReady by mutableStateOf(false)

    // Periodic backend fetch
    private var periodicFetchJob: Job? = null
    private val periodicScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastBackendFetchTime = AtomicLong(0)

    // Trip data refresh for passenger counts
    private val tripRefreshHandler = Handler(Looper.getMainLooper())
    private val tripRefreshRunnable = object : Runnable {
        override fun run() {
            refreshTripDataFromDatabase()
            tripRefreshHandler.postDelayed(this, 10000) // Refresh every 10 seconds
        }
    }

    // UI state management
    private var currentInput by mutableStateOf("")
    private var isValidationInProgress by mutableStateOf(false)
    private var showBookingSuccess by mutableStateOf(false)
    private var showBookingFailure by mutableStateOf(false)
    private var showValidationSuccess by mutableStateOf(false)
    private var showValidationFailure by mutableStateOf(false)
    private var showMqttNotification by mutableStateOf(false)
    private var bookingSuccessData by mutableStateOf(BookingSuccessData("", "", "", ""))
    private var bookingFailureMessage by mutableStateOf("")
    private var validationSuccessTicket by mutableStateOf("")
    private var validationFailureMessage by mutableStateOf("")
    private var mqttNotificationData by mutableStateOf(MqttNotificationData("", "", "", 0, false))

    // Passenger count state
    private var nextWaypointName by mutableStateOf("")
    private var pickupCount by mutableStateOf(0)
    private var dropoffCount by mutableStateOf(0)

    // Passenger list modal state
    private var showPassengerListDialog by mutableStateOf(false)
    private var passengerListType by mutableStateOf(PassengerListType.PICKUP)
    private var passengerList by mutableStateOf<List<com.gocavgo.validator.service.BookingService.PassengerInfo>>(emptyList())
    private var selectedPassengerBookingId by mutableStateOf<String?>(null)

    // Destination selection dialog state
    private var showDestinationSelectionDialog by mutableStateOf(false)
    private var availableDestinations by mutableStateOf<List<AvailableDestination>>(emptyList())
    private var currentLocationForDialog by mutableStateOf("")
    private var nfcPendingId by mutableStateOf<String?>(null)

    enum class PassengerListType {
        PICKUP, DROPOFF
    }

    // Data classes for UI state
    data class BookingSuccessData(
        val ticketNumber: String,
        val fromLocation: String,
        val toLocation: String,
        val price: String
    )

    data class MqttNotificationData(
        val passengerName: String,
        val pickup: String,
        val dropoff: String,
        val numTickets: Int,
        val isPaid: Boolean
    )

    // Backend synchronization methods

    private suspend fun fetchTripsFromBackend() {
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.w(TAG, "Vehicle not registered, skipping backend fetch")
            return
        }

        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "=== FETCHING TRIPS FROM BACKEND ON LAUNCH ===")
        Logging.d(TAG, "Vehicle ID: $vehicleId")

        try {
            // Check database before sync
            val beforeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
            Logging.d(TAG, "Active trip in DB BEFORE sync: ${beforeTrip?.id ?: "none"} (status: ${beforeTrip?.status ?: "N/A"})")

            // Always fetch from backend on launch (user requirement)
            val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)

            when {
                result.isSuccess() -> {
                    val tripCount = result.getDataOrNull() ?: 0
                    Logging.d(TAG, "Backend sync successful: $tripCount trips synced")

                    // Load active trip from database after sync
                    val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                    Logging.d(TAG, "Active trip in DB AFTER sync: ${activeTrip?.id ?: "none"} (status: ${activeTrip?.status ?: "N/A"})")

                    if (activeTrip != null) {
                        Logging.d(TAG, "Active trip found after sync:")
                        Logging.d(TAG, "  - ID: ${activeTrip.id}")
                        Logging.d(TAG, "  - Status: ${activeTrip.status}")
                        Logging.d(TAG, "  - Vehicle ID: ${activeTrip.vehicle_id}")
                        Logging.d(TAG, "  - Route: ${activeTrip.route.origin.google_place_name} → ${activeTrip.route.destination.google_place_name}")
                        // Convert to TripResponse and handle
                        handleTripReceived(activeTrip)
                    } else {
                        Logging.d(TAG, "No active trips after backend sync")
                        // Check if there are ANY trips for this vehicle
                        val totalTrips = databaseManager.getTripCountByVehicle(vehicleId.toInt())
                        Logging.d(TAG, "Total trips in database for vehicle $vehicleId: $totalTrips")

                        withContext(Dispatchers.Main) {
                            foregroundService?.updateNotification("Waiting for trip...")
                        }
                    }
                }
                result.isError() -> {
                    val error = result.getErrorOrNull() ?: "Unknown error"
                    Logging.e(TAG, "Backend sync failed: $error")
                    // Continue with MQTT listening even if sync fails
                    withContext(Dispatchers.Main) {
                        foregroundService?.updateNotification("Waiting for trip...")
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Exception during backend fetch: ${e.message}", e)
            e.printStackTrace()
        }

        Logging.d(TAG, "=== BACKEND FETCH COMPLETE ===")
    }

    private fun startPeriodicBackendFetch() {
        periodicFetchJob?.cancel()

        Logging.d(TAG, "Starting periodic backend fetch every 7 minutes")

        periodicFetchJob = periodicScope.launch {
            while (isActive) {
                delay(7 * 60 * 1000) // 7 minutes

                try {
                    // Skip if navigating
                    if (isNavigating.get()) {
                        Logging.d(TAG, "Navigation active, skipping periodic fetch")
                        continue
                    }

                    // Check internet connectivity
                    if (!NetworkUtils.isConnectedToInternet(this@AutoModeHeadlessActivity)) {
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
        if (!SyncCoordinator.shouldFetchFromBackend(this@AutoModeHeadlessActivity)) {
            Logging.d(TAG, "Skipping - MQTT data is fresh (within 2 minutes)")
            Logging.d(TAG, "Sync status: ${SyncCoordinator.getSyncStatus(this@AutoModeHeadlessActivity)}")
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
                if (activeTrip != null && activeTrip.id != currentTrip?.id) {
                    Logging.d(TAG, "Active trip changed, updating: ${activeTrip.id}")
                    handleTripReceived(activeTrip)
                }
            }
            result.isError() -> {
                Logging.e(TAG, "Periodic sync failed: ${result.getErrorOrNull()}")
            }
        }

        Logging.d(TAG, "=== END PERIODIC FETCH ===")
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
        Logging.d(TAG, "Is navigating: ${isNavigating.get()}")

        // STRATEGY: If navigating, immediately publish MQTT states (trip status + heartbeat)
        // If not navigating, sync via API (trips + settings) even if countdown is present
        if (isNavigating.get() && currentTrip != null) {
            Logging.d(TAG, "Navigating - immediately publishing MQTT states (trip status + heartbeat)")
            lifecycleScope.launch {
                publishMqttStatesOnNetworkRestore()
            }
        } else {
            Logging.d(TAG, "Not navigating - syncing trips and settings via API")
            lifecycleScope.launch {
                performNetworkRestoreApiSync()
            }
        }

        networkOfflineTime.set(0)
    }
    
    /**
     * Publish MQTT states immediately when network is restored during navigation
     * Publishes trip status and heartbeat (omits trip data if not navigating)
     */
    private suspend fun publishMqttStatesOnNetworkRestore() {
        try {
            Logging.d(TAG, "=== PUBLISHING MQTT STATES ON NETWORK RESTORE ===")
            
            val trip = currentTrip
            if (trip == null) {
                Logging.w(TAG, "No current trip - cannot publish trip status")
                return
            }
            
            val mqttService = mqttService
            if (mqttService == null || !mqttService.isConnected()) {
                Logging.w(TAG, "MQTT service not connected - cannot publish states")
                return
            }
            
            // Get current location for MQTT messages
            val currentLocation = currentUserLocation?.coordinates
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
            mqttService.sendTripStatusUpdateBackend(
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
            mqttService.sendHeartbeatResponse(
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
            tripSectionValidator?.let { validator ->
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
    private fun checkSettingsDuringNavigation() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                
                result.onSuccess { settings ->
                    withContext(Dispatchers.Main) {
                        // If logout or deactivate = true, immediately exit (even if navigating)
                        if (settings.logout || settings.deactivate) {
                            Logging.w(TAG, "Logout or deactivate detected during navigation - immediately exiting to LauncherActivity")
                            stopNavigationAndCleanup()
                            exitToLauncherActivity()
                        } else {
                            // Update settings without applying (to prevent loop)
                            currentSettings = settings
                            Logging.d(TAG, "Settings checked during navigation - no logout/deactivate detected")
                        }
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
     * Exit to LauncherActivity (which handles routing based on settings)
     */
    private fun exitToLauncherActivity() {
        try {
            Logging.d(TAG, "Exiting to LauncherActivity")
            val intent = Intent(this, com.gocavgo.validator.LauncherActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Logging.e(TAG, "Error exiting to LauncherActivity: ${e.message}", e)
        }
    }
    
    /**
     * Sync trips and settings via API when network is restored and not navigating
     * This runs even if countdown is present
     */
    private suspend fun performNetworkRestoreApiSync() {
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
                    if (activeTrip != null && activeTrip.id != currentTrip?.id) {
                        Logging.d(TAG, "Active trip changed after network restore: ${activeTrip.id}")
                        withContext(Dispatchers.Main) {
                            handleTripReceived(activeTrip)
                        }
                    } else if (activeTrip != null && activeTrip.id == currentTrip?.id) {
                        // Same trip - verify state matches
                        Logging.d(TAG, "Same trip - verifying state after network restore")
                        withContext(Dispatchers.Main) {
                            verifyStateFromDatabase(activeTrip)
                            syncStateFromService(activeTrip)
                        }
                    }
                }
                tripResult.isError() -> {
                    Logging.e(TAG, "Trip sync failed: ${tripResult.getErrorOrNull()}")
                }
            }
            
            // 2. Sync settings from API
            Logging.d(TAG, "Syncing settings from API...")
            val settingsResult = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
            
            settingsResult.onSuccess { settings ->
                Logging.d(TAG, "Settings sync successful")
                withContext(Dispatchers.Main) {
                    currentSettings = settings
                    
                    // If logout or deactivate = true, immediately exit to LauncherActivity
                    if (settings.logout || settings.deactivate) {
                        Logging.w(TAG, "Logout or deactivate detected - immediately exiting to LauncherActivity")
                        exitToLauncherActivity()
                    } else {
                        // Apply settings (check other settings like devmode)
                        settingsManager.applySettings(this@AutoModeHeadlessActivity, settings)
                        
                        // For other settings changes (not logout/deactivate), check if should route
                        // Only exit if NOT navigating (if navigating, will check after navigation)
                        if (!isNavigating.get() && (settingsManager.areAllSettingsFalse(settings) || !settings.devmode)) {
                            Logging.d(TAG, "Settings require routing (not navigating) - exiting to LauncherActivity")
                            exitToLauncherActivity()
                        }
                    }
                }
            }.onFailure { error ->
                Logging.e(TAG, "Settings sync failed: ${error.message}")
                // Use saved settings if available
                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                savedSettings?.let {
                    withContext(Dispatchers.Main) {
                        currentSettings = it
                        // Check saved settings for logout/deactivate
                        if (it.logout || it.deactivate) {
                            Logging.w(TAG, "Saved settings show logout or deactivate - immediately exiting to LauncherActivity")
                            exitToLauncherActivity()
                        }
                    }
                }
            }
            
            Logging.d(TAG, "=== NETWORK RESTORE API SYNC COMPLETE ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during network restore API sync: ${e.message}", e)
        }
    }

    private suspend fun performImmediateBackendFetch() {
        Logging.d(TAG, "=== IMMEDIATE BACKEND FETCH (NETWORK RECOVERY) ===")

        val vehicleId = vehicleSecurityManager.getVehicleId()
        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)

        when {
            result.isSuccess() -> {
                val tripCount = result.getDataOrNull() ?: 0
                Logging.d(TAG, "Network recovery sync successful: $tripCount trips")

                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                if (activeTrip != null && activeTrip.id != currentTrip?.id) {
                    Logging.d(TAG, "Active trip changed after recovery: ${activeTrip.id}")
                    handleTripReceived(activeTrip)
                }
            }
            result.isError() -> {
                Logging.e(TAG, "Network recovery sync failed: ${result.getErrorOrNull()}")
            }
        }

        wasOfflineForExtendedPeriod.set(false)
        Logging.d(TAG, "=== END NETWORK RECOVERY FETCH ===")
    }

    private suspend fun performSilentBackendFetch(): Boolean {
        Logging.d(TAG, "Silent backend fetch")
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        val result = databaseManager.syncTripsFromRemote(vehicleId.toInt(), page = 1, limit = 10)
        
        return when {
            result.isSuccess() -> {
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                if (activeTrip != null) {
                    Logging.d(TAG, "Active trip found: ${activeTrip.id}")
                    withContext(Dispatchers.Main) {
                        handleTripReceived(activeTrip)
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable showing on lock screen for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // For older versions, use window flags
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        enableEdgeToEdge()

        Logging.d(TAG, "=== AUTO MODE HEADLESS ACTIVITY STARTED ===")

        // Mark activity as active
        isActivityActive.set(true)

        // Initialize database manager
        databaseManager = DatabaseManager.getInstance(this)
        vehicleSecurityManager = VehicleSecurityManager(this)
        settingsManager = VehicleSettingsManager.getInstance(this)

        // Fetch settings on create
        lifecycleScope.launch {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            try {
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                result.onSuccess { settings ->
                    currentSettings = settings
                    // Apply settings (check logout/deactivate)
                    settingsManager.applySettings(this@AutoModeHeadlessActivity, settings)
                }.onFailure { error ->
                    Logging.e(TAG, "Failed to fetch settings: ${error.message}")
                    // Use saved settings if available
                    val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                    savedSettings?.let {
                        currentSettings = it
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception fetching settings: ${e.message}", e)
                // Use saved settings if available
                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                savedSettings?.let {
                    currentSettings = it
                }
            }
        }

        // Handle back press - minimize to background or exit app when all settings are false
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val settings = currentSettings
                if (settings != null && settingsManager.areAllSettingsFalse(settings)) {
                    Logging.d(TAG, "All settings are false - exiting app on back press")
                    finishAffinity() // Exit app
                } else {
                    // Minimize to background instead of finishing
                    Logging.d(TAG, "Back press - minimizing to background")
                    moveTaskToBack(true)
                    // Update notification to show tap to reopen
                    foregroundService?.updateNotification("Auto Mode active in background - Tap to reopen")
                }
            }
        })

        // Restore state from database immediately (synchronous) before showing UI
        try {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            val activeTrip = runBlocking {
                withContext(Dispatchers.IO) {
                    databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                }
            }

            if (activeTrip != null) {
                Logging.d(TAG, "Restoring state for active trip: ${activeTrip.id} (${activeTrip.status})")
                currentTrip = activeTrip
                tripResponse = activeTrip
                if (activeTrip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                    Logging.d(TAG, "Trip is IN_PROGRESS, will resume navigation after initialization")
                    // Clear any confirmation state that might have persisted
                    isAwaitingConfirmation.set(false)
                    showConfirmationDialog = false
                    confirmationTimeoutJob?.cancel()
                    // Will be handled after navigation components are initialized
                    isNavigating.set(true)
                    val origin = activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name
                    messageViewText = "Resuming navigation: $origin"
                } else if (activeTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                    // Check if departure time has passed - if so, start navigation immediately
                    val departureTimeMillis = activeTrip.departure_time * 1000
                    val currentTime = System.currentTimeMillis()
                    if (currentTime >= departureTimeMillis) {
                        Logging.d(TAG, "Trip departure time has passed, will start navigation after initialization (skipping confirmation)")
                        val origin = activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name
                        val destination = activeTrip.route.destination.custom_name ?: activeTrip.route.destination.google_place_name
                        messageViewText = "Resuming navigation: $origin → $destination"
                    } else {
                        val origin = activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name
                        val destination = activeTrip.route.destination.custom_name ?: activeTrip.route.destination.google_place_name
                        messageViewText = "Trip scheduled: $origin → $destination"
                    }
                }
            } else {
                messageViewText = "Auto Mode: Waiting for trip..."
            }
        } catch (e: Exception) {
            Logging.w(TAG, "State restore failed: ${e.message}")
        }

        // Start and bind to foreground service
        startAndBindForegroundService()

        // Initialize UI first
        setContent {
            ValidatorTheme {
                AutoModeHeadlessScreen(
                    messageText = messageViewText,
                    countdownText = countdownText,
                    currentInput = currentInput,
                    isValidationInProgress = isValidationInProgress,
                    nextWaypointName = nextWaypointName,
                    pickupCount = pickupCount,
                    dropoffCount = dropoffCount,
                    onDigitClick = ::addDigit,
                    onDeleteClick = ::deleteLastDigit,
                    onClearClick = ::forceClearInput,
                    showBookingSuccess = showBookingSuccess,
                    showBookingFailure = showBookingFailure,
                    showValidationSuccess = showValidationSuccess,
                    showValidationFailure = showValidationFailure,
                    showMqttNotification = showMqttNotification,
                    bookingSuccessData = bookingSuccessData,
                    bookingFailureMessage = bookingFailureMessage,
                    validationSuccessTicket = validationSuccessTicket,
                    validationFailureMessage = validationFailureMessage,
                    mqttNotificationData = mqttNotificationData,
                    showPassengerListDialog = showPassengerListDialog,
                    passengerListType = passengerListType,
                    passengerList = passengerList,
                    onPickupCountClick = ::showPickupPassengerList,
                    onDropoffCountClick = ::showDropoffPassengerList,
                    onPassengerClick = ::showPassengerDetails,
                    onPassengerListDismiss = { showPassengerListDialog = false },
                    onBookingSuccessDismiss = { showBookingSuccess = false },
                    onBookingFailureDismiss = { showBookingFailure = false },
                    onValidationSuccessDismiss = { showValidationSuccess = false },
                    onValidationFailureDismiss = { showValidationFailure = false },
                    onMqttNotificationDismiss = { showMqttNotification = false },
                    showDestinationSelectionDialog = showDestinationSelectionDialog,
                    availableDestinations = availableDestinations,
                    currentLocationForDialog = currentLocationForDialog,
                    onDestinationSelected = ::onDestinationSelected,
                    onDestinationSelectionDismiss = { showDestinationSelectionDialog = false },
                    showConfirmationDialog = showConfirmationDialog,
                    confirmationTripData = confirmationTripData,
                    onConfirmStart = ::handleConfirmStart,
                    onConfirmCancel = ::handleConfirmCancel,
                    isConnected = isConnected,
                    connectionType = connectionType,
                    isMetered = isMetered,
                    showMapDownloadDialog = showMapDownloadDialog,
                    mapDownloadProgress = mapDownloadProgress,
                    mapDownloadTotalSize = mapDownloadTotalSize,
                    mapDownloadMessage = mapDownloadMessage,
                    mapDownloadStatus = mapDownloadStatus,
                    onMapDownloadCancel = {
                        mapDownloaderManager?.cancelDownloads()
                        showMapDownloadDialog = false
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }


        // Initialize components in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Check if activity is still valid before initializing SDK
                if (isDestroyed) {
                    Logging.w(TAG, "Activity is being destroyed, skipping SDK initialization")
                    return@launch
                }

                // Initialize HERE SDK first
                initializeHERESDK()

                // Verify SDK is initialized before proceeding
                val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
                if (sdkNativeEngine == null) {
                    Logging.e(TAG, "HERE SDK initialization failed or SDK was disposed")
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) {
                            messageViewText = "Error: HERE SDK initialization failed"
                        }
                    }
                    return@launch
                }
                Logging.d(TAG, "HERE SDK verified and ready for navigation components")

                // Initialize map downloader after HERE SDK is ready
                withContext(Dispatchers.Main) {
                    initializeMapDownloader()
                }

                // Get MQTT service instance
                mqttService = MqttService.getInstance()

                // Switch to main thread for remaining initialization
                withContext(Dispatchers.Main) {
                    // Verify SDK is still valid before initializing navigation components
                    val currentSdk = SDKNativeEngine.getSharedInstance()
                    if (currentSdk == null) {
                        Logging.e(TAG, "HERE SDK was disposed before navigation components initialization")
                        messageViewText = "Error: HERE SDK unavailable"
                        return@withContext
                    }

                    // Initialize network monitoring
                    initializeNetworkMonitoring()

                    // Initialize NFC reader
                    initializeNFCReader()

                    // Register MQTT callbacks
                    registerMqttTripCallback()
                    registerBookingBundleReceiver()
                    registerMqttBookingBundleCallback()

                    // Register confirmation receiver
                    registerConfirmationReceiver()

                    // Register settings change receiver
                    registerSettingsChangeReceiver()

                    // Initialize navigation components (requires SDK)
                    initializeNavigationComponents()

                    // After initialization, check if we need to resume navigation for restored trip
                    currentTrip?.let { trip ->
                        Logging.d(TAG, "=== CHECKING RESTORED TRIP AFTER INITIALIZATION ===")
                        Logging.d(TAG, "Trip ID: ${trip.id}, Status: ${trip.status}")

                        // Check if Service is already navigating this trip
                        val serviceIsNavigating = foregroundService?.isNavigatingForSync() ?: false
                        val serviceTrip = foregroundService?.getCurrentTripForSync()
                        val serviceNavigatingSameTrip = serviceIsNavigating && serviceTrip?.id == trip.id

                        // If trip is IN_PROGRESS, attach to existing navigation or resume
                        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                            if (serviceNavigatingSameTrip) {
                                // Service is already navigating this trip - just attach to it, don't restart
                                Logging.d(TAG, "Service is already navigating trip ${trip.id} - attaching to existing navigation")
                                isNavigating.set(true)
                                // Don't reset navigation flags - Service navigation is already active
                                // Just sync state and let RouteProgressListener update UI
                                syncStateFromService(trip)
                                // Update UI to show navigation is active
                                val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                                messageViewText = "Navigating: $origin"
                                foregroundService?.updateNotification("Navigating...")
                            } else if (!isNavigating.get()) {
                                // Navigation was not active, start it
                                Logging.d(TAG, "Resuming navigation for IN_PROGRESS trip after initialization")
                                isNavigationStarted = false
                                currentRoute = null
                                handleTripReceived(trip)
                            } else {
                                // Navigation was already marked active, resume it directly
                                Logging.d(TAG, "Navigation already active, resuming directly")
                                startNavigationInternal(trip, allowResume = true)
                            }
                        }
                        // If trip is SCHEDULED and departure time has passed, start navigation (skip confirmation)
                        else if (trip.status.equals("SCHEDULED", ignoreCase = true)) {
                            val departureTimeMillis = trip.departure_time * 1000
                            val currentTime = System.currentTimeMillis()
                            if (currentTime >= departureTimeMillis) {
                                Logging.d(TAG, "Trip departure time has passed, starting navigation immediately (skipping confirmation)")
                                val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                                val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                                messageViewText = "Resuming navigation: $origin → $destination"
                                foregroundService?.updateNotification("Resuming navigation...")
                                // Reset navigation flags
                                isNavigationStarted = false
                                currentRoute = null
                                startNavigationInternal(trip, allowResume = true)
                            } else {
                                Logging.d(TAG, "Trip departure time not yet passed, will use normal schedule logic")
                                // Handle normally with countdown/confirmation
                                handleTripReceived(trip)
                            }
                        }
                    }

                    // Start periodic trip data refresh
                    tripRefreshHandler.postDelayed(tripRefreshRunnable, 10000)

                    // Start periodic backend fetch
                    startPeriodicBackendFetch()
                }

                // Fetch trips from backend on launch
                fetchTripsFromBackend()

                // Silent fetch if no active trip/navigation/countdown
                if (!isNavigating.get() && 
                    (countdownJob == null || !countdownJob!!.isActive) && 
                    countdownText.isEmpty() && 
                    currentTrip == null) {
                    startPeriodicBackendFetch() // Reset periodic job
                    lifecycleScope.launch(Dispatchers.IO) {
                        performSilentBackendFetch()
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error during initialization: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    messageViewText = "Error: ${e.message}"
                }
            }
        }
    }


    /**
     * Schedule background workers (health checks, network monitoring, settings timeout)
     */
    private fun scheduleBackgroundWorkers() {
        try {
            // Schedule WorkManager health checks
            MqttHealthCheckWorker.schedule(this)

            // Schedule network monitoring worker
            NetworkMonitorWorker.schedule(this)

            // Schedule settings timeout worker (checks for 4-day timeout)
            SettingsTimeoutWorker.schedule(this)

            Logging.d(TAG, "Background workers scheduled")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to schedule background workers: ${e.message}", e)
        }
    }

    /**
     * Request notification permission for Android 13+
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    /**
     * Start MQTT foreground service
     */
    private fun startMqttForegroundService() {
        try {
            val serviceIntent = Intent(this, MqttForegroundService::class.java).apply {
                action = MqttForegroundService.ACTION_START_SERVICE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Logging.d(TAG, "MQTT foreground service started")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to start MQTT foreground service: ${e.message}", e)
        }
    }

    private fun startAndBindForegroundService() {
        val serviceIntent = Intent(this, AutoModeHeadlessForegroundService::class.java)

        // Start service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind to service
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        Logging.d(TAG, "Started and bound to AutoModeHeadlessForegroundService")
    }

    private fun registerMqttTripCallback() {
        mqttService?.setTripEventCallback { tripEvent ->
            Logging.d(TAG, "=== MQTT TRIP EVENT RECEIVED ===")
            Logging.d(TAG, "Event: ${tripEvent.event}")
            Logging.d(TAG, "Trip ID: ${tripEvent.data.id}")

            // Check if trip is for this vehicle
            val vehicleId = vehicleSecurityManager.getVehicleId()
            if (tripEvent.data.vehicle_id != vehicleId.toInt()) {
                Logging.d(TAG, "Trip is for different vehicle, ignoring")
                return@setTripEventCallback
            }

            // Handle cancellation event
            if (tripEvent.event == "TRIP_CANCELLED") {
                Logging.d(TAG, "Processing TRIP_CANCELLED event")
                lifecycleScope.launch(Dispatchers.Main) {
                    handleTripCancellation(tripEvent.data.id)
                }
                return@setTripEventCallback
            }

            // Check if this is the same trip we're already handling
            val tripId = tripEvent.data.id
            val isSameTrip = currentTrip?.id == tripId

            // Ignore new trips if already navigating a different trip (existing logic)
            if (isNavigating.get() && !isSameTrip) {
                Logging.w(TAG, "Navigation active for different trip, ignoring new trip")
                return@setTripEventCallback
            }

            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    val trip = convertBackendTripToAndroid(tripEvent.data)

                    // If trip is IN_PROGRESS and we're awaiting confirmation, clear confirmation immediately
                    if (trip.status.equals("IN_PROGRESS", ignoreCase = true) && isAwaitingConfirmation.get()) {
                        Logging.d(TAG, "MQTT trip is IN_PROGRESS - clearing confirmation state")
                        isAwaitingConfirmation.set(false)
                        showConfirmationDialog = false
                        confirmationTimeoutJob?.cancel()
                        foregroundService?.cancelConfirmationNotification()
                    }

                    handleTripReceived(trip)

                    // Record MQTT update for sync coordination
                    SyncCoordinator.recordMqttUpdate(this@AutoModeHeadlessActivity)
                    Logging.d(TAG, "Recorded MQTT update for sync coordination")
                } catch (e: Exception) {
                    Logging.e(TAG, "Error handling MQTT trip: ${e.message}", e)
                }
            }
        }

        Logging.d(TAG, "MQTT trip event callback registered")
    }

    private fun handleTripReceived(trip: TripResponse) {
        Logging.d(TAG, "=== HANDLING RECEIVED TRIP ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Status: ${trip.status}")
        Logging.d(TAG, "Departure time: ${trip.departure_time}")

        // Cancel any existing countdown
        countdownJob?.cancel()

        // Save as current trip
        currentTrip = trip
        
        // Sync with service (Activity takes precedence when active, but keep service in sync)
        syncActivityStateToService()

        // CHECK FOR IN_PROGRESS STATUS FIRST - Skip all confirmation logic
        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "=== TRIP IS IN_PROGRESS - RESUMING NAVIGATION ===")
            val passedCount = trip.waypoints.count { it.is_passed }
            val totalCount = trip.waypoints.size
            Logging.d(TAG, "Waypoints passed: $passedCount / $totalCount")

            val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
            val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
            messageViewText = "Resuming navigation: $origin → $destination"
            foregroundService?.updateNotification("Resuming navigation...")

            // Reset navigation flags to allow resuming
            isNavigationStarted = false
            currentRoute = null

            // Start navigation immediately, bypassing all countdown/confirmation logic
            startNavigationInternal(trip, allowResume = true)
            return
        }

        // Original logic for SCHEDULED trips
        // Calculate times
        val departureTimeMillis = trip.departure_time * 1000
        val currentTime = System.currentTimeMillis()
        val twoMinutesInMs = 2 * 60 * 1000

        Logging.d(TAG, "Departure: $departureTimeMillis")
        Logging.d(TAG, "Current time: $currentTime")

        if (currentTime >= departureTimeMillis) {
            // LATE: Actual departure time has passed - show confirmation
            // BUT: Double-check status again - if trip became IN_PROGRESS, skip confirmation
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
                messageViewText = "Starting navigation: $origin → $destination"
                foregroundService?.updateNotification("Starting navigation...")
                startNavigationInternal(trip)
            } else {
                // More than 2 minutes before departure - schedule countdown
                val delayUntilCountdownStart = timeUntilDeparture - twoMinutesInMs
                Logging.d(TAG, "Scheduling countdown to start in ${delayUntilCountdownStart}ms (${delayUntilCountdownStart / 60000} minutes)")
                val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                messageViewText = "Trip scheduled: $origin → $destination"
                foregroundService?.updateNotification("Trip scheduled: $origin → $destination")

                startCountdown(delayUntilCountdownStart, trip)
            }
        }
    }

    private fun startCountdown(delayMs: Long, trip: TripResponse) {
        countdownJob = lifecycleScope.launch(Dispatchers.IO) {
            var remainingMs = delayMs

            while (remainingMs > 0 && coroutineContext.isActive) {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60

                val countdown = "Depart in: %02d:%02d".format(minutes, seconds)

                withContext(Dispatchers.Main) {
                    countdownText = countdown
                    foregroundService?.updateNotification("$countdown - ${trip.route.origin.google_place_name}")
                }

                delay(1000)
                remainingMs -= 1000
            }

            // Countdown complete - launch navigation
            if (coroutineContext.isActive && remainingMs <= 0) {
                withContext(Dispatchers.Main) {
                    Logging.d(TAG, "Countdown complete, launching navigation")
                    countdownText = ""
                    messageViewText = "Starting navigation..."
                    foregroundService?.updateNotification("Starting navigation...")
                    startNavigationInternal(trip)
                }
            }
        }
    }

    private fun startNavigationInternal(trip: TripResponse, allowResume: Boolean = false) {
        // Check if navigation is already active - but allow resume if explicitly requested
        if (isNavigating.get() && !allowResume) {
            Logging.w(TAG, "Navigation already active, ignoring start request")
            return
        }

        Logging.d(TAG, "=== STARTING NAVIGATION INTERNALLY ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Allow resume: $allowResume")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")
        Logging.d(TAG, "IsNavigationStarted: $isNavigationStarted")

        // Clear any confirmation state (safety check)
        if (isAwaitingConfirmation.get() || showConfirmationDialog) {
            Logging.d(TAG, "Clearing confirmation state before starting navigation")
            isAwaitingConfirmation.set(false)
            showConfirmationDialog = false
            confirmationTimeoutJob?.cancel()
            foregroundService?.cancelConfirmationNotification()
        }

        // Reset navigation started flag to allow route calculation
        if (allowResume) {
            Logging.d(TAG, "Resuming navigation - resetting navigation flags")
            isNavigationStarted = false
            currentRoute = null
        }

        isNavigating.set(true)
        tripResponse = trip
        currentTrip = trip
        
        // Update trip status to IN_PROGRESS when navigation starts
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentStatus = trip.status
                if (!currentStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                    databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                    Logging.d(TAG, "Trip ${trip.id} status updated from $currentStatus to IN_PROGRESS")
                    
                    // Update local trip status
                    withContext(Dispatchers.Main) {
                        currentTrip = trip.copy(status = "IN_PROGRESS")
                        tripResponse = tripResponse?.copy(status = "IN_PROGRESS")
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update trip status to IN_PROGRESS: ${e.message}", e)
            }
        }
        
        // Sync with service (Activity takes precedence when active, but keep service in sync)
        syncActivityStateToService()

        // Immediately update notification to reflect navigation starting
        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
        messageViewText = "Starting navigation: $origin"
        foregroundService?.updateNotification("Starting navigation...")
        Logging.d(TAG, "Navigation state set, notification updated")

        // Update passenger counts
        updatePassengerCounts()

        // Start navigation using existing HeadlessNavigActivity logic
        Logging.d(TAG, "Calling startNavigation()...")
        startNavigation()

        // Don't overwrite messageViewText here - RouteProgressListener will update it immediately
        // with navigation info (distance, speed, waypoint name) as soon as route progress starts
        foregroundService?.updateNotification("Navigating...")
    }

    private fun showTripConfirmation(trip: TripResponse, delayMinutes: Int) {
        // Safety check: Never show confirmation for IN_PROGRESS trips
        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "Trip is IN_PROGRESS - skipping confirmation and starting navigation directly")
            startNavigationInternal(trip, allowResume = true)
            return
        }

        isAwaitingConfirmation.set(true)

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

        // Show UI dialog
        showConfirmationDialog = true

        // Show notification for background confirmation
        foregroundService?.showConfirmationNotification(trip, delayMinutes)

        // Start 3-minute auto-start timeout
        startConfirmationTimeout(trip)

        messageViewText = "Trip confirmation required - ${delayMinutes}min late"
    }

    private fun formatTime(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    private fun startConfirmationTimeout(trip: TripResponse) {
        confirmationTimeoutJob?.cancel()

        confirmationTimeoutJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(3 * 60 * 1000) // 3 minutes

            if (isAwaitingConfirmation.get()) {
                // Double-check trip status before auto-starting
                val currentTripStatus = currentTrip?.status ?: trip.status
                if (currentTripStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                    Logging.d(TAG, "Trip became IN_PROGRESS during confirmation timeout - starting navigation directly")
                    withContext(Dispatchers.Main) {
                        startNavigationInternal(trip, allowResume = true)
                    }
                } else {
                    Logging.d(TAG, "Confirmation timeout - auto-starting navigation")
                    withContext(Dispatchers.Main) {
                        handleConfirmStart()
                    }
                }
            }
        }
    }

    private fun handleConfirmStart() {
        Logging.d(TAG, "=== HANDLE CONFIRM START ===")
        Logging.d(TAG, "IsAwaitingConfirmation: ${isAwaitingConfirmation.get()}")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")

        // Safety check: If trip is IN_PROGRESS, skip confirmation and start navigation directly
        val trip = confirmationTripData?.trip ?: currentTrip
        if (trip != null && trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
            Logging.d(TAG, "Trip is IN_PROGRESS - skipping confirmation and starting navigation directly")
            isAwaitingConfirmation.set(false)
            showConfirmationDialog = false
            confirmationTimeoutJob?.cancel()
            foregroundService?.cancelConfirmationNotification()
            startNavigationInternal(trip, allowResume = true)
            return
        }

        // If already navigating, just dismiss UI elements
        if (isNavigating.get()) {
            Logging.d(TAG, "Already navigating, just clearing confirmation UI")
            isAwaitingConfirmation.set(false)
            showConfirmationDialog = false
            confirmationTimeoutJob?.cancel()
            foregroundService?.cancelConfirmationNotification()

            // Force UI sync to navigation state
            val origin = currentTrip?.route?.origin?.custom_name
                ?: currentTrip?.route?.origin?.google_place_name
                ?: "destination"
            messageViewText = "Navigating: $origin"
            foregroundService?.updateNotification("Navigating: $origin")
            return
        }

        val confirmTrip = confirmationTripData?.trip
        if (confirmTrip == null) {
            Logging.e(TAG, "No trip data available for confirmation")
            return
        }

        // Clear confirmation state
        isAwaitingConfirmation.set(false)
        showConfirmationDialog = false
        confirmationTimeoutJob?.cancel()
        foregroundService?.cancelConfirmationNotification()

        Logging.d(TAG, "User confirmed trip start, initiating navigation")
        startNavigationInternal(confirmTrip)
    }

    private fun handleConfirmCancel() {
        Logging.d(TAG, "=== HANDLE CONFIRM CANCEL ===")

        isAwaitingConfirmation.set(false)
        showConfirmationDialog = false
        confirmationTimeoutJob?.cancel()
        foregroundService?.cancelConfirmationNotification()

        Logging.d(TAG, "User cancelled late trip")
        currentTrip = null
        confirmationTripData = null

        // Return to waiting state
        messageViewText = "Auto Mode: Waiting for trip..."
        foregroundService?.updateNotification("Auto Mode: Waiting for trip...")
    }

    private fun handleTripCancellation(cancelledTripId: Int) {
        Logging.d(TAG, "=== HANDLING TRIP CANCELLATION ===")
        Logging.d(TAG, "Cancelled Trip ID: $cancelledTripId")
        Logging.d(TAG, "Current Trip ID: ${currentTrip?.id}")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")
        Logging.d(TAG, "IsAwaitingConfirmation: ${isAwaitingConfirmation.get()}")

        // Check if the cancelled trip is our current trip
        if (currentTrip?.id == cancelledTripId) {
            Logging.d(TAG, "Current trip was cancelled - stopping all activities")

            // Stop navigation if active
            if (isNavigating.get()) {
                Logging.d(TAG, "Stopping active navigation")
                stopNavigationAndCleanup()
            }

            // Cancel countdown if active
            countdownJob?.cancel()
            countdownText = ""

            // Cancel confirmation if awaiting
            if (isAwaitingConfirmation.get()) {
                Logging.d(TAG, "Cancelling confirmation prompt")
                isAwaitingConfirmation.set(false)
                showConfirmationDialog = false
                confirmationTimeoutJob?.cancel()
                foregroundService?.cancelConfirmationNotification()
            }

            // Clear current trip
            currentTrip = null
            tripResponse = null
            confirmationTripData = null

            // Return to waiting state
            isNavigating.set(false)
            messageViewText = "Auto Mode: Waiting for trip..."
            foregroundService?.updateNotification("Trip cancelled - Waiting for next trip...")

            Logging.d(TAG, "Returned to waiting state after cancellation")
        } else {
            Logging.d(TAG, "Cancelled trip is not current trip, ignoring navigation state")
        }

        // Delete trip from database regardless
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                databaseManager.deleteTripById(cancelledTripId)
                Logging.d(TAG, "Trip $cancelledTripId deleted from database")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to delete trip from database: ${e.message}", e)
            }
        }
    }
    
    /**
     * Handle trip deletion detected during navigation (from TripSectionValidator)
     * Called when TripSectionValidator detects trip is not found in database
     */
    private suspend fun handleTripDeletedDuringNavigation(deletedTripId: Int) {
        try {
            Logging.d(TAG, "=== ACTIVITY: HANDLING TRIP DELETION DURING NAVIGATION ===")
            Logging.d(TAG, "Deleted Trip ID: $deletedTripId")
            Logging.d(TAG, "Current Trip ID: ${currentTrip?.id}")
            
            // Check if this is the current trip
            if (currentTrip?.id == deletedTripId) {
                Logging.d(TAG, "ACTIVITY: Current trip was deleted during navigation - stopping navigation and transitioning to waiting state")
                
                // Check if navigation is active
                val wasNavigating = isNavigating.get()
                
                // Clear current trip
                currentTrip = null
                tripResponse = null
                confirmationTripData = null
                isNavigating.set(false)
                
                // Stop navigation if active
                if (wasNavigating) {
                    Logging.d(TAG, "ACTIVITY: Stopping navigation due to trip deletion")
                    stopNavigationAndCleanup()
                }
                
                // Transition to waiting state
                withContext(Dispatchers.Main) {
                    messageViewText = "Auto Mode: Waiting for trip..."
                    countdownText = ""
                    foregroundService?.updateNotification("Auto Mode: Waiting for trip...")
                }
                
                Logging.d(TAG, "ACTIVITY: Transitioned to waiting state after trip deletion")
            } else {
                Logging.d(TAG, "ACTIVITY: Deleted trip $deletedTripId is not current trip (current: ${currentTrip?.id}), ignoring")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "ACTIVITY: Error handling trip deletion during navigation: ${e.message}", e)
        }
    }

    private fun stopNavigationAndCleanup() {
        try {
            // Stop headless navigation using NavigationExample
            navigationExample?.stopHeadlessNavigation()

            // Reset trip section validator
            if (::tripSectionValidator.isInitialized) {
                tripSectionValidator.reset()
            }

            Logging.d(TAG, "Navigation stopped and cleaned up")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during navigation cleanup: ${e.message}", e)
        }
    }

    private fun onNavigationComplete() {
        Logging.d(TAG, "=== NAVIGATION COMPLETE ===")

        isNavigating.set(false)
        currentTrip = null
        tripResponse = null

        // Fetch settings on trip completion (scheduled check after navigation)
        lifecycleScope.launch {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            try {
                Logging.d(TAG, "Scheduled settings check after navigation completion")
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                result.onSuccess { settings ->
                    currentSettings = settings
                    
                    // If logout or deactivate = true, immediately exit to LauncherActivity
                    if (settings.logout || settings.deactivate) {
                        Logging.w(TAG, "Logout or deactivate detected after navigation - immediately exiting to LauncherActivity")
                        exitToLauncherActivity()
                    } else {
                        // Apply settings (check other settings like devmode)
                        settingsManager.applySettings(this@AutoModeHeadlessActivity, settings)
                        
                        // For other settings changes (not logout/deactivate), check if should route
                        if (settingsManager.areAllSettingsFalse(settings) || !settings.devmode) {
                            Logging.d(TAG, "Settings require routing after navigation - exiting to LauncherActivity")
                            exitToLauncherActivity()
                        }
                    }
                }.onFailure { error ->
                    Logging.e(TAG, "Failed to fetch settings on trip completion: ${error.message}")
                    // Use saved settings if available
                    val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                    savedSettings?.let {
                        currentSettings = it
                        // Check saved settings for logout/deactivate
                        if (it.logout || it.deactivate) {
                            Logging.w(TAG, "Saved settings show logout or deactivate - immediately exiting to LauncherActivity")
                            exitToLauncherActivity()
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception fetching settings on trip completion: ${e.message}", e)
            }
        }

        // Reset to listening state
        messageViewText = "Auto Mode: Waiting for trip..."
        countdownText = ""
        foregroundService?.updateNotification("Auto Mode: Waiting for trip...")

        // Silent fetch after navigation completes
        startPeriodicBackendFetch() // Reset periodic job
        lifecycleScope.launch(Dispatchers.IO) {
            performSilentBackendFetch()
        }

        Logging.d(TAG, "Returned to listening state")
    }

    private fun initializeNavigationComponents() {
        try {
            Logging.d(TAG, "Initializing navigation components...")

            // Initialize trip section validator
            tripSectionValidator = TripSectionValidator(this)

            // Initialize MQTT service in trip section validator
            mqttService?.let {
                tripSectionValidator.initializeMqttService(it)
                Logging.d(TAG, "MQTT service initialized in trip section validator")
            }
            
            // Set callback for trip deletion - when trip is not found in DB, transition to waiting state
            tripSectionValidator.setTripDeletedCallback { deletedTripId ->
                Logging.w(TAG, "ACTIVITY: Trip $deletedTripId deleted during navigation - transitioning to waiting state")
                lifecycleScope.launch {
                    handleTripDeletedDuringNavigation(deletedTripId)
                }
            }

            // Initialize NavigationExample (replaces Navigator, RoutingEngines, and LocationEngine)
            initializeNavigationExample()

            Logging.d(TAG, "Navigation components initialized successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize navigation components: ${e.message}", e)
            messageViewText = "Error initializing navigation: ${e.message}"
        }
    }

    // [REST OF THE FILE CONTINUES WITH SAME LOGIC AS HeadlessNavigActivity]
    // Including all the same methods for:
    // - initializeHERESDK, initializeNetworkMonitoring, initializeRoutingEngines, initializeNavigator, initializeLocationEngine
    // - startNavigation, calculateRouteAndStartNavigation, createWaypointsFromTrip
    // - startHeadlessGuidance, setupHeadlessListeners, handleRouteDeviation, setupLocationSource
    // - NFC methods, booking methods, ticket validation, passenger management
    // - All UI helper methods and state management

    private fun initializeHERESDK(lowMem: Boolean = false) {
        // Check if activity is still valid before initializing
        if (isDestroyed) {
            Logging.w(TAG, "Activity is being destroyed, cannot initialize HERE SDK")
            return
        }

        // Synchronize to prevent concurrent initialization attempts
        synchronized(sdkInitLock) {
            try {
                // Check if activity is still valid inside synchronized block
                if (isDestroyed) {
                    Logging.w(TAG, "Activity was destroyed during SDK initialization check")
                    return
                }

                // Double-check pattern: Check again inside synchronized block
                if (SDKNativeEngine.getSharedInstance() != null) {
                    Logging.d(TAG, "HERE SDK already initialized, skipping")
                    return
                }

                Logging.d(TAG, "Initializing HERE SDK...")
                val accessKeyID = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_ID
                val accessKeySecret = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_SECRET
                val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
                val options = SDKOptions(authenticationMode)
                if(lowMem) {
                    options.lowMemoryMode = true
                    Logging.d(TAG, "Initialised in Low memory mode")
                }

                // Initialize SDK - this is the critical section that must not run concurrently
                SDKNativeEngine.makeSharedInstance(this, options)
                Logging.d(TAG, "HERE SDK initialized successfully")

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
            } catch (e: InstantiationErrorException) {
                Logging.e(TAG, "Initialization of HERE SDK failed: ${e.error.name}", e)
                throw RuntimeException("Initialization of HERE SDK failed: " + e.error.name)
            } catch (e: Exception) {
                Logging.e(TAG, "Unexpected error during HERE SDK initialization: ${e.message}", e)
                throw RuntimeException("Unexpected error during HERE SDK initialization: ${e.message}")
            }
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

    private fun initializeNetworkMonitoring() {
        Logging.d(TAG, "Initializing network monitoring...")

        // Check if we have network permissions
        if (!NetworkUtils.hasNetworkPermissions(this)) {
            Logging.w(TAG, "Network permissions not available, using basic monitoring")
            return
        }

        networkMonitor = NetworkMonitor(this) { connected, type, metered ->
            Logging.d(TAG, "Network state changed: connected=$connected, type=$type, metered=$metered")

            // Update UI state
            isConnected = connected
            connectionType = type
            isMetered = metered

            // Update HERE SDK offline mode
            updateHERESDKOfflineMode(connected)

            // Update RouteCalculator with network state
            routeCalculator?.setNetworkState(connected)

            // Update NavigationHandler with network state
            navigationExample?.getNavigationHandler()?.setNetworkState(connected)

            // Notify map downloader of network availability
            if (connected) {
                mapDownloaderManager?.onNetworkAvailable()
            }

            // Handle network recovery for backend sync
            if (connected) {
                handleNetworkRestored()
            } else {
                handleNetworkLost()
            }
        }

        networkMonitor?.startMonitoring()

        // Update RouteCalculator with initial network state
        routeCalculator?.setNetworkState(isConnected)

        Logging.d(TAG, "Network monitoring started")
    }

    private fun initializeNavigationExample() {
        try {
            // Verify SDK is initialized before creating NavigationExample
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine == null) {
                Logging.e(TAG, "HERE SDK not initialized, cannot create NavigationExample")
                throw RuntimeException("HERE SDK not initialized. Cannot create NavigationExample.")
            }

            // Create MessageViewUpdater for navigation updates
            messageViewUpdater = MessageViewUpdater()

            // Create RouteCalculator for route calculation
            routeCalculator = RouteCalculator()

            // Create NavigationExample (handles Navigator, RoutingEngines, LocationEngine internally)
            // For headless mode, mapView is null
            navigationExample = NavigationExample(
                context = this,
                mapView = null, // Headless mode - no map view
                messageView = messageViewUpdater!!,
                tripSectionValidator = tripSectionValidator
            )

            // Start location provider for getting current location before route calculation
            navigationExample?.startLocationProvider()

            // Setup headless listeners using NavigationHandler
            navigationExample?.let { navExample ->
                val navigator = navExample.getHeadlessNavigator()
                val dynamicRoutingEngine = navExample.getDynamicRoutingEngine()
                val navigationHandler = navExample.getNavigationHandler()

                // Setup headless listeners (NavigationHandler handles all listener setup)
                if (dynamicRoutingEngine != null) {
                    navigationHandler.setupHeadlessListeners(navigator, dynamicRoutingEngine)
                    Logging.d(TAG, "NavigationExample initialized successfully with headless listeners")
                } else {
                    Logging.e(TAG, "DynamicRoutingEngine is null, cannot setup headless listeners")
                    throw RuntimeException("DynamicRoutingEngine is null, cannot setup headless listeners")
                }
            }
        } catch (e: InstantiationErrorException) {
            Logging.e(TAG, "Initialization of NavigationExample failed: ${e.error.name}", e)
            throw RuntimeException("Initialization of NavigationExample failed: " + e.error.name)
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize NavigationExample: ${e.message}", e)
            throw RuntimeException("Failed to initialize NavigationExample: ${e.message}", e)
        }
    }

    private fun startNavigation() {
        tripResponse?.let { trip ->
            // Get simulate value from settings
            val simulate = currentSettings?.simulate ?: false

            if (!simulate) {
                startLocationEngineAndWaitForLocation()
            } else {
                isNavigationStarted = true
                calculateRouteAndStartNavigation()
            }
        }
    }

    private fun startLocationEngineAndWaitForLocation() {
        navigationExample?.let { navExample ->
            Logging.d(TAG, "Waiting for location from NavigationExample location provider...")

            // Check if we already have a valid location
            if (navExample.hasValidLocation()) {
                currentUserLocation = navExample.getLastKnownLocation()
                if (currentUserLocation != null) {
                    Logging.d(TAG, "Location already available, starting navigation...")
                    isNavigationStarted = true
                    calculateRouteAndStartNavigation()
                    return
                }
            }

            // Wait for location with timeout
            val handler = Handler(Looper.getMainLooper())
            var attempts = 0
            val maxAttempts = 10 // 10 seconds total

            val checkLocation = object : Runnable {
                override fun run() {
                    attempts++
                    if (navExample.hasValidLocation()) {
                        currentUserLocation = navExample.getLastKnownLocation()
                        if (currentUserLocation != null && !isNavigationStarted) {
                            Logging.d(TAG, "Location acquired after $attempts attempts, starting navigation...")
                            isNavigationStarted = true
                            calculateRouteAndStartNavigation()
                        }
                    } else if (attempts < maxAttempts) {
                        handler.postDelayed(this, 1000) // Check every second
                    } else {
                        Logging.w(TAG, "No location received within timeout, falling back to simulated navigation")
                        isNavigationStarted = true
                        calculateRouteAndStartNavigation()
                    }
                }
            }

            handler.postDelayed(checkLocation, 1000) // Start checking after 1 second
        } ?: run {
            Logging.w(TAG, "NavigationExample not available, falling back to simulated navigation")
            isNavigationStarted = true
            calculateRouteAndStartNavigation()
        }
    }

    private fun calculateRouteAndStartNavigation() {
        tripResponse?.let { trip ->
            // Get simulate value from settings
            val simulate = currentSettings?.simulate ?: false

            var origin: Waypoint? = null
            if (currentUserLocation == null && !simulate) {
                Logging.w(TAG, "No current user location available, falling back to simulated navigation")
                // Fallback to simulated mode if no location
            } else {
                currentUserLocation?.let {
                    origin = Waypoint(it.coordinates)
                    origin!!.headingInDegrees = it.bearingInDegrees
                }
            }
            val waypoints = createWaypointsFromTrip(trip, origin)

            Logging.d(TAG, "Calculating route")
            Logging.d(TAG, "Trip ID: ${trip.id}")
            Logging.d(TAG, "Waypoints: ${waypoints.size}")

            // Use RouteCalculator for route calculation (handles network switching automatically)
            routeCalculator?.calculateRouteWithWaypoints(waypoints) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val route = routes[0]
                    currentRoute = route

                    Logging.d(TAG, "Route calculated successfully")
                    startHeadlessGuidance(route)
                } else {
                    Logging.e(TAG, "Route calculation failed: ${routingError?.name}")
                }
            }
        } ?: run {
            Logging.e(TAG, "No trip data available for route calculation")
        }
    }

    private fun createWaypointsFromTrip(trip: TripResponse, origin: Waypoint? = null): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()

        if (origin != null) {
            waypoints.add(origin)
        }

        // Add origin if not using device location
        if (origin == null) {
            // For IN_PROGRESS trips in simulated mode, use saved current location if available
            val useSavedLocation = trip.status.equals("IN_PROGRESS", ignoreCase = true) &&
                                   trip.vehicle.current_latitude != null &&
                                   trip.vehicle.current_longitude != null

            val originWaypoint = if (useSavedLocation) {
                Logging.d(TAG, "Using saved vehicle location for IN_PROGRESS trip (simulated mode)")
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
        val skippedCount = sortedWaypoints.size - unpassedWaypoints.size

        Logging.d(TAG, "=== WAYPOINT FILTERING ===")
        Logging.d(TAG, "Total waypoints: ${sortedWaypoints.size}")
        Logging.d(TAG, "Passed waypoints: $skippedCount")
        Logging.d(TAG, "Unpassed waypoints: ${unpassedWaypoints.size}")

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
            Logging.d(TAG, "Added waypoint: ${tripWaypoint.location.custom_name ?: tripWaypoint.location.google_place_name} (order: ${tripWaypoint.order})")
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

        Logging.d(TAG, "Created ${waypoints.size} waypoints from trip data (skipped $skippedCount passed waypoints)")
        Logging.d(TAG, "=========================")
        return waypoints
    }

    private fun startHeadlessGuidance(route: Route) {
        navigationExample?.let { navExample ->
            // Verify route sections with trip section validator
            tripResponse?.let { trip ->
                // Calculate skipped waypoint count
                val skippedCount = trip.waypoints.count { it.is_passed }

                Logging.d(TAG, "=== ROUTE VERIFICATION WITH SKIPPED WAYPOINTS ===")
                Logging.d(TAG, "Skipped waypoints: $skippedCount")
                Logging.d(TAG, "Total waypoints: ${trip.waypoints.size}")

                // Get simulate value from settings
                val simulate = currentSettings?.simulate ?: false

                val isVerified = tripSectionValidator.verifyRouteSections(
                    tripResponse = trip,
                    route = route,
                    isSimulated = simulate,
                    deviceLocation = currentUserLocation?.coordinates,
                    skippedWaypointCount = skippedCount
                )
                Logging.d(TAG, "Route verification result: $isVerified")
                Logging.d(TAG, "================================================")
            }

            // Get simulate value from settings
            val simulate = currentSettings?.simulate ?: false

            // Start headless navigation using NavigationExample
            // This handles route setting, location source, and listener setup
            navExample.startHeadlessNavigation(route, simulate)

            // Add AutoMode-specific callbacks to existing listeners
            // NavigationHandler already set up listeners, we wrap them to add AutoMode behavior
            val navigator = navExample.getHeadlessNavigator()
            val originalDestinationReachedListener = navigator.destinationReachedListener

            // Wrap navigable location listener to update currentSpeedInMetersPerSecond
            val originalNavigableLocationListener = navigator.navigableLocationListener
            navigator.navigableLocationListener = NavigableLocationListener { currentNavigableLocation ->
                // Guard: Don't process if activity is destroyed
                if (isDestroyed) {
                    return@NavigableLocationListener
                }

                // Call original listener (NavigationHandler's listener) if it exists
                originalNavigableLocationListener?.let { listener ->
                    listener.onNavigableLocationUpdated(currentNavigableLocation)
                }

                // AutoMode-specific: Update current speed for navigation display
                try {
                    val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond ?: 0.0
                    currentSpeedInMetersPerSecond = speed
                } catch (e: Exception) {
                    Logging.e(TAG, "Error updating speed in navigable location listener: ${e.message}", e)
                }
            }

            // Wrap destination reached listener to add AutoMode-specific behavior
            navigator.destinationReachedListener = DestinationReachedListener {
                // Guard: Don't process if activity is destroyed
                if (isDestroyed) {
                    return@DestinationReachedListener
                }

                // Call original listener (NavigationHandler's listener) if it exists
                originalDestinationReachedListener?.let {
                    it.onDestinationReached()
                }

                // AutoMode-specific: Handle navigation complete
                lifecycleScope.launch(Dispatchers.Main) {
                    onNavigationComplete()
                }
            }

            // Wrap route progress listener to add AutoMode-specific behavior
            val originalRouteProgressListener = navigator.routeProgressListener
            navigator.routeProgressListener = RouteProgressListener { routeProgress ->
                // Guard: Don't process if activity is destroyed
                if (isDestroyed) {
                    return@RouteProgressListener
                }

                // Call original listener (NavigationHandler's listener) if it exists
                originalRouteProgressListener?.let { listener ->
                    listener.onRouteProgressUpdated(routeProgress)
                }

                // AutoMode-specific: Extract and display navigation info using waypoint progress
                try {
                    // Process section progress through trip section validator for verified trips
                    val sectionProgressList = routeProgress.sectionProgress
                    val totalSections = sectionProgressList.size
                    tripSectionValidator.processSectionProgress(sectionProgressList, totalSections)

                    // Get next waypoint info
                    var nextWaypointInfo = getNextWaypointInfo()
                    
                    // If waypoint info is null but we have route progress, use first section data
                    if (nextWaypointInfo == null && sectionProgressList.isNotEmpty()) {
                        val firstSection = sectionProgressList.firstOrNull()
                        if (firstSection != null) {
                            val remainingDistance = firstSection.remainingDistanceInMeters?.toDouble() ?: 0.0
                            if (remainingDistance > 0) {
                                val trip = tripResponse
                                if (trip != null) {
                                    // Get waypoint name from trip data
                                    val nextWaypoint = trip.waypoints.firstOrNull { !it.is_passed }
                                    val waypointName = if (nextWaypoint != null) {
                                        nextWaypoint.location.custom_name ?: nextWaypoint.location.google_place_name
                                    } else {
                                        trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                                    }
                                    nextWaypointInfo = NextWaypointInfo(waypointName, remainingDistance)
                                    Logging.d(TAG, "ACTIVITY: Using route progress data directly (waypoint progress not available yet)")
                                }
                            }
                        }
                    }
                    
                    // Get current speed from navigable location listener
                    // currentSpeedInMetersPerSecond is updated by NavigationHandler's navigableLocationListener
                    val speedInKmh = currentSpeedInMetersPerSecond * 3.6
                    
                    // Check if trip is completed
                    val trip = tripResponse
                    val allWaypointsPassed = trip?.waypoints?.all { it.is_passed } ?: false
                    val lastSection = sectionProgressList.lastOrNull()
                    val remainingDistanceToDestination = lastSection?.remainingDistanceInMeters?.toDouble() ?: 0.0
                    
                    if (nextWaypointInfo == null || (allWaypointsPassed && remainingDistanceToDestination <= 10.0)) {
                        // Trip completed - return to waiting state
                        Logging.d(TAG, "Trip completed - remaining distance to destination: ${remainingDistanceToDestination}m")
                        runOnUiThread {
                            onNavigationComplete()
                        }
                        return@RouteProgressListener
                    }

                    // Format navigation text based on waypoint info
                    // Note: Waypoint name is shown separately in nextWaypointName, not in messageText
                    val navigationText = if (nextWaypointInfo.remainingDistanceInMeters == Double.MAX_VALUE) {
                        // Under timer or no distance data - show waypoint name and speed
                        "${nextWaypointInfo.name} | ${String.format("%.1f", speedInKmh)} km/h"
                    } else {
                        // Normal navigation - show only distance and speed (waypoint name is in nextWaypointName)
                        val remainingDistanceKm = nextWaypointInfo.remainingDistanceInMeters / 1000.0
                        // Avoid showing "0.0 km" when very close to waypoint
                        if (remainingDistanceKm < 0.01) {
                            // Very close to waypoint - show just speed
                            "Next: ${String.format("%.1f", speedInKmh)} km/h"
                        } else {
                            "Next: ${String.format("%.1f", remainingDistanceKm)} km | Speed: ${String.format("%.1f", speedInKmh)} km/h"
                        }
                    }

                    // Update both messageViewText and nextWaypointName on UI thread
                    runOnUiThread {
                        messageViewText = navigationText
                        // Update nextWaypointName to show the waypoint name separately
                        // This includes both under-timer case (origin name) and normal navigation (waypoint name)
                        nextWaypointName = nextWaypointInfo.name
                        foregroundService?.updateNotification(navigationText)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error extracting navigation info: ${e.message}", e)
                }

                // AutoMode-specific: Update passenger counts when route progress changes
                updatePassengerCounts()
            }

            // Update current route reference
            currentRoute = route

            // Don't set messageViewText here - RouteProgressListener will update it with navigation info
            // (distance, speed, waypoint name) as soon as route progress starts
            Logging.d(TAG, "Navigation started - RouteProgressListener will update UI with navigation info")
        }
    }

    // setupHeadlessListeners, handleRouteDeviation, and setupLocationSource removed
    // NavigationHandler and NavigationExample now handle all listener setup, route deviation, and location source

    // [CONTINUE WITH ALL NFC, BOOKING, VALIDATION, AND UI METHODS FROM HeadlessNavigActivity]
    // Due to length limits, I'm including the key parts here. The rest follows the same pattern.

    private fun initializeNFCReader() {
        try {
            nfcReaderHelper = NFCReaderHelper(
                context = this,
                onTagRead = { tag -> handleNFCTagRead(tag) },
                onError = { error -> Logging.e(TAG, "NFC Error: $error") }
            )

            if (nfcReaderHelper?.isNfcSupported() == true) {
                if (nfcReaderHelper?.isNfcEnabled() == true) {
                    nfcReaderHelper?.enableNfcReader(this)
                    Logging.d(TAG, "NFC reader enabled and ready")
                } else {
                    Logging.w(TAG, "NFC is disabled. Please enable NFC in settings.")
                }
            } else {
                Logging.w(TAG, "NFC not supported on this device")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize NFC reader: ${e.message}", e)
        }
    }

    private fun handleNFCTagRead(tag: Tag) {
        try {
            val nfcId = tag.id.joinToString("") { "%02x".format(it) }
            Logging.d(TAG, "=== NFC TAG READ ===")
            Logging.d(TAG, "NFC ID: $nfcId")

            if (isDestroyed) {
                Logging.w(TAG, "Activity is destroyed, ignoring NFC tag read")
                return
            }

            if (tripResponse == null) {
                Logging.e(TAG, "Trip response is null! Cannot process booking.")
                return
            }

            // Check for existing booking first
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val existingBookingResult = databaseManager.getExistingBookingByNfcTag(nfcId)

                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) {
                            when (existingBookingResult) {
                                is com.gocavgo.validator.service.ExistingBookingResult.Found -> {
                                    Logging.d(TAG, "Found existing booking, showing ticket display")
                                    showExistingTicketDialog(existingBookingResult.booking, existingBookingResult.payment, existingBookingResult.ticket)
                                }
                                is com.gocavgo.validator.service.ExistingBookingResult.NotFound -> {
                                    Logging.d(TAG, "No existing booking found, showing destination selection")
                                    showDestinationSelectionDialog(nfcId)
                                }
                                is com.gocavgo.validator.service.ExistingBookingResult.Error -> {
                                    Logging.e(TAG, "Error checking existing booking: ${existingBookingResult.message}")
                                    showBookingError("Error checking existing booking: ${existingBookingResult.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error checking existing booking: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) {
                            showBookingError("Error checking existing booking: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling NFC tag: ${e.message}", e)
        }
    }

    private fun showDestinationSelectionDialog(nfcId: String) {
        Logging.d(TAG, "=== SHOWING DESTINATION SELECTION DIALOG ===")
        Logging.d(TAG, "NFC ID: $nfcId")

        val destinations = getAvailableWaypoints()
        val currentLocation = getCurrentLocationName()

        Logging.d(TAG, "Available destinations count: ${destinations.size}")
        destinations.forEach { dest ->
            val displayName = getLocationDisplayName(dest.location)
            Logging.d(TAG, "  - $displayName (final: ${dest.isFinalDestination})")
        }

        if (destinations.isEmpty()) {
            Logging.w(TAG, "No available destinations found!")
            showBookingError("All waypoints have been passed. Trip is complete.")
            return
        }

        // Show the destination selection dialog to user
        nfcPendingId = nfcId
        availableDestinations = destinations
        currentLocationForDialog = currentLocation
        showDestinationSelectionDialog = true
        Logging.d(TAG, "Destination selection dialog displayed with ${destinations.size} options")
    }

    private fun onDestinationSelected(destination: AvailableDestination) {
        Logging.d(TAG, "Destination selected: ${getLocationDisplayName(destination.location)}")

        nfcPendingId?.let { nfcId ->
            showDestinationSelectionDialog = false
            processBooking(nfcId, destination)
            nfcPendingId = null
        } ?: run {
            Logging.e(TAG, "No pending NFC ID found!")
            showBookingError("Invalid booking state")
        }
    }

    private fun processBooking(nfcId: String, destination: AvailableDestination) {
        try {
            val destinationDisplayName = getLocationDisplayName(destination.location)
            val currentLocation = getCurrentLocationName()
            val priceText = calculateDestinationPrice(destination)

            // Create complete booking with payment and ticket in database
            createCompleteBooking(nfcId, destination, currentLocation, destinationDisplayName, priceText)

        } catch (e: Exception) {
            Logging.e(TAG, "Error processing booking: ${e.message}", e)
        }
    }

    private fun createCompleteBooking(
        nfcId: String,
        destination: AvailableDestination,
        currentLocation: String,
        destinationDisplayName: String,
        priceText: String
    ) {
        tripResponse?.let { trip ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fromLocationId = getCurrentLocationId()
                    val toLocationId = destination.locationId

                    val result = databaseManager.createBookingWithPaymentAndTicket(
                        tripId = trip.id,
                        nfcId = nfcId,
                        fromLocationId = fromLocationId,
                        toLocationId = toLocationId,
                        price = destination.price,
                        userPhone = "NFC_USER",
                        userName = "NFC Card User"
                    )

                    withContext(Dispatchers.Main) {
                        when (result) {
                            is com.gocavgo.validator.service.BookingCreationResult.Success -> {
                                // Show booking success prompt
                                bookingSuccessData = BookingSuccessData(
                                    ticketNumber = result.ticketNumber,
                                    fromLocation = currentLocation,
                                    toLocation = destinationDisplayName,
                                    price = priceText
                                )
                                showBookingSuccess = true

                                // Auto-dismiss after 5 seconds
                                handler.postDelayed({
                                    showBookingSuccess = false
                                }, 5000)
                            }
                            is com.gocavgo.validator.service.BookingCreationResult.Error -> {
                                bookingFailureMessage = "Failed to create booking: ${result.message}"
                                showBookingFailure = true

                                // Auto-dismiss after 3 seconds
                                handler.postDelayed({
                                    showBookingFailure = false
                                }, 3000)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error creating complete booking: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        bookingFailureMessage = "Error creating booking: ${e.message}"
                        showBookingFailure = true

                        handler.postDelayed({
                            showBookingFailure = false
                        }, 3000)
                    }
                }
            }
        }
    }

    private fun showExistingTicketDialog(
        booking: com.gocavgo.validator.database.BookingEntity,
        payment: com.gocavgo.validator.database.PaymentEntity,
        ticket: com.gocavgo.validator.database.TicketEntity
    ) {
        // Show validation success for existing ticket
        validationSuccessTicket = "${booking.user_name} - ${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
        showValidationSuccess = true

        // Auto-dismiss after 3 seconds
        handler.postDelayed({
            showValidationSuccess = false
        }, 3000)
    }

    private fun showBookingError(errorMessage: String) {
        bookingFailureMessage = errorMessage
        showBookingFailure = true

        // Auto-dismiss after 3 seconds
        handler.postDelayed({
            showBookingFailure = false
        }, 3000)
    }

    private fun getAvailableWaypoints(): List<AvailableDestination> {
        tripResponse?.let { trip ->
            val allWaypoints = trip.waypoints
            val availableWaypoints = allWaypoints.filter { !it.is_passed }.sortedBy { it.order }

            val availableDestinations = mutableListOf<AvailableDestination>()

            // Add intermediate waypoints
            availableWaypoints.forEach { waypoint ->
                availableDestinations.add(
                    AvailableDestination(
                        id = waypoint.id,
                        locationId = waypoint.location_id,
                        location = waypoint.location,
                        price = waypoint.price,
                        order = waypoint.order,
                        isFinalDestination = false
                    )
                )
            }

            // Always add final destination
            val finalDestinationPrice = calculateFinalDestinationPrice()
            availableDestinations.add(
                AvailableDestination(
                    id = -1,
                    locationId = trip.route.destination.id,
                    location = trip.route.destination,
                    price = finalDestinationPrice,
                    order = Int.MAX_VALUE,
                    isFinalDestination = true
                )
            )

            return availableDestinations
        }
        return emptyList()
    }

    private fun getCurrentLocationName(): String {
        tripResponse?.let { trip ->
            val passedWaypoints = trip.waypoints.filter { it.is_passed }.sortedBy { it.order }

            return when {
                passedWaypoints.isEmpty() -> getLocationDisplayName(trip.route.origin)
                passedWaypoints.size == trip.waypoints.size -> getLocationDisplayName(trip.route.destination)
                else -> getLocationDisplayName(passedWaypoints.last().location)
            }
        }
        return "Unknown"
    }

    private fun getCurrentLocationId(): Int {
        tripResponse?.let { trip ->
            val passedWaypoints = trip.waypoints.filter { it.is_passed }.sortedBy { it.order }

            return when {
                passedWaypoints.isEmpty() -> trip.route.origin.id
                passedWaypoints.size == trip.waypoints.size -> trip.route.destination.id
                else -> passedWaypoints.last().location_id
            }
        }
        return -1
    }

    private fun getLocationDisplayName(location: com.gocavgo.validator.dataclass.SavePlaceResponse): String {
        return location.custom_name?.takeIf { it.isNotBlank() } ?: location.google_place_name
    }

    private fun calculateDestinationPrice(destination: AvailableDestination): String {
        tripResponse?.let { trip ->
            val currentLocation = getCurrentLocationName()
            val origin = getLocationDisplayName(trip.route.origin)
            val allWaypoints = trip.waypoints.sortedBy { it.order }
            val currentWaypointIndex = getCurrentWaypointIndex()

            return when {
                currentLocation == origin -> {
                    "${destination.price.toInt()} RWF"
                }
                currentWaypointIndex >= 0 && !destination.isFinalDestination -> {
                    val currentWaypoint = allWaypoints[currentWaypointIndex]
                    val segmentPrice = destination.price - currentWaypoint.price
                    "${maxOf(0.0, segmentPrice).toInt()} RWF"
                }
                destination.isFinalDestination -> {
                    "${destination.price.toInt()} RWF"
                }
                else -> "${destination.price.toInt()} RWF"
            }
        }

        return "${destination.price.toInt()} RWF"
    }

    private fun calculateFinalDestinationPrice(): Double {
        tripResponse?.let { trip ->
            val currentLocation = getCurrentLocationName()
            val origin = getLocationDisplayName(trip.route.origin)
            val allWaypoints = trip.waypoints.sortedBy { it.order }
            val currentWaypointIndex = getCurrentWaypointIndex()

            return when {
                currentLocation == origin -> {
                    val lastWaypoint = allWaypoints.maxByOrNull { it.order }
                    lastWaypoint?.price ?: 1000.0
                }
                currentWaypointIndex >= 0 -> {
                    val currentWaypoint = allWaypoints[currentWaypointIndex]
                    val lastWaypoint = allWaypoints.maxByOrNull { it.order }
                    val segmentPrice = (lastWaypoint?.price ?: 1000.0) - currentWaypoint.price
                    maxOf(0.0, segmentPrice)
                }
                else -> 1000.0
            }
        }
        return 1000.0
    }

    private fun getCurrentWaypointIndex(): Int {
        tripResponse?.let { trip ->
            val allWaypoints = trip.waypoints.sortedBy { it.order }
            val passedWaypoints = allWaypoints.filter { it.is_passed }
            return if (passedWaypoints.isEmpty()) {
                -1
            } else {
                val lastPassedWaypoint = passedWaypoints.maxByOrNull { it.order }
                allWaypoints.indexOf(lastPassedWaypoint)
            }
        }
        return -1
    }

    private fun addDigit(digit: String) {
        if (isValidationInProgress) {
            return
        }

        if (currentInput.length < 6) {
            currentInput += digit

            if (currentInput.length == 6) {
                validateTicketByNumber(currentInput)
            }
        }
    }

    private fun deleteLastDigit() {
        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
        }
    }

    private fun forceClearInput() {
        currentInput = ""
        isValidationInProgress = false
    }

    private fun validateTicketByNumber(ticketNumber: String) {
        Logging.d(TAG, "=== VALIDATING TICKET ===")
        Logging.d(TAG, "Ticket Number: $ticketNumber")

        // Set validation flag
        isValidationInProgress = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ticket = databaseManager.getTicketByNumber(ticketNumber)

                withContext(Dispatchers.Main) {
                    if (ticket != null) {
                        // Fetch booking data to get user name
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val booking = databaseManager.getBookingById(ticket.booking_id)
                                withContext(Dispatchers.Main) {
                                    if (booking != null) {
                                        validationSuccessTicket = "${booking.user_name} - ${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
                                    } else {
                                        validationSuccessTicket = "${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
                                    }
                                    showValidationSuccess = true

                                    // Auto-dismiss after 3 seconds
                                    handler.postDelayed({
                                        showValidationSuccess = false
                                        currentInput = ""
                                        isValidationInProgress = false
                                    }, 3000)
                                }
                            } catch (e: Exception) {
                                Logging.e(TAG, "Error fetching booking data: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    validationSuccessTicket = "${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
                                    showValidationSuccess = true

                                    handler.postDelayed({
                                        showValidationSuccess = false
                                        currentInput = ""
                                        isValidationInProgress = false
                                    }, 3000)
                                }
                            }
                        }
                    } else {
                        validationFailureMessage = "Invalid ticket number"
                        showValidationFailure = true

                        handler.postDelayed({
                            showValidationFailure = false
                            currentInput = ""
                            isValidationInProgress = false
                        }, 3000)
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error validating ticket: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    validationFailureMessage = "Error validating ticket: ${e.message}"
                    showValidationFailure = true

                    handler.postDelayed({
                        showValidationFailure = false
                        currentInput = ""
                        isValidationInProgress = false
                    }, 3000)
                }
            }
        }
    }

    private fun refreshTripDataFromDatabase() {
        tripResponse?.let { currentTrip ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val freshTrip = databaseManager.getTripById(currentTrip.id)
                    withContext(Dispatchers.Main) {
                        if (freshTrip != null) {
                            // If trip status changed to IN_PROGRESS, clear any confirmation state
                            val wasScheduled = currentTrip.status.equals("SCHEDULED", ignoreCase = true)
                            val nowInProgress = freshTrip.status.equals("IN_PROGRESS", ignoreCase = true)

                            if (wasScheduled && nowInProgress) {
                                Logging.d(TAG, "Trip status changed from SCHEDULED to IN_PROGRESS - clearing confirmation state")
                                isAwaitingConfirmation.set(false)
                                showConfirmationDialog = false
                                confirmationTimeoutJob?.cancel()
                                foregroundService?.cancelConfirmationNotification()

                                // Start navigation if not already navigating
                                if (!isNavigating.get()) {
                                    Logging.d(TAG, "Trip became IN_PROGRESS - starting navigation")
                                    startNavigationInternal(freshTrip, allowResume = true)
                                }
                            }

                            tripResponse = freshTrip
                            updatePassengerCounts()
                        }
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error refreshing trip data: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Extracts clean waypoint name from WaypointProgressInfo.waypointName
     * Removes prefixes like "Origin: ", "Waypoint X: ", "Destination: "
     */
    private fun extractWaypointName(waypointName: String): String {
        return when {
            waypointName.startsWith("Origin: ") -> waypointName.removePrefix("Origin: ")
            waypointName.startsWith("Destination: ") -> waypointName.removePrefix("Destination: ")
            waypointName.contains(": ") -> {
                // Handle "Waypoint X: [name]" format
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

    /**
     * Data class to hold next waypoint information
     */
    private data class NextWaypointInfo(
        val name: String,
        val remainingDistanceInMeters: Double
    )

    /**
     * Gets the next waypoint info from TripSectionValidator
     * Returns null if trip is not in progress, no waypoint data available, or trip is completed
     * Prioritizes waypoint progress data over "under timer" check
     */
    private fun getNextWaypointInfo(): NextWaypointInfo? {
        try {
            val trip = tripResponse ?: return null
            
            // PRIORITY 1: Get waypoint progress from TripSectionValidator (most accurate, real-time data)
            // This should be available as soon as route progress starts
            val waypointProgress = tripSectionValidator.getCurrentWaypointProgress()
            
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
                // Return origin name only (without distance)
                val originName = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                return NextWaypointInfo(originName, Double.MAX_VALUE) // Use MAX_VALUE to indicate no distance yet
            }

            // PRIORITY 3: Check if trip is completed (fallback)
            val allWaypointsPassed = trip.waypoints.all { it.is_passed }
            if (allWaypointsPassed) {
                if (waypointProgress.isNotEmpty()) {
                    val destinationProgress = waypointProgress.find { it.waypointName.startsWith("Destination: ") }
                    if (destinationProgress != null) {
                        val remainingDistance = destinationProgress.remainingDistanceInMeters
                        if (remainingDistance == null || remainingDistance <= 10.0) {
                            Logging.d(TAG, "Trip completed - all waypoints passed and at destination")
                            return null // Trip completed
                        }
                        val cleanName = extractWaypointName(destinationProgress.waypointName)
                        return NextWaypointInfo(cleanName, remainingDistance)
                    }
                }
                Logging.d(TAG, "Trip completed - no destination progress available")
                return null
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
            Logging.d(TAG, "No waypoint progress data available")
            return null
        } catch (e: Exception) {
            Logging.e(TAG, "Error getting next waypoint info: ${e.message}", e)
            return null
        }
    }

    private fun updatePassengerCounts() {
        tripResponse?.let { trip ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (pickups, dropoffs) = if (trip.status.equals("SCHEDULED", ignoreCase = true)) {
                        // Show origin pickups for scheduled trips
                        val originId = trip.route.origin.id
                        databaseManager.getPassengerCountsForLocation(trip.id, originId)
                    } else {
                        // Show next waypoint pickups/dropoffs for in-progress trips
                        val nextWaypoint = trip.waypoints.firstOrNull { it.is_next }
                        if (nextWaypoint != null) {
                            databaseManager.getPassengerCountsForLocation(trip.id, nextWaypoint.location_id)
                        } else {
                            // Check if all waypoints are passed - show destination
                            val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                            if (allWaypointsPassed) {
                                // Show destination dropoff counts
                                databaseManager.getPassengerCountsForLocation(trip.id, trip.route.destination.id)
                            } else {
                                Pair(0, 0)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        pickupCount = pickups
                        dropoffCount = dropoffs

                        // Update waypoint name ONLY when NOT navigating
                        // During active navigation, route progress listener handles nextWaypointName updates
                        // to ensure it matches TripSectionValidator's waypoint progress data
                        if (!isNavigating.get()) {
                            nextWaypointName = if (trip.status.equals("SCHEDULED", ignoreCase = true)) {
                                trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                            } else {
                                val nextWaypoint = trip.waypoints.firstOrNull { it.is_next }
                                    ?: trip.waypoints.filter { !it.is_passed }.minByOrNull { it.order }

                                if (nextWaypoint != null) {
                                    nextWaypoint.location.custom_name ?: nextWaypoint.location.google_place_name
                                } else {
                                    // All waypoints passed - show destination
                                    val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                                    if (allWaypointsPassed) {
                                        trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                                    } else {
                                        "No upcoming waypoint"
                                    }
                                }
                            }
                        }
                        // When navigating, nextWaypointName is updated by route progress listener
                        // which uses TripSectionValidator.getCurrentWaypointProgress() for accurate data
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error updating passenger counts: ${e.message}", e)
                }
            }
        }
    }

    private fun showPickupPassengerList() {
        tripResponse?.let { trip ->
            lifecycleScope.launch(Dispatchers.IO) {
                val locationId = if (trip.status.equals("SCHEDULED", ignoreCase = true)) {
                    trip.route.origin.id
                } else {
                    val nextWaypoint = trip.waypoints.firstOrNull { it.is_next }
                    if (nextWaypoint != null) {
                        nextWaypoint.location_id
                    } else {
                        // All waypoints passed - check destination
                        val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                        if (allWaypointsPassed) {
                            trip.route.destination.id
                        } else {
                            return@launch
                        }
                    }
                }

                val passengers = databaseManager.getPassengersPickingUpAtLocation(trip.id, locationId)
                withContext(Dispatchers.Main) {
                    passengerList = passengers
                    passengerListType = PassengerListType.PICKUP
                    showPassengerListDialog = true
                }
            }
        }
    }

    private fun showDropoffPassengerList() {
        tripResponse?.let { trip ->
            // Only show dropoffs for in-progress trips
            if (trip.status.equals("SCHEDULED", ignoreCase = true)) return

            lifecycleScope.launch(Dispatchers.IO) {
                val nextWaypoint = trip.waypoints.firstOrNull { it.is_next }
                val locationId = if (nextWaypoint != null) {
                    nextWaypoint.location_id
                } else {
                    // All waypoints passed - check destination
                    val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                    if (allWaypointsPassed) {
                        trip.route.destination.id
                    } else {
                        return@launch
                    }
                }

                val passengers = databaseManager.getPassengersDroppingOffAtLocation(trip.id, locationId)
                withContext(Dispatchers.Main) {
                    passengerList = passengers
                    passengerListType = PassengerListType.DROPOFF
                    showPassengerListDialog = true
                }
            }
        }
    }

    private fun showPassengerDetails(bookingId: String) {
        selectedPassengerBookingId = bookingId
        Logging.d(TAG, "Show passenger details for booking: $bookingId")
    }

    private fun registerBookingBundleReceiver() {
        try {
            if (bookingBundleReceiver != null) return
            bookingBundleReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return

                    if (intent.action == MqttService.ACTION_BOOKING_BUNDLE_SAVED) {
                        val tripId = intent.getStringExtra("trip_id") ?: "unknown"
                        val passengerName = intent.getStringExtra("passenger_name") ?: "Passenger"
                        val pickup = intent.getStringExtra("pickup") ?: "Unknown"
                        val dropoff = intent.getStringExtra("dropoff") ?: "Unknown"
                        val numTickets = intent.getIntExtra("num_tickets", 1)
                        val isPaid = intent.getBooleanExtra("is_paid", false)

                        runOnUiThread {
                            try {
                                showMqttBookingNotification(passengerName, pickup, dropoff, numTickets, isPaid)
                                playBundleNotificationSound()
                                refreshTripDataFromDatabase()
                            } catch (e: Exception) {
                                Logging.e(TAG, "Error handling booking bundle broadcast: ${e.message}", e)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(MqttService.ACTION_BOOKING_BUNDLE_SAVED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bookingBundleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(bookingBundleReceiver, filter)
            }
            Logging.d(TAG, "Registered booking bundle receiver")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to register booking bundle receiver: ${e.message}", e)
        }
    }

    private fun registerMqttBookingBundleCallback() {
        try {
            mqttService?.setBookingBundleCallback { tripId, passengerName, pickup, dropoff, numTickets, isPaid ->
                runOnUiThread {
                    try {
                        showMqttBookingNotification(passengerName, pickup, dropoff, numTickets, isPaid)
                        playBundleNotificationSound()
                        refreshTripDataFromDatabase()
                    } catch (e: Exception) {
                        Logging.e(TAG, "Error handling MQTT booking bundle callback: ${e.message}", e)
                    }
                }
            }
            Logging.d(TAG, "MQTT booking bundle callback registered")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to register MQTT booking bundle callback: ${e.message}", e)
        }
    }

    private fun registerConfirmationReceiver() {
        try {
            confirmationReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Logging.d(TAG, "=== CONFIRMATION RECEIVER TRIGGERED ===")
                    Logging.d(TAG, "Received action: ${intent?.action}")
                    Logging.d(TAG, "Intent extras: ${intent?.extras}")

                    when (intent?.action) {
                        TripConfirmationReceiver.ACTION_START_CONFIRMED -> {
                            Logging.d(TAG, "Processing START_CONFIRMED action")
                            runOnUiThread {
                                try {
                                    handleConfirmStart()
                                    Logging.d(TAG, "handleConfirmStart() executed successfully")
                                } catch (e: Exception) {
                                    Logging.e(TAG, "Error in handleConfirmStart: ${e.message}", e)
                                }
                            }
                        }
                        TripConfirmationReceiver.ACTION_CANCEL_CONFIRMED -> {
                            Logging.d(TAG, "Processing CANCEL_CONFIRMED action")
                            runOnUiThread {
                                try {
                                    handleConfirmCancel()
                                    Logging.d(TAG, "handleConfirmCancel() executed successfully")
                                } catch (e: Exception) {
                                    Logging.e(TAG, "Error in handleConfirmCancel: ${e.message}", e)
                                }
                            }
                        }
                        else -> {
                            Logging.w(TAG, "Unknown action received: ${intent?.action}")
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
            Logging.d(TAG, "Confirmation receiver registered with actions: ${filter.actionsIterator().asSequence().toList()}")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to register confirmation receiver: ${e.message}", e)
        }
    }

    private fun registerSettingsChangeReceiver() {
        try {
            settingsChangeReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (context == null || intent == null) return

                    when (intent.action) {
                        ACTION_SETTINGS_LOGOUT -> {
                            Logging.d(TAG, "Received logout broadcast - immediately exiting to LauncherActivity")
                            // Immediately exit to LauncherActivity (even if navigating)
                            stopNavigationAndCleanup()
                            exitToLauncherActivity()
                        }
                        ACTION_SETTINGS_DEACTIVATE -> {
                            val isDeactivated = intent.getBooleanExtra("is_deactivated", false)
                            Logging.d(TAG, "Received deactivate broadcast - isDeactivated: $isDeactivated")
                            if (isDeactivated) {
                                // Immediately exit to LauncherActivity (even if navigating)
                                Logging.d(TAG, "Deactivate=true - immediately exiting to LauncherActivity")
                                stopNavigationAndCleanup()
                                exitToLauncherActivity()
                            }
                        }
                        ACTION_SETTINGS_CHANGED -> {
                            Logging.d(TAG, "Received settings changed broadcast - refreshing settings")
                            // Refresh settings from database without re-applying (to prevent loop)
                            lifecycleScope.launch {
                                val vehicleId = vehicleSecurityManager.getVehicleId()
                                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                                savedSettings?.let {
                                    // Only update if different from current
                                    if (currentSettings == null ||
                                        currentSettings?.logout != it.logout ||
                                        currentSettings?.devmode != it.devmode ||
                                        currentSettings?.deactivate != it.deactivate ||
                                        currentSettings?.appmode != it.appmode ||
                                        currentSettings?.simulate != it.simulate) {
                                        currentSettings = it
                                        Logging.d(TAG, "Settings updated from broadcast (no re-apply to prevent loop)")

                                        // PRIORITY 1: If logout OR deactivate = true, immediately exit (even if navigating)
                                        if (it.logout || it.deactivate) {
                                            Logging.d(TAG, "Logout or deactivate detected from broadcast - immediately exiting to LauncherActivity")
                                            stopNavigationAndCleanup()
                                            exitToLauncherActivity()
                                        }
                                        // PRIORITY 2: For other settings changes, only go back if NOT navigating
                                        else if (!isNavigating.get()) {
                                            Logging.d(TAG, "Settings changed and not navigating - checking if should exit to LauncherActivity")
                                            // Check if settings require routing to LauncherActivity
                                            if (settingsManager.areAllSettingsFalse(it) || !it.devmode) {
                                                Logging.d(TAG, "Settings require routing - exiting to LauncherActivity")
                                                exitToLauncherActivity()
                                            }
                                        } else {
                                            Logging.d(TAG, "Settings changed but navigating - will check after navigation completes")
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
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to register settings change receiver: ${e.message}", e)
        }
    }

    private fun showMqttBookingNotification(
        passengerName: String,
        pickup: String,
        dropoff: String,
        numTickets: Int,
        isPaid: Boolean
    ) {
        try {
            mqttNotificationData = MqttNotificationData(
                passengerName = passengerName,
                pickup = pickup,
                dropoff = dropoff,
                numTickets = numTickets,
                isPaid = isPaid
            )
            showMqttNotification = true

            // Auto-dismiss after 5 seconds
            handler.postDelayed({
                showMqttNotification = false
            }, 5000)

        } catch (e: Exception) {
            Logging.e(TAG, "Error showing MQTT booking notification: ${e.message}", e)
        }
    }

    private fun playBundleNotificationSound() {
        try {
            // Try notification sound first
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, notificationUri)

            if (ringtone != null) {
                ringtone.play()

                // Auto-stop after 2 seconds to prevent long sounds
                handler.postDelayed({
                    try {
                        if (ringtone.isPlaying) {
                            ringtone.stop()
                        }
                    } catch (e: Exception) {
                        Logging.w(TAG, "Error stopping ringtone: ${e.message}")
                    }
                }, 2000)
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error playing notification sound: ${e.message}")
        }
    }

    private fun convertBackendTripToAndroid(backendTrip: com.gocavgo.validator.dataclass.TripData): TripResponse {
        // [Same implementation as MainActivity/MqttService]
        return com.gocavgo.validator.util.TripDataConverter.convertBackendTripToAndroid(backendTrip)
    }

    /**
     * Sync Activity state from service state (when Activity resumes from background)
     * Service state is authoritative when Activity was paused
     */
    /**
     * Sync Activity state to service (when Activity is active, keep service in sync)
     * Activity state is authoritative when Activity is active
     */
    private fun syncActivityStateToService() {
        // When Activity is active, Activity state is authoritative
        // Service should sync from Activity, but we don't need to do anything here
        // Service will defer to Activity when Activity is active
        // This method is a placeholder for future bidirectional sync if needed
        Logging.d(TAG, "Activity state sync to service - Activity is authoritative when active")
    }
    
    /**
     * Verify state from database (authoritative source) and fix any mismatches
     * This runs FIRST in onResume() before any Service sync
     */
    private fun verifyStateFromDatabase(dbTrip: TripResponse?) {
        try {
            Logging.d(TAG, "=== VERIFYING STATE FROM DATABASE ===")
            Logging.d(TAG, "Database trip: ${dbTrip?.id} (status: ${dbTrip?.status})")
            Logging.d(TAG, "Activity trip: ${currentTrip?.id} (status: ${currentTrip?.status})")
            Logging.d(TAG, "Activity navigating: ${isNavigating.get()}")
            
            // CASE 1: Trip cancellation detection
            // If Activity has a trip but database doesn't, trip was cancelled
            val currentTripSnapshot = currentTrip
            if (currentTripSnapshot != null && dbTrip == null) {
                Logging.w(TAG, "TRIP CANCELLATION DETECTED: Activity has trip ${currentTripSnapshot.id} but database has none - clearing state")
                
                // Clear all state immediately
                currentTrip = null
                tripResponse = null
                confirmationTripData = null
                isNavigating.set(false)
                isAwaitingConfirmation.set(false)
                showConfirmationDialog = false
                countdownText = ""
                countdownJob?.cancel()
                confirmationTimeoutJob?.cancel()
                foregroundService?.cancelConfirmationNotification()
                
                // Update UI to waiting state
                messageViewText = "Auto Mode: Waiting for trip..."
                foregroundService?.updateNotification("Auto Mode: Waiting for trip...")
                
                Logging.d(TAG, "State cleared after trip cancellation detection")
                return
            }
            
            // CASE 2: Trip exists in database but Activity doesn't have it
            // This means a new trip was assigned while Activity was paused
            if (dbTrip != null && currentTrip?.id != dbTrip.id) {
                Logging.d(TAG, "NEW TRIP DETECTED: Database has trip ${dbTrip.id} but Activity has ${currentTrip?.id} - updating")
                currentTrip = dbTrip
                tripResponse = dbTrip
                
                // Handle the new trip based on its status
                if (dbTrip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                    Logging.d(TAG, "New trip is IN_PROGRESS - will start navigation")
                    isNavigating.set(true)
                    isAwaitingConfirmation.set(false)
                    showConfirmationDialog = false
                    countdownText = ""
                    countdownJob?.cancel()
                    confirmationTimeoutJob?.cancel()
                    foregroundService?.cancelConfirmationNotification()
                } else if (dbTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                    Logging.d(TAG, "New trip is SCHEDULED - will handle normally")
                    // Will be handled by syncStateFromService or normal trip handling
                }
            }
            
            // CASE 3: Trip status changed (SCHEDULED → IN_PROGRESS)
            if (dbTrip != null && currentTrip?.id == dbTrip.id) {
                val wasScheduled = currentTrip?.status?.equals("SCHEDULED", ignoreCase = true) == true
                val nowInProgress = dbTrip.status.equals("IN_PROGRESS", ignoreCase = true)
                
                if (wasScheduled && nowInProgress) {
                    Logging.d(TAG, "TRIP STATUS CHANGED: SCHEDULED → IN_PROGRESS - starting navigation")
                    
                    // Update trip status
                    currentTrip = dbTrip
                    tripResponse = dbTrip
                    
                    // Clear confirmation state if active
                    if (isAwaitingConfirmation.get() || showConfirmationDialog) {
                        Logging.d(TAG, "Clearing confirmation state due to status change")
                        isAwaitingConfirmation.set(false)
                        showConfirmationDialog = false
                        confirmationTimeoutJob?.cancel()
                        foregroundService?.cancelConfirmationNotification()
                    }
                    
                    // Clear countdown if active
                    countdownText = ""
                    countdownJob?.cancel()
                    
                    // Start navigation if not already navigating
                    if (!isNavigating.get()) {
                        Logging.d(TAG, "Starting navigation due to status change")
                        isNavigating.set(true)
                    }
                }
            }
            
            // CASE 4: Database shows IN_PROGRESS but Activity isn't navigating
            // This means navigation should be active but Activity state is stale
            if (dbTrip != null && 
                dbTrip.status.equals("IN_PROGRESS", ignoreCase = true) && 
                !isNavigating.get()) {
                Logging.w(TAG, "NAVIGATION STATE MISMATCH: Database shows IN_PROGRESS but Activity not navigating - fixing")
                
                // Update trip
                currentTrip = dbTrip
                tripResponse = dbTrip
                
                // Clear any conflicting state
                isAwaitingConfirmation.set(false)
                showConfirmationDialog = false
                countdownText = ""
                countdownJob?.cancel()
                confirmationTimeoutJob?.cancel()
                foregroundService?.cancelConfirmationNotification()
                
                // Set navigating flag
                isNavigating.set(true)
                
                // Navigation will be resumed by syncStateFromService or navigation recovery
            }
            
            // CASE 5: Database shows no trip but Activity has trip
            // Already handled in CASE 1, but ensure state is cleared
            if (dbTrip == null && currentTripSnapshot != null) {
                Logging.w(TAG, "TRIP REMOVED: Database has no trip but Activity has ${currentTripSnapshot.id} - clearing state")
                currentTrip = null
                tripResponse = null
                confirmationTripData = null
                isNavigating.set(false)
                isAwaitingConfirmation.set(false)
                showConfirmationDialog = false
                countdownText = ""
                countdownJob?.cancel()
                confirmationTimeoutJob?.cancel()
                foregroundService?.cancelConfirmationNotification()
                messageViewText = "Auto Mode: Waiting for trip..."
                foregroundService?.updateNotification("Auto Mode: Waiting for trip...")
            }
            
            Logging.d(TAG, "=== END DATABASE VERIFICATION ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error verifying state from database: ${e.message}", e)
        }
    }
    
    /**
     * Sync Activity state from service state (when Activity resumes from background)
     * Database state is authoritative, Service state is secondary source
     * @param dbTrip The trip from database (authoritative source)
     */
    private fun syncStateFromService(dbTrip: TripResponse?) {
        try {
            // Use existing service connection (serviceConnection is already bound)
            val service = foregroundService
            if (service == null) {
                Logging.d(TAG, "Service not available for state sync")
                return
            }
            
            Logging.d(TAG, "=== SYNCING STATE FROM SERVICE ===")
            
            // Get service state via public sync methods
            val serviceTrip = service.getCurrentTripForSync()
            val serviceIsNavigating = service.isNavigatingForSync()
            val serviceCountdownText = service.getCountdownTextForSync()
            val serviceIsAwaitingConfirmation = service.isAwaitingConfirmationForSync()
            
            Logging.d(TAG, "Database trip: ${dbTrip?.id} (status: ${dbTrip?.status})")
            Logging.d(TAG, "Service trip: ${serviceTrip?.id} (status: ${serviceTrip?.status})")
            Logging.d(TAG, "Activity trip: ${currentTrip?.id} (status: ${currentTrip?.status})")
            Logging.d(TAG, "Service navigating: $serviceIsNavigating, Activity navigating: ${isNavigating.get()}")
            Logging.d(TAG, "Service countdown: $serviceCountdownText, Activity countdown: $countdownText")
            Logging.d(TAG, "Service awaiting confirmation: $serviceIsAwaitingConfirmation, Activity: ${isAwaitingConfirmation.get()}")
            
            // Use database as authoritative source - if database and service disagree, trust database
            val authoritativeTrip = dbTrip ?: serviceTrip
            
            // If database has trip but service doesn't, database is correct (service might be stale)
            if (dbTrip != null && serviceTrip == null) {
                Logging.w(TAG, "Database has trip but Service doesn't - using database trip (Service state is stale)")
            }
            
            // If service has trip but database doesn't, database is correct (trip was cancelled)
            if (dbTrip == null && serviceTrip != null) {
                Logging.w(TAG, "Service has trip but Database doesn't - trip was cancelled, ignoring Service state")
                // State already cleared by verifyStateFromDatabase, just return
                return
            }
            
            // If database and service have different trips, trust database
            if (dbTrip != null && serviceTrip != null && dbTrip.id != serviceTrip.id) {
                Logging.w(TAG, "Database and Service have different trips - using database trip (Service state is stale)")
            }
            
            // Now sync from service state, but only if it matches database (or database has no trip)
            if (authoritativeTrip == null) {
                Logging.d(TAG, "No authoritative trip - no sync needed")
                updatePassengerCounts()
                return
            }
            
            // Ensure Activity has the authoritative trip
            if (currentTrip?.id != authoritativeTrip.id) {
                Logging.d(TAG, "Updating Activity trip to match authoritative source")
                currentTrip = authoritativeTrip
                tripResponse = authoritativeTrip
            }
            
            // Sync navigation state - if database shows IN_PROGRESS, navigation should be active
            val shouldBeNavigating = authoritativeTrip.status.equals("IN_PROGRESS", ignoreCase = true)
            
            if (shouldBeNavigating && !isNavigating.get()) {
                // Check if Service is already navigating this trip
                val serviceNavigatingSameTrip = serviceIsNavigating && serviceTrip?.id == authoritativeTrip.id
                
                if (serviceNavigatingSameTrip) {
                    // Service is already navigating - just attach to it, don't restart
                    Logging.d(TAG, "Service is already navigating trip ${authoritativeTrip.id} - attaching to existing navigation")
                    isNavigating.set(true)
                    
                    // Clear conflicting state
                    isAwaitingConfirmation.set(false)
                    showConfirmationDialog = false
                    countdownText = ""
                    countdownJob?.cancel()
                    confirmationTimeoutJob?.cancel()
                    foregroundService?.cancelConfirmationNotification()
                    
                    // Don't restart navigation - Service is already handling it
                    // Just ensure UI reflects navigation state
                    // RouteProgressListener will update UI when it receives updates
                    Logging.d(TAG, "Attached to existing Service navigation - RouteProgressListener will update UI")
                    foregroundService?.updateNotification("Navigating...")
                } else {
                    // Service is not navigating - start navigation
                    Logging.d(TAG, "Navigation should be active (database shows IN_PROGRESS) - starting navigation recovery")
                    isNavigating.set(true)
                    
                    // Clear conflicting state
                    isAwaitingConfirmation.set(false)
                    showConfirmationDialog = false
                    countdownText = ""
                    countdownJob?.cancel()
                    confirmationTimeoutJob?.cancel()
                    foregroundService?.cancelConfirmationNotification()
                    
                    // Re-initialize navigation if needed
                    if (!isDestroyed && navigationExample?.getHeadlessNavigator()?.route == null) {
                        Logging.d(TAG, "Restarting navigation from database state")
                        startNavigationInternal(authoritativeTrip, allowResume = true)
                    } else {
                        Logging.d(TAG, "Navigation already initialized, just updating state")
                        foregroundService?.updateNotification("Navigating...")
                    }
                }
            } else if (!shouldBeNavigating && isNavigating.get()) {
                Logging.w(TAG, "Navigation is active but trip is not IN_PROGRESS - stopping navigation")
                isNavigating.set(false)
                stopNavigationAndCleanup()
            }
            
            // Sync countdown state - only if trip is SCHEDULED and not IN_PROGRESS
            if (!shouldBeNavigating && serviceCountdownText.isNotEmpty() && authoritativeTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                Logging.d(TAG, "Service has active countdown: $serviceCountdownText, restarting Activity countdown job")
                
                // Cancel any existing countdown job
                countdownJob?.cancel()
                
                // Calculate remaining time based on trip departure time
                val departureTimeMillis = authoritativeTrip.departure_time * 1000
                val currentTime = System.currentTimeMillis()
                val twoMinutesInMs = 2 * 60 * 1000
                
                if (currentTime < departureTimeMillis) {
                    val timeUntilDeparture = departureTimeMillis - currentTime
                    
                    if (timeUntilDeparture > twoMinutesInMs) {
                        // More than 2 minutes before departure - restart countdown
                        val delayUntilCountdownStart = timeUntilDeparture - twoMinutesInMs
                        Logging.d(TAG, "Restarting countdown with ${delayUntilCountdownStart}ms remaining (${delayUntilCountdownStart / 60000} minutes)")
                        
                        // Set initial countdown text to match service
                        countdownText = serviceCountdownText
                        
                        // Update message text to show trip route
                        val origin = authoritativeTrip.route.origin.custom_name ?: authoritativeTrip.route.origin.google_place_name
                        val destination = authoritativeTrip.route.destination.custom_name ?: authoritativeTrip.route.destination.google_place_name
                        messageViewText = "Trip scheduled: $origin → $destination"
                        foregroundService?.updateNotification("Trip scheduled: $origin → $destination")
                        
                        // Start countdown job to continue updating
                        startCountdown(delayUntilCountdownStart, authoritativeTrip)
                    } else {
                        // Within 2-minute window - navigation should start soon, no countdown needed
                        Logging.d(TAG, "Within 2-minute window, countdown should complete soon - clearing countdown")
                        countdownText = ""
                    }
                } else {
                    // Departure time has passed - no countdown needed (should be in confirmation or navigation)
                    Logging.d(TAG, "Departure time has passed, no countdown needed")
                    countdownText = ""
                }
            } else if (serviceCountdownText.isEmpty() && countdownText.isNotEmpty()) {
                // Service has no countdown - clear Activity countdown
                Logging.d(TAG, "Service has no countdown, clearing Activity countdown")
                countdownJob?.cancel()
                countdownText = ""
            }
            
            // Sync confirmation state - only if trip is SCHEDULED (not IN_PROGRESS)
            if (!shouldBeNavigating && serviceIsAwaitingConfirmation && authoritativeTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                Logging.d(TAG, "Syncing confirmation state from service")
                isAwaitingConfirmation.set(true)
                val expectedTime = service.getConfirmationExpectedTimeForSync() ?: formatTime(authoritativeTrip.departure_time * 1000)
                val currentTime = service.getConfirmationCurrentTimeForSync() ?: formatTime(System.currentTimeMillis())
                val delayMinutes = service.getConfirmationDelayMinutesForSync() ?: 0
                confirmationTripData = com.gocavgo.validator.ui.components.TripConfirmationData(
                    trip = authoritativeTrip,
                    expectedDepartureTime = expectedTime,
                    currentTime = currentTime,
                    delayMinutes = delayMinutes
                )
                showConfirmationDialog = true
            } else if (shouldBeNavigating && (isAwaitingConfirmation.get() || showConfirmationDialog)) {
                // Trip is IN_PROGRESS but awaiting confirmation - clear confirmation state
                Logging.d(TAG, "Trip is IN_PROGRESS but awaiting confirmation - clearing confirmation state")
                isAwaitingConfirmation.set(false)
                showConfirmationDialog = false
                confirmationTimeoutJob?.cancel()
                foregroundService?.cancelConfirmationNotification()
            }
            
            // Update passenger counts
            updatePassengerCounts()
            
            Logging.d(TAG, "=== END STATE SYNC ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error syncing state from service: ${e.message}", e)
        }
    }
    
    private fun updateHERESDKOfflineMode(isConnected: Boolean) {
        try {
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                sdkNativeEngine.setOfflineMode(!isConnected)
                Logging.d(TAG, "HERE SDK offline mode set to: ${!isConnected}")
            } else {
                pendingOfflineMode = !isConnected
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to update HERE SDK offline mode: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Mark Activity as active
        isActivityActive.set(true)

        Logging.d(TAG, "=== ON RESUME ===")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")
        Logging.d(TAG, "CurrentTrip: ${currentTrip?.id}")
        Logging.d(TAG, "IsAwaitingConfirmation: ${isAwaitingConfirmation.get()}")

        // PRIORITY 1: Database-first state verification (authoritative source)
        // This must run FIRST before any UI updates or Service sync
        lifecycleScope.launch(Dispatchers.IO) {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            val dbTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
            
            withContext(Dispatchers.Main) {
                // Verify state from database and fix any mismatches immediately
                verifyStateFromDatabase(dbTrip)
                
                // PRIORITY 2: Sync from Service state (secondary source)
                // Only after database verification is complete
                syncStateFromService(dbTrip)
            }
        }

        // Silent fetch if no active trip/navigation/countdown
        if (!isNavigating.get() && 
            (countdownJob == null || !countdownJob!!.isActive) && 
            countdownText.isEmpty() && 
            currentTrip == null) {
            startPeriodicBackendFetch() // Reset periodic job
            lifecycleScope.launch(Dispatchers.IO) {
                performSilentBackendFetch()
            }
        }

        // Notify MQTT service that app is in foreground
        mqttService?.onAppForeground()

        if (!isDestroyed) {
            // Re-enable NFC reader
            nfcReaderHelper?.let { helper ->
                if (helper.isNfcSupported() && helper.isNfcEnabled()) {
                    helper.enableNfcReader(this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Logging.d(TAG, "=== ON NEW INTENT ===")
        Logging.d(TAG, "Activity received new intent (SINGLE_TOP behavior)")
        Logging.d(TAG, "Intent extras: ${intent?.extras}")

        // Trigger state refresh from database
        lifecycleScope.launch(Dispatchers.IO) {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())

            withContext(Dispatchers.Main) {
                if (activeTrip != null) {
                    Logging.d(TAG, "Refreshing state for active trip: ${activeTrip.id} (status: ${activeTrip.status})")
                    
                    // Check if Service is already navigating this trip
                    val serviceIsNavigating = foregroundService?.isNavigatingForSync() ?: false
                    val serviceTrip = foregroundService?.getCurrentTripForSync()
                    val serviceNavigatingSameTrip = serviceIsNavigating && serviceTrip?.id == activeTrip.id

                    // If Service is navigating and trip is IN_PROGRESS, attach to existing navigation
                    if (activeTrip.status.equals("IN_PROGRESS", ignoreCase = true) && serviceNavigatingSameTrip) {
                        Logging.d(TAG, "Service is already navigating trip ${activeTrip.id} - attaching to existing navigation (not restarting)")
                        
                        // Sync state from Service to attach to existing navigation
                        currentTrip = activeTrip
                        tripResponse = activeTrip
                        isNavigating.set(true)
                        
                        // Sync full state from Service
                        syncStateFromService(activeTrip)
                        
                        // Don't call handleTripReceived - it would restart navigation
                        // Just ensure UI is updated
                        updatePassengerCounts()
                    } else if (currentTrip?.id != activeTrip.id ||
                        (activeTrip.status.equals("IN_PROGRESS", ignoreCase = true) && !isNavigating.get())) {
                        // Trip changed or navigation not active - handle normally
                        Logging.d(TAG, "Trip changed or navigation not active - handling trip")
                        handleTripReceived(activeTrip)
                    } else {
                        // Same trip, just sync state
                        Logging.d(TAG, "Same trip - syncing state")
                        syncStateFromService(activeTrip)
                    }
                } else {
                    // No active trip - verify state
                    verifyStateFromDatabase(null)
                    syncStateFromService(null)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        
        // Mark Activity as inactive
        isActivityActive.set(false)

        // Notify MQTT service that app is in background
        mqttService?.onAppBackground()

        if (!isDestroyed) {
            try {
                nfcReaderHelper?.disableNfcReader(this)
            } catch (e: Exception) {
                Logging.w(TAG, "Error disabling NFC reader during pause: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isDestroyed = true
        Logging.d(TAG, "AutoModeHeadlessActivity destroyed")

        // Mark activity as inactive
        isActivityActive.set(false)

        // Cancel countdown
        countdownJob?.cancel()

        // Cancel confirmation timeout
        confirmationTimeoutJob?.cancel()

        // Stop periodic backend fetch
        periodicFetchJob?.cancel()

        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null

        // Stop navigation using NavigationExample (handles all cleanup)
        navigationExample?.let { navExample ->
            // Set shutdown flag to prevent starting services during cleanup
            navExample.setShuttingDown(true)

            // Clear Navigator listeners FIRST to prevent accessing disposed Navigator
            // This must be done before stopping navigation to prevent listener callbacks
            try {
                val navigator = navExample.getHeadlessNavigator()
                navigator.routeProgressListener = null
                navigator.navigableLocationListener = null
                navigator.routeDeviationListener = null
                navigator.milestoneStatusListener = null
                navigator.destinationReachedListener = null
                Logging.d(TAG, "Navigator listeners cleared in onDestroy")
            } catch (e: Exception) {
                Logging.e(TAG, "Error clearing Navigator listeners: ${e.message}", e)
            }

            // Stop headless navigation (stops route, location, listeners)
            navExample.stopHeadlessNavigation()

            // Stop location services
            navExample.stopLocating()

            // Stop rendering (if any)
            navExample.stopRendering()

            Logging.d(TAG, "NavigationExample stopped and cleaned up")
        }

        // Null out navigationExample to prevent any access after cleanup
        navigationExample = null

        // Reset trip section validator
        if (::tripSectionValidator.isInitialized) {
            tripSectionValidator.reset()
        }

        // Clean up NFC reader
        try {
            nfcReaderHelper?.disableNfcReader(this)
        } catch (e: Exception) {
            Logging.w(TAG, "Error disabling NFC reader during destroy: ${e.message}")
        }

        // Unregister booking bundle receiver
        try {
            bookingBundleReceiver?.let { unregisterReceiver(it) }
            bookingBundleReceiver = null
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering booking bundle receiver: ${e.message}")
        }

        // Unregister confirmation receiver
        try {
            confirmationReceiver?.let { unregisterReceiver(it) }
            confirmationReceiver = null
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering confirmation receiver: ${e.message}")
        }

        // Unregister settings change receiver
        try {
            settingsChangeReceiver?.let { unregisterReceiver(it) }
            settingsChangeReceiver = null
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering settings change receiver: ${e.message}")
        }

        // Unregister Activity MQTT callbacks (Service callback should remain registered)
        try {
            mqttService?.setBookingBundleCallback(null)
            mqttService?.setTripEventCallback(null) // This only unregisters Activity callback, Service callback remains
            Logging.d(TAG, "Activity MQTT callbacks unregistered (Service callback remains for background)")
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering Activity MQTT callbacks: ${e.message}")
        }

        // Stop periodic trip data refresh
        tripRefreshHandler.removeCallbacks(tripRefreshRunnable)

        handler.removeCallbacksAndMessages(null)

        // Clean up map downloader
        mapDownloaderManager?.cleanup()

        // Unbind and stop foreground service
        try {
            // Unbind from service first
            unbindService(serviceConnection)

            // Stop the service explicitly
            val serviceIntent = Intent(this, AutoModeHeadlessForegroundService::class.java)
            stopService(serviceIntent)

            // Null out the reference
            foregroundService = null

            Logging.d(TAG, "AutoModeHeadlessForegroundService stopped and unbound")
        } catch (e: Exception) {
            Logging.e(TAG, "Error stopping foreground service: ${e.message}", e)
            foregroundService = null
        }

        // Dispose HERE SDK
        disposeHERESDK()
    }

    private fun disposeHERESDK() {
        // Free HERE SDK resources before the application shuts down.
        // Usually, this should be called only on application termination.
        // Afterwards, the HERE SDK is no longer usable unless it is initialized again.
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine != null) {
            sdkNativeEngine.dispose()
            // For safety reasons, we explicitly set the shared instance to null to avoid situations,
            // where a disposed instance is accidentally reused.
            SDKNativeEngine.setSharedInstance(null)
        }
    }
}

@Composable
fun AutoModeHeadlessScreen(
    messageText: String,
    countdownText: String,
    currentInput: String,
    isValidationInProgress: Boolean,
    nextWaypointName: String,
    pickupCount: Int,
    dropoffCount: Int,
    onDigitClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onClearClick: () -> Unit,
    showBookingSuccess: Boolean,
    showBookingFailure: Boolean,
    showValidationSuccess: Boolean,
    showValidationFailure: Boolean,
    showMqttNotification: Boolean,
    bookingSuccessData: AutoModeHeadlessActivity.BookingSuccessData,
    bookingFailureMessage: String,
    validationSuccessTicket: String,
    validationFailureMessage: String,
    mqttNotificationData: AutoModeHeadlessActivity.MqttNotificationData,
    showPassengerListDialog: Boolean,
    passengerListType: AutoModeHeadlessActivity.PassengerListType,
    passengerList: List<com.gocavgo.validator.service.BookingService.PassengerInfo>,
    onPickupCountClick: () -> Unit,
    onDropoffCountClick: () -> Unit,
    onPassengerClick: (String) -> Unit,
    onPassengerListDismiss: () -> Unit,
    onBookingSuccessDismiss: () -> Unit,
    onBookingFailureDismiss: () -> Unit,
    onValidationSuccessDismiss: () -> Unit,
    onValidationFailureDismiss: () -> Unit,
    onMqttNotificationDismiss: () -> Unit,
    showDestinationSelectionDialog: Boolean,
    availableDestinations: List<AvailableDestination>,
    currentLocationForDialog: String,
    onDestinationSelected: (AvailableDestination) -> Unit,
    onDestinationSelectionDismiss: () -> Unit,
    showConfirmationDialog: Boolean,
    confirmationTripData: TripConfirmationData?,
    onConfirmStart: () -> Unit,
    onConfirmCancel: () -> Unit,
    isConnected: Boolean,
    connectionType: String,
    isMetered: Boolean,
    showMapDownloadDialog: Boolean,
    mapDownloadProgress: Int,
    mapDownloadTotalSize: Int,
    mapDownloadMessage: String,
    mapDownloadStatus: String,
    onMapDownloadCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        // Network status indicator overlay
        NetworkStatusIndicator(
            isConnected = isConnected,
            connectionType = connectionType,
            isMetered = isMetered
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            // Countdown display (if active)
            if (countdownText.isNotEmpty()) {
                Text(
                    text = countdownText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Passenger count display
            if (pickupCount > 0 || dropoffCount > 0 || nextWaypointName.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable(enabled = pickupCount > 0) { onPickupCountClick() },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = "+$pickupCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Card(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable(enabled = dropoffCount > 0) { onDropoffCountClick() },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF44336).copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = "-$dropoffCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF44336),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Next waypoint name - hidden only when waiting for trip
            if (nextWaypointName.isNotEmpty() && !messageText.contains("Waiting for trip")) {
                Text(
                    text = nextWaypointName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Text(
                text = messageText,
                fontSize = 16.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Ticket input display
            TicketInputDisplay(
                currentInput = currentInput,
                isValidationInProgress = isValidationInProgress,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Numeric keyboard
            NumericKeyboard(
                onDigitClick = onDigitClick,
                onDeleteClick = onDeleteClick,
                onClearClick = onClearClick,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Success/Failure prompts (reuse from HeadlessNavigActivity)
        BookingSuccessPrompt(
            ticketNumber = bookingSuccessData.ticketNumber,
            fromLocation = bookingSuccessData.fromLocation,
            toLocation = bookingSuccessData.toLocation,
            price = bookingSuccessData.price,
            isVisible = showBookingSuccess,
            onDismiss = onBookingSuccessDismiss
        )

        BookingFailurePrompt(
            errorMessage = bookingFailureMessage,
            isVisible = showBookingFailure,
            onDismiss = onBookingFailureDismiss
        )

        ValidationSuccessPrompt(
            ticketNumber = validationSuccessTicket,
            isVisible = showValidationSuccess,
            onDismiss = onValidationSuccessDismiss
        )

        ValidationFailurePrompt(
            errorMessage = validationFailureMessage,
            isVisible = showValidationFailure,
            onDismiss = onValidationFailureDismiss
        )

        MqttBookingNotification(
            passengerName = mqttNotificationData.passengerName,
            pickup = mqttNotificationData.pickup,
            dropoff = mqttNotificationData.dropoff,
            numTickets = mqttNotificationData.numTickets,
            isPaid = mqttNotificationData.isPaid,
            isVisible = showMqttNotification,
            onDismiss = onMqttNotificationDismiss
        )

        PassengerListDialog(
            passengers = passengerList,
            listType = when(passengerListType) {
                AutoModeHeadlessActivity.PassengerListType.PICKUP -> HeadlessNavigActivity.PassengerListType.PICKUP
                AutoModeHeadlessActivity.PassengerListType.DROPOFF -> HeadlessNavigActivity.PassengerListType.DROPOFF
            },
            isVisible = showPassengerListDialog,
            onPassengerClick = onPassengerClick,
            onDismiss = onPassengerListDismiss
        )

        // Destination selection dialog (conditionally rendered)
        if (showDestinationSelectionDialog) {
            DestinationSelectionDialog(
                destinations = availableDestinations,
                currentLocation = currentLocationForDialog,
                onDestinationSelected = onDestinationSelected,
                onDismiss = onDestinationSelectionDismiss
            )
        }

        // Trip confirmation dialog (conditionally rendered)
        if (showConfirmationDialog && confirmationTripData != null) {
            TripConfirmationDialog(
                data = confirmationTripData,
                onConfirm = onConfirmStart,
                onCancel = onConfirmCancel
            )
        }

        // Map download dialog overlay
        if (showMapDownloadDialog) {
            MapDownloadDialog(
                progress = mapDownloadProgress,
                totalSize = mapDownloadTotalSize,
                message = mapDownloadMessage,
                status = mapDownloadStatus,
                onCancel = onMapDownloadCancel
            )
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

