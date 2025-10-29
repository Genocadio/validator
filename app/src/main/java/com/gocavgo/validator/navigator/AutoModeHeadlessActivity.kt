package com.gocavgo.validator.navigator

import android.annotation.SuppressLint
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
import android.util.Log
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
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.ui.components.NetworkStatusIndicator
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.location.LocationEngine
import com.here.sdk.location.LocationEngineStatus
import com.here.sdk.location.LocationFeature
import com.here.sdk.location.LocationStatusListener
import com.here.sdk.core.LocationListener
import com.here.sdk.core.Location
import com.here.sdk.navigation.Navigator
import com.here.sdk.navigation.RouteProgressListener
import com.here.sdk.navigation.NavigableLocationListener
import com.here.sdk.navigation.RouteDeviation
import com.here.sdk.navigation.RouteDeviationListener
import com.here.sdk.navigation.LocationSimulator
import com.here.sdk.navigation.LocationSimulatorOptions
import com.here.sdk.navigation.Milestone
import com.here.sdk.navigation.MilestoneStatus
import com.here.sdk.navigation.MilestoneStatusListener
import com.here.sdk.navigation.DestinationReachedListener
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.Route
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import com.here.sdk.core.GeoCoordinates
import com.gocavgo.validator.security.VehicleSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import com.gocavgo.validator.sync.SyncCoordinator
import java.text.SimpleDateFormat
import java.util.Date
import com.gocavgo.validator.ui.components.TripConfirmationData
import com.gocavgo.validator.ui.components.TripConfirmationDialog
import com.gocavgo.validator.receiver.TripConfirmationReceiver
import java.util.Locale

class AutoModeHeadlessActivity : ComponentActivity() {
    companion object {
        private const val TAG = "AutoModeHeadlessActivity"
        
        @Volatile
        private var isActivityActive = AtomicBoolean(false)
        
        fun isActive(): Boolean = isActivityActive.get()
    }

    private lateinit var databaseManager: DatabaseManager
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private var tripResponse: TripResponse? = null
    private var isSimulated: Boolean = false// Auto mode uses real GPS
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

    // HERE SDK components
    private var navigator: Navigator? = null
    private var locationEngine: LocationEngine? = null
    private var locationSimulator: LocationSimulator? = null
    private var onlineRoutingEngine: RoutingEngine? = null
    private var offlineRoutingEngine: OfflineRoutingEngine? = null
    private var currentRoute: Route? = null
    private var currentUserLocation: Location? = null
    private var isNavigationStarted = false
    private var currentSpeedInMetersPerSecond: Double = 0.0
    
    // Route deviation handling
    private var isReturningToRoute = false
    private var deviationCounter = 0
    private val DEVIATION_THRESHOLD_METERS = 50
    private val MIN_DEVIATION_EVENTS = 3
    
    // Trip section validator for route validation and waypoint tracking
    private lateinit var tripSectionValidator: TripSectionValidator
    
    // NFC functionality
    private var nfcReaderHelper: NFCReaderHelper? = null
    private var mqttService: MqttService? = null
    private var bookingBundleReceiver: BroadcastReceiver? = null
    private var confirmationReceiver: BroadcastReceiver? = null
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
        
        Logging.d(TAG, "Network restored after $offlineMinutes minutes")
        
        // If offline for more than 5 minutes and not navigating, fetch immediately
        if (offlineMinutes >= 5 && !isNavigating.get()) {
            Logging.d(TAG, "Extended offline period detected, fetching from backend")
            wasOfflineForExtendedPeriod.set(true)
            
            lifecycleScope.launch {
                performImmediateBackendFetch()
            }
        }
        
        networkOfflineTime.set(0)
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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Initialize components in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Initialize HERE SDK first
                initializeHERESDK()

                // Get MQTT service instance
                mqttService = MqttService.getInstance()

                // Switch to main thread for remaining initialization
                withContext(Dispatchers.Main) {
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
                    
                    // Initialize navigation components
                    initializeNavigationComponents()
                    
                    // Start periodic trip data refresh
                    tripRefreshHandler.postDelayed(tripRefreshRunnable, 10000)
                    
                    // Start periodic backend fetch
                    startPeriodicBackendFetch()
                }
                
                // Fetch trips from backend on launch
                fetchTripsFromBackend()
            } catch (e: Exception) {
                Logging.e(TAG, "Error during initialization: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    messageViewText = "Error: ${e.message}"
                }
            }
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
            
            // Ignore new trips if already navigating (existing logic)
            if (isNavigating.get()) {
                Logging.w(TAG, "Navigation active, ignoring new trip")
                return@setTripEventCallback
            }
            
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    val trip = convertBackendTripToAndroid(tripEvent.data)
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
            
            // Start navigation immediately, bypassing all countdown/confirmation logic
            startNavigationInternal(trip)
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

    private fun startNavigationInternal(trip: TripResponse) {
        if (isNavigating.get()) {
            Logging.w(TAG, "Navigation already active, ignoring start request")
            return
        }
        
        Logging.d(TAG, "=== STARTING NAVIGATION INTERNALLY ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        
        // Clear any confirmation state (safety check)
        if (isAwaitingConfirmation.get() || showConfirmationDialog) {
            Logging.d(TAG, "Clearing confirmation state before starting navigation")
            isAwaitingConfirmation.set(false)
            showConfirmationDialog = false
            confirmationTimeoutJob?.cancel()
            foregroundService?.cancelConfirmationNotification()
        }
        
        isNavigating.set(true)
        tripResponse = trip
        currentTrip = trip
        
        // Immediately update notification to reflect navigation starting
        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
        messageViewText = "Starting navigation: $origin"
        foregroundService?.updateNotification("Starting navigation...")
        Logging.d(TAG, "Navigation state set, notification updated")
        
        // Update passenger counts
        updatePassengerCounts()
        
        // Start navigation using existing HeadlessNavigActivity logic
        startNavigation()
        
        // Update again after navigation starts
        messageViewText = "Navigating: $origin"
        foregroundService?.updateNotification("Navigating: $origin")
    }
    
    private fun showTripConfirmation(trip: TripResponse, delayMinutes: Int) {
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
                Logging.d(TAG, "Confirmation timeout - auto-starting navigation")
                withContext(Dispatchers.Main) {
                    handleConfirmStart()
                }
            }
        }
    }
    
    private fun handleConfirmStart() {
        Logging.d(TAG, "=== HANDLE CONFIRM START ===")
        Logging.d(TAG, "IsAwaitingConfirmation: ${isAwaitingConfirmation.get()}")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")
        
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
        
        val trip = confirmationTripData?.trip
        if (trip == null) {
            Logging.e(TAG, "No trip data available for confirmation")
            return
        }
        
        // Clear confirmation state
        isAwaitingConfirmation.set(false)
        showConfirmationDialog = false
        confirmationTimeoutJob?.cancel()
        foregroundService?.cancelConfirmationNotification()
        
        Logging.d(TAG, "User confirmed trip start, initiating navigation")
        startNavigationInternal(trip)
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
    
    private fun stopNavigationAndCleanup() {
        try {
            // Stop navigator
            navigator?.route = null
            
            // Stop location simulator if active
            locationSimulator?.stop()
            
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
        
        // Reset to listening state
        messageViewText = "Auto Mode: Waiting for trip..."
        countdownText = ""
        foregroundService?.updateNotification("Auto Mode: Waiting for trip...")
        
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

            // Initialize routing engines
            initializeRoutingEngines()

            // Initialize navigator
            initializeNavigator()

            // Initialize location engine
            initializeLocationEngine()

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
        try {
            // Check if SDK is already initialized to avoid duplicate initialization
            if (SDKNativeEngine.getSharedInstance() != null) {
                Logging.d(TAG, "HERE SDK already initialized, skipping")
                return
            }
            
            val accessKeyID = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_ID
            val accessKeySecret = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_SECRET
            val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
            val options = SDKOptions(authenticationMode)
            if(lowMem) {
                options.lowMemoryMode = true
                Logging.d(TAG, "Initialised in Low memory mode")
            }
            
            // Initialize SDK
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
            
            // Handle network recovery for backend sync
            if (connected) {
                handleNetworkRestored()
            } else {
                handleNetworkLost()
            }
        }

        networkMonitor?.startMonitoring()
        Logging.d(TAG, "Network monitoring started")
    }

    private fun initializeRoutingEngines() {
        try {
            onlineRoutingEngine = RoutingEngine()
            offlineRoutingEngine = OfflineRoutingEngine()
            Logging.d(TAG, "Routing engines initialized successfully")
        } catch (e: InstantiationErrorException) {
            Logging.e(TAG, "Initialization of routing engines failed: ${e.error.name}", e)
            throw RuntimeException("Initialization of routing engines failed: " + e.error.name)
        }
    }

    private fun initializeNavigator() {
        try {
            navigator = Navigator()
            Logging.d(TAG, "Navigator initialized successfully")
        } catch (e: InstantiationErrorException) {
            Logging.e(TAG, "Initialization of Navigator failed: ${e.error.name}", e)
            throw RuntimeException("Initialization of Navigator failed: " + e.error.name)
        }
    }

    private fun initializeLocationEngine() {
        try {
            locationEngine = LocationEngine()
            Logging.d(TAG, "LocationEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            Logging.e(TAG, "Initialization of LocationEngine failed: ${e.error.name}", e)
            // Continue without GPS if initialization fails
        }
    }

    private fun startNavigation() {
        tripResponse?.let { trip ->
            if (!isSimulated) {
                startLocationEngineAndWaitForLocation()
            } else {
                isNavigationStarted = true
                calculateRouteAndStartNavigation()
            }
        }
    }

    private fun startLocationEngineAndWaitForLocation() {
        locationEngine?.let { engine ->
            Logging.d(TAG, "Starting location engine for real-time navigation...")

            val locationStatusListener: LocationStatusListener = object : LocationStatusListener {
                override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
                    Logging.d(TAG, "Location engine status: ${locationEngineStatus.name}")
                }

                override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
                    for (feature in features) {
                        Logging.w(TAG, "Location feature not available: ${feature.name}")
                    }
                }
            }

            val locationListener = LocationListener { location ->
                Logging.d(TAG, "Received location: ${location.coordinates.latitude}, ${location.coordinates.longitude}")
                currentUserLocation = location

                // Start navigation once we have a location (only once)
                if (!isNavigationStarted) {
                    Logging.d(TAG, "Location acquired, starting navigation...")
                    isNavigationStarted = true
                    calculateRouteAndStartNavigation()
                }
            }

            try {
                engine.addLocationListener(locationListener)
                engine.addLocationStatusListener(locationStatusListener)
                engine.confirmHEREPrivacyNoticeInclusion()
                engine.start(LocationAccuracy.NAVIGATION)

                // Set a timeout to fallback to simulation if no location is received
                Handler(Looper.getMainLooper()).postDelayed({
                    if (currentUserLocation == null && !isNavigationStarted) {
                        Logging.w(TAG, "No location received within timeout, falling back to simulated navigation")
                        isSimulated = true
                        isNavigationStarted = true
                        calculateRouteAndStartNavigation()
                    }
                }, 10000) // 10 second timeout

            } catch (e: Exception) {
                Logging.e(TAG, "Failed to start location engine: ${e.message}", e)
                Logging.w(TAG, "Falling back to simulated navigation due to GPS failure")
                isSimulated = true
                isNavigationStarted = true
                calculateRouteAndStartNavigation()
            }
        } ?: run {
            Logging.w(TAG, "Location engine not available, falling back to simulated navigation")
            isSimulated = true
            isNavigationStarted = true
            calculateRouteAndStartNavigation()
        }
    }

    private fun calculateRouteAndStartNavigation() {
        tripResponse?.let { trip ->
            var origin: Waypoint? = null
            if (currentUserLocation == null && !isSimulated) {
                Logging.w(TAG, "No current user location available, switching to simulated navigation")
                isSimulated = true
            } else {
                currentUserLocation?.let {
                    origin = Waypoint(it.coordinates)
                    origin!!.headingInDegrees = it.bearingInDegrees
                }
            }
            val waypoints = createWaypointsFromTrip(trip, origin)

            val carOptions = CarOptions().apply {
                routeOptions.optimizeWaypointsOrder = false
                routeOptions.enableTolls = true
                routeOptions.alternatives = 1
            }

            val routingEngine = onlineRoutingEngine ?: offlineRoutingEngine!!

            Logging.d(TAG, "Calculating route")
            Logging.d(TAG, "Trip ID: ${trip.id}")
            Logging.d(TAG, "Waypoints: ${waypoints.size}")

            routingEngine.calculateRoute(waypoints, carOptions) { routingError, routes ->
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
        navigator?.let { nav ->
            // Set up listeners
            setupHeadlessListeners(nav)
            
            // Set the route
            nav.route = route
            
            // Verify route sections with trip section validator
            tripResponse?.let { trip ->
                // Calculate skipped waypoint count
                val skippedCount = trip.waypoints.count { it.is_passed }
                
                Logging.d(TAG, "=== ROUTE VERIFICATION WITH SKIPPED WAYPOINTS ===")
                Logging.d(TAG, "Skipped waypoints: $skippedCount")
                Logging.d(TAG, "Total waypoints: ${trip.waypoints.size}")
                
                val isVerified = tripSectionValidator.verifyRouteSections(
                    tripResponse = trip,
                    route = route,
                    isSimulated = isSimulated,
                    deviceLocation = currentUserLocation?.coordinates,
                    skippedWaypointCount = skippedCount
                )
                Logging.d(TAG, "Route verification result: $isVerified")
                Logging.d(TAG, "================================================")
            }
            
            // Set up location source
            setupLocationSource(nav, route, isSimulated)
            
            messageViewText = "Navigation in progress"
            Logging.d(TAG, "Navigation started")
        }
    }

    private fun setupHeadlessListeners(nav: Navigator) {
        nav.routeProgressListener = RouteProgressListener { routeProgress ->
            try {
                Logging.d(TAG, "Route progress update received")
                
                // Process section progress through trip section validator for verified trips
                val sectionProgressList = routeProgress.sectionProgress
                val totalSections = sectionProgressList.size
                tripSectionValidator.processSectionProgress(sectionProgressList, totalSections)
                
                // Update passenger counts when route progress changes
                updatePassengerCounts()
                
                val maneuverProgressList = routeProgress.maneuverProgress
                if (maneuverProgressList.isNotEmpty()) {
                    val nextManeuverProgress = maneuverProgressList[0]
                    nextManeuverProgress?.let { progress ->
                        val remainingDistance = progress.remainingDistanceInMeters / 1000.0
                        if (sectionProgressList.isNotEmpty()) {
                            val lastSection = sectionProgressList.last()
                            val totalRemainingDistance = lastSection.remainingDistanceInMeters / 1000.0

                            val speedInKmh = currentSpeedInMetersPerSecond * 3.6
                            messageViewText = "Next: ${String.format("%.1f", remainingDistance)} km | Speed: ${String.format("%.1f", speedInKmh)} km/h"
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error in route progress listener: ${e.message}", e)
            }
        }

        nav.navigableLocationListener = NavigableLocationListener { currentNavigableLocation ->
            try {
                val lastMapMatchedLocation = currentNavigableLocation.mapMatchedLocation
                if (lastMapMatchedLocation == null) {
                    Logging.w(TAG, "No map-matched location available")
                    return@NavigableLocationListener
                }

                val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond ?: 0.0
                val accuracy = currentNavigableLocation.originalLocation.speedAccuracyInMetersPerSecond ?: 0.0
                currentSpeedInMetersPerSecond = speed
                
                // Extract location data from map-matched location
                val lat = lastMapMatchedLocation.coordinates.latitude
                val lng = lastMapMatchedLocation.coordinates.longitude
                val bearing = lastMapMatchedLocation.bearingInDegrees
                
                // Store location data for use by TripSectionValidator
                tripSectionValidator.updateLocationData(lat, lng, speed, accuracy, bearing)
            } catch (e: Exception) {
                Logging.e(TAG, "Error in navigable location listener: ${e.message}", e)
            }
        }

        // Notifies on route deviation events
        nav.routeDeviationListener = RouteDeviationListener { routeDeviation ->
            try {
                handleRouteDeviation(routeDeviation)
            } catch (e: Exception) {
                Logging.e(TAG, "Error in route deviation listener: ${e.message}", e)
            }
        }

        // Notifies when a waypoint on the route is reached or missed
        nav.milestoneStatusListener =
            MilestoneStatusListener { milestone, milestoneStatus ->
                if (milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.REACHED) {
                    val waypointOrder = milestone.waypointIndex!!
                    val waypointCoordinates = milestone.originalCoordinates!!
                    Logging.d(TAG, "Waypoint reached: milestoneIndex=${milestone.waypointIndex}, waypointOrder=$waypointOrder")
                    tripSectionValidator.markWaypointAsPassedByMilestone(waypointOrder, waypointCoordinates)
                }
            }

        // Notifies when the destination of the route is reached
        nav.destinationReachedListener = DestinationReachedListener {
            Logging.d(TAG, "Destination reached!")
            tripSectionValidator.handleDestinationReached()
            
            // Navigation complete - return to listening state
            lifecycleScope.launch(Dispatchers.Main) {
                onNavigationComplete()
            }
        }
    }

    private fun handleRouteDeviation(routeDeviation: RouteDeviation) {
        val route = navigator?.route ?: return
        
        val currentMapMatchedLocation = routeDeviation.currentLocation.mapMatchedLocation
        val currentGeoCoordinates = currentMapMatchedLocation?.coordinates
            ?: routeDeviation.currentLocation.originalLocation.coordinates
        
        val lastGeoCoordinatesOnRoute = if (routeDeviation.lastLocationOnRoute != null) {
            val lastMapMatched = routeDeviation.lastLocationOnRoute!!.mapMatchedLocation
            lastMapMatched?.coordinates ?: routeDeviation.lastLocationOnRoute!!.originalLocation.coordinates
        } else {
            route.sections[0].departurePlace.originalCoordinates
        }
        
        val distanceInMeters = currentGeoCoordinates.distanceTo(lastGeoCoordinatesOnRoute!!).toInt()
        deviationCounter++
        
        if (isReturningToRoute) {
            Logging.d(TAG, "Rerouting already in progress")
            return
        }
        
        if (distanceInMeters > DEVIATION_THRESHOLD_METERS && deviationCounter >= MIN_DEVIATION_EVENTS) {
            Logging.d(TAG, "=== ROUTE DEVIATION DETECTED ===")
            Logging.d(TAG, "Distance: ${distanceInMeters}m - Starting reroute")
            isReturningToRoute = true
            
            val newStartingPoint = Waypoint(currentGeoCoordinates)
            currentMapMatchedLocation?.bearingInDegrees?.let { 
                newStartingPoint.headingInDegrees = it 
            }
            
            val routingEngine = onlineRoutingEngine ?: offlineRoutingEngine!!
            routingEngine.returnToRoute(
                route,
                newStartingPoint,
                routeDeviation.lastTraveledSectionIndex,
                routeDeviation.traveledDistanceOnLastSectionInMeters
            ) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val newRoute = routes[0]
                    currentRoute = newRoute
                    navigator?.route = newRoute
                    Logging.d(TAG, "Rerouting successful")
                    runOnUiThread {
                        messageViewText = "Route recalculated"
                    }
                } else {
                    Logging.e(TAG, "Rerouting failed: ${routingError?.name}")
                }
                isReturningToRoute = false
                deviationCounter = 0
            }
        }
    }

    private fun setupLocationSource(locationListener: LocationListener, route: Route, isSimulator: Boolean) {
        if (!isSimulator) {
            locationEngine?.let { engine ->
                Logging.d(TAG, "Setting up location source for real-time navigation")
                try {
                    engine.addLocationListener(locationListener)
                    Logging.d(TAG, "Added navigation location listener to existing location engine")
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to add location listener: ${e.message}")
                }
            }
        } else {
            try {
                locationSimulator = LocationSimulator(route, LocationSimulatorOptions())
            } catch (e: InstantiationErrorException) {
                Logging.e(TAG, "Initialization of LocationSimulator failed: ${e.error.name}")
                throw RuntimeException("Initialization of LocationSimulator failed: " + e.error.name)
            }

            locationSimulator?.let { simulator ->
                simulator.listener = locationListener
                simulator.start()
                Logging.d(TAG, "Location simulation started")
            }
        }
    }

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
                        
                        // Update waypoint name
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
        
        Logging.d(TAG, "=== ON RESUME ===")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")
        Logging.d(TAG, "IsAwaitingConfirmation: ${isAwaitingConfirmation.get()}")
        Logging.d(TAG, "ShowConfirmationDialog: $showConfirmationDialog")
        
        // Reconcile state with actual navigation status
        Logging.d(TAG, "State reconciliation - isNavigating: ${isNavigating.get()}, countdownActive: ${countdownText.isNotEmpty()}, awaitingConfirmation: ${isAwaitingConfirmation.get()}")
        
        when {
            isNavigating.get() -> {
                // Navigation active - ensure UI reflects this
                if (countdownText.isNotEmpty()) {
                    Logging.d(TAG, "Clearing countdown text during active navigation")
                    countdownText = ""
                }
                if (isAwaitingConfirmation.get() || showConfirmationDialog) {
                    Logging.d(TAG, "Clearing confirmation UI during active navigation")
                    isAwaitingConfirmation.set(false)
                    showConfirmationDialog = false
                    confirmationTimeoutJob?.cancel()
                    foregroundService?.cancelConfirmationNotification()
                }
                // Ensure message reflects navigation
                if (!messageViewText.contains("Navigat") && !messageViewText.contains("Next:")) {
                    val origin = currentTrip?.route?.origin?.custom_name 
                        ?: currentTrip?.route?.origin?.google_place_name 
                        ?: "destination"
                    messageViewText = "Navigating: $origin"
                    Logging.d(TAG, "Updated message to navigation state")
                }
            }
            isAwaitingConfirmation.get() -> {
                // Awaiting confirmation - ensure UI shows dialog
                countdownText = ""
                if (!showConfirmationDialog && confirmationTripData != null) {
                    Logging.d(TAG, "Restoring confirmation dialog display")
                    showConfirmationDialog = true
                }
            }
            countdownText.isNotEmpty() -> {
                // Countdown active - this is correct state
                Logging.d(TAG, "Countdown active: $countdownText")
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
    
    override fun onPause() {
        super.onPause()
        
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
        
        // Stop navigation
        navigator?.route = null
        navigator?.routeProgressListener = null
        navigator?.navigableLocationListener = null
        navigator?.routeDeviationListener = null
        navigator?.milestoneStatusListener = null
        navigator?.destinationReachedListener = null
        
        // Reset trip section validator
        if (::tripSectionValidator.isInitialized) {
            tripSectionValidator.reset()
        }
        
        // Stop location engine
        locationEngine?.let { engine ->
            try {
                engine.stop()
                Logging.d(TAG, "Location engine stopped")
            } catch (e: Exception) {
                Logging.e(TAG, "Error stopping location engine: ${e.message}")
            }
        }
        
        // Stop location simulator
        locationSimulator?.let { simulator ->
            simulator.stop()
            simulator.listener = null
            Logging.d(TAG, "Location simulator stopped")
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
        
        // Unregister MQTT callbacks
        try {
            mqttService?.setBookingBundleCallback(null)
            mqttService?.setTripEventCallback(null)
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering MQTT callbacks: ${e.message}")
        }

        // Stop periodic trip data refresh
        tripRefreshHandler.removeCallbacks(tripRefreshRunnable)
        
        handler.removeCallbacksAndMessages(null)
        
        // Unbind and stop foreground service
        try {
            unbindService(serviceConnection)
            stopService(Intent(this, AutoModeHeadlessForegroundService::class.java))
        } catch (e: Exception) {
            Logging.e(TAG, "Error stopping foreground service: ${e.message}", e)
        }
        
        // Dispose HERE SDK
        disposeHERESDK()
    }

    private fun disposeHERESDK() {
        SDKNativeEngine.getSharedInstance()?.dispose()
        SDKNativeEngine.setSharedInstance(null)
        Logging.d(TAG, "HERE SDK disposed")
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
            if (nextWaypointName.isNotEmpty()) {
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

                // Next waypoint name
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
    }
}
