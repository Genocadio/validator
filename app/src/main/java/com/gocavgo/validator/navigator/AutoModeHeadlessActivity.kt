package com.gocavgo.validator.navigator

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.booking.BookingNfcManager
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.trip.ActiveTripListener
import com.gocavgo.validator.trip.ActiveTripCallback
import com.gocavgo.validator.service.AutoModeHeadlessForegroundService
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
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
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.gocavgo.validator.sync.SyncCoordinator
import com.gocavgo.validator.receiver.TripConfirmationReceiver
import com.gocavgo.validator.MapDownloaderManager
import android.widget.Toast

class AutoModeHeadlessActivity : ComponentActivity() {
    companion object {
        private const val TAG = "AutoModeHeadlessActivity"
        
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
    private var countdownText by mutableStateOf("")
    
    // Waypoint overlay state
    private var showWaypointOverlay by mutableStateOf(false)
    private var waypointProgressData by mutableStateOf<List<TripSectionValidator.WaypointProgressInfo>>(emptyList())
    
    // Route calculation state
    private var isRouteCalculated by mutableStateOf(false)
    
    // Cache for notification persistence (prevent flickering when data is temporarily null)
    private var lastKnownDistance: Double? = null
    private var lastKnownTime: Long? = null
    private var lastKnownWaypointName: String? = null

    // Active trip listener
    private var activeTripListener: ActiveTripListener? = null

    // HERE SDK components - using App class like HeadlessNavigActivity
    private var app: App? = null
    private var messageViewUpdater: MessageViewUpdater? = null

    // MQTT service
    private var mqttService: MqttService? = null
    private var confirmationReceiver: BroadcastReceiver? = null
    private var settingsChangeReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // Booking and NFC manager
    private lateinit var bookingNfcManager: BookingNfcManager

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

    // Periodic navigation state sync from Service (when Service is navigating and Activity is active)
    private var serviceNavigationSyncJob: Job? = null
    

    // Trip data refresh for passenger counts
    private val tripRefreshHandler = Handler(Looper.getMainLooper())
    private val tripRefreshRunnable = object : Runnable {
        override fun run() {
            refreshTripDataFromDatabase()
            tripRefreshHandler.postDelayed(this, 10000) // Refresh every 10 seconds
        }
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

        // Delegate to ActiveTripListener
        activeTripListener?.handleNetworkRestored(isNavigating.get(), currentTrip)
        networkOfflineTime.set(0)
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

        // Initialize booking and NFC manager
        bookingNfcManager = BookingNfcManager(
            context = this,
            lifecycleScope = lifecycleScope,
            databaseManager = databaseManager,
            handler = handler
        )

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
                // Access state from booking manager (mutableStateOf can be used directly in Compose)
                val currentInput by bookingNfcManager.currentInput
                val isValidationInProgress by bookingNfcManager.isValidationInProgress
                val nextWaypointName by bookingNfcManager.nextWaypointName
                val pickupCount by bookingNfcManager.pickupCount
                val dropoffCount by bookingNfcManager.dropoffCount
                val showBookingSuccess by bookingNfcManager.showBookingSuccess
                val showBookingFailure by bookingNfcManager.showBookingFailure
                val showValidationSuccess by bookingNfcManager.showValidationSuccess
                val showValidationFailure by bookingNfcManager.showValidationFailure
                val showMqttNotification by bookingNfcManager.showMqttNotification
                val bookingSuccessData by bookingNfcManager.bookingSuccessData
                val bookingFailureMessage by bookingNfcManager.bookingFailureMessage
                val validationSuccessTicket by bookingNfcManager.validationSuccessTicket
                val validationFailureMessage by bookingNfcManager.validationFailureMessage
                val mqttNotificationData by bookingNfcManager.mqttNotificationData
                val showPassengerListDialog by bookingNfcManager.showPassengerListDialog
                val passengerListType by bookingNfcManager.passengerListType
                val passengerList by bookingNfcManager.passengerList
                val showDestinationSelectionDialog by bookingNfcManager.showDestinationSelectionDialog
                val availableDestinations by bookingNfcManager.availableDestinations
                val currentLocationForDialog by bookingNfcManager.currentLocationForDialog

                AutoModeHeadlessScreen(
                    messageText = messageViewText,
                    countdownText = countdownText,
                    currentInput = currentInput,
                    isValidationInProgress = isValidationInProgress,
                    nextWaypointName = nextWaypointName,
                    pickupCount = pickupCount,
                    dropoffCount = dropoffCount,
                    onDigitClick = { bookingNfcManager.addDigit(it) },
                    onDeleteClick = { bookingNfcManager.deleteLastDigit() },
                    onClearClick = { bookingNfcManager.forceClearInput() },
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
                    onPickupCountClick = { bookingNfcManager.showPickupPassengerList() },
                    onDropoffCountClick = { bookingNfcManager.showDropoffPassengerList() },
                    onPassengerClick = { bookingNfcManager.showPassengerDetails(it) },
                    onPassengerListDismiss = { bookingNfcManager.dismissPassengerList() },
                    onBookingSuccessDismiss = { bookingNfcManager.dismissBookingSuccess() },
                    onBookingFailureDismiss = { bookingNfcManager.dismissBookingFailure() },
                    onValidationSuccessDismiss = { bookingNfcManager.dismissValidationSuccess() },
                    onValidationFailureDismiss = { bookingNfcManager.dismissValidationFailure() },
                    onMqttNotificationDismiss = { bookingNfcManager.dismissMqttNotification() },
                    showDestinationSelectionDialog = showDestinationSelectionDialog,
                    availableDestinations = availableDestinations,
                    currentLocationForDialog = currentLocationForDialog,
                    onDestinationSelected = { bookingNfcManager.onDestinationSelected(it) },
                    onDestinationSelectionDismiss = { bookingNfcManager.dismissDestinationSelection() },
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
                    showConfirmationDialog = false,
                    confirmationTripData = null,
                    onConfirmStart = { },
                    onConfirmCancel = { },
                    showWaypointOverlay = showWaypointOverlay,
                    waypointProgressData = waypointProgressData,
                    onToggleWaypointOverlay = { 
                        showWaypointOverlay = !showWaypointOverlay
                        if (showWaypointOverlay) {
                            updateWaypointProgressData()
                            startWaypointProgressUpdates()
                        }
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

                // Get MQTT service instance (Service should have initialized it)
                mqttService = MqttService.getInstance()
                
                // Verify MQTT connection - if not connected, trigger reconnection
                if (mqttService != null && !mqttService!!.isConnected()) {
                    Logging.d(TAG, "MQTT service exists but not connected in onCreate, ensuring connection...")
                    val isActive = mqttService!!.isServiceActive()
                    if (!isActive) {
                        // Service is not active, but we can't connect from Activity (Service should handle it)
                        Logging.w(TAG, "MQTT service is not active - Service should handle connection")
                    } else {
                        // Service is active but not connected, trigger reconnection
                        Logging.d(TAG, "MQTT service is active but not connected, triggering reconnection...")
                        mqttService?.onAppForeground() // This will trigger reconnection if needed
                    }
                } else if (mqttService == null) {
                    Logging.w(TAG, "MQTT service not initialized - Service should initialize it")
                } else {
                    Logging.d(TAG, "MQTT service is connected")
                }

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

                    // Initialize booking and NFC manager with trip data
                    bookingNfcManager.initialize(currentTrip)

                    // Initialize ActiveTripListener
                    activeTripListener = ActiveTripListener(this@AutoModeHeadlessActivity)
                    activeTripListener?.start(
                        scope = lifecycleScope,
                        callback = object : ActiveTripCallback {
                            override fun onActiveTripFound(trip: TripResponse) {
                                Logging.d(TAG, "Active trip found: ${trip.id}")
                                handleTripReceived(trip)
                            }
                            
                            override fun onActiveTripChanged(newTrip: TripResponse, oldTrip: TripResponse?) {
                                Logging.d(TAG, "Active trip changed: ${oldTrip?.id} → ${newTrip.id}")
                                handleTripReceived(newTrip)
                            }
                            
                            override fun onTripCancelled(tripId: Int) {
                                Logging.d(TAG, "Trip cancelled: $tripId")
                                handleTripCancellation(tripId)
                            }
                            
                            override fun onError(error: String) {
                                Logging.e(TAG, "Error from ActiveTripListener: $error")
                                messageViewText = "Error: $error"
                            }
                            
                            override fun onCountdownTextUpdate(countdownText: String) {
                                this@AutoModeHeadlessActivity.countdownText = countdownText
                                if (countdownText.isNotEmpty()) {
                                    val trip = currentTrip
                                    if (trip != null) {
                                        foregroundService?.updateNotification("$countdownText - ${trip.route.origin.google_place_name}")
                                    }
                                }
                            }
                            
                            override fun onCountdownComplete(trip: TripResponse) {
                                Logging.d(TAG, "Countdown complete, starting navigation")
                                countdownText = ""
                                messageViewText = "Starting navigation..."
                                foregroundService?.updateNotification("Starting navigation...")
                                startNavigationInternal(trip)
                            }
                            
                            override fun onNavigationStartRequested(trip: TripResponse, allowResume: Boolean) {
                                Logging.d(TAG, "Navigation start requested (allowResume=$allowResume)")
                                if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                                    val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                                    val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                                    messageViewText = "Resuming navigation: $origin → $destination"
                                    foregroundService?.updateNotification("Resuming navigation...")
                                } else {
                                    val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                                    val destination = trip.route.destination.custom_name ?: trip.route.destination.google_place_name
                                    messageViewText = "Starting navigation: $origin → $destination"
                                    foregroundService?.updateNotification("Starting navigation...")
                                }
                                startNavigationInternal(trip, allowResume)
                            }
                            
                            override fun onTripScheduled(trip: TripResponse, departureTimeMillis: Long) {
                                Logging.d(TAG, "Trip scheduled: ${trip.id}, departure time: $departureTimeMillis")
                                // Format departure time for display
                                val departureDate = java.util.Date(departureTimeMillis)
                                val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                val formattedTime = timeFormat.format(departureDate)
                                
                                val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                                val departureMessage = "Trip will depart at $formattedTime - $origin"
                                
                                messageViewText = departureMessage
                                foregroundService?.updateNotification(departureMessage)
                                
                                // Update booking manager with trip
                                bookingNfcManager.updateTrip(trip)
                            }
                            
                            override fun onSettingsSynced(settings: VehicleSettings, isNavigating: Boolean): Boolean {
                                lifecycleScope.launch(Dispatchers.Main) {
                                    currentSettings = settings
                                    
                                    // If logout or deactivate = true, immediately exit
                                    if (settings.logout || settings.deactivate) {
                                        Logging.w(TAG, "Logout or deactivate detected - immediately exiting to LauncherActivity")
                                        if (isNavigating) {
                                            stopNavigationAndCleanup()
                                        }
                                        exitToLauncherActivity()
                                    } else {
                                        // Apply settings (check other settings like devmode)
                                        settingsManager.applySettings(this@AutoModeHeadlessActivity, settings)
                                        
                                        // For other settings changes (not logout/deactivate), check if should route
                                        // Only exit if NOT navigating (if navigating, will check after navigation)
                                        if (!isNavigating && (settingsManager.areAllSettingsFalse(settings) || !settings.devmode)) {
                                            Logging.d(TAG, "Settings require routing (not navigating) - exiting to LauncherActivity")
                                            exitToLauncherActivity()
                                        }
                                    }
                                }
                                return settings.logout || settings.deactivate || (!isNavigating && (settingsManager.areAllSettingsFalse(settings) || !settings.devmode))
                            }
                            
                            override fun onTripStateVerificationNeeded(trip: TripResponse) {
                                lifecycleScope.launch(Dispatchers.Main) {
                                    verifyStateFromDatabase(trip)
                                    syncStateFromService(trip)
                                }
                            }
                            
                            override fun onImmediateBackendFetchComplete(trip: TripResponse?) {
                                if (trip != null && trip.id != currentTrip?.id) {
                                    Logging.d(TAG, "Active trip changed after recovery: ${trip.id}")
                                    handleTripReceived(trip)
                                }
                                wasOfflineForExtendedPeriod.set(false)
                            }
                            
                            override fun onSilentBackendFetchFoundTrip(trip: TripResponse) {
                                lifecycleScope.launch(Dispatchers.Main) {
                                    handleTripReceived(trip)
                                }
                            }
                        },
                        settingsManager = settingsManager,
                        app = app
                    )

                    // Register confirmation receiver (for backward compatibility, but won't be used)
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
                        val serviceHasActiveRoute = foregroundService?.hasActiveRouteForSync() ?: false

                        Logging.d(TAG, "onCreate after init - Service navigation state check:")
                        Logging.d(TAG, "  - serviceIsNavigating: $serviceIsNavigating")
                        Logging.d(TAG, "  - serviceNavigatingSameTrip: $serviceNavigatingSameTrip")
                        Logging.d(TAG, "  - serviceHasActiveRoute: $serviceHasActiveRoute")

                        // If trip is IN_PROGRESS, attach to existing navigation or resume
                        if (trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                            if (serviceNavigatingSameTrip || serviceHasActiveRoute) {
                                // Service is already navigating this trip - just attach to it, don't restart
                                Logging.d(TAG, "Service is already navigating trip ${trip.id} (flag=${serviceIsNavigating}, route=${serviceHasActiveRoute}) - attaching to existing navigation")
                                isNavigating.set(true)
                                // Don't reset navigation flags - Service navigation is already active
                                // Just sync state and let RouteProgressListener update UI
                                syncStateFromService(trip)
                                
                                // Update UI immediately with current navigation state from Service
                                val serviceNavigationText = foregroundService?.getCurrentNavigationTextForSync() ?: ""
                                if (serviceNavigationText.isNotEmpty() && (serviceNavigationText.contains("Next:") || serviceNavigationText.contains("km/h"))) {
                                    messageViewText = serviceNavigationText
                                    Logging.d(TAG, "Using Service navigation text on create: $serviceNavigationText")
                                } else {
                                    val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                                    messageViewText = "Navigating: $origin"
                                }
                                
                                // Update booking manager with trip (this updates passenger counts and waypoint name)
                                bookingNfcManager.updateTrip(trip)
                                
                                // Don't update notification - Service is already updating it
                            } else if (!isNavigating.get()) {
                                // Navigation was not active, start it
                                Logging.d(TAG, "Resuming navigation for IN_PROGRESS trip after initialization")
                                // App handles navigation state internally
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
                                // App handles navigation state internally
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
                }

                // Silent fetch if no active trip/navigation/countdown
                if (!isNavigating.get() && 
                    countdownText.isEmpty() && 
                    currentTrip == null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        activeTripListener?.performSilentBackendFetch()
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


    private fun handleTripReceived(trip: TripResponse) {
        Logging.d(TAG, "=== HANDLING RECEIVED TRIP ===")
        Logging.d(TAG, "Trip ID: ${trip.id}")
        Logging.d(TAG, "Status: ${trip.status}")
        Logging.d(TAG, "Departure time: ${trip.departure_time}")

        // Save as current trip
        currentTrip = trip
        
        // Update booking manager with trip
        bookingNfcManager.updateTrip(trip)
        
        // Sync with service (Activity takes precedence when active, but keep service in sync)
        syncActivityStateToService()

        // Countdown and navigation start logic is now handled by ActiveTripListener
        // This method just updates local state and syncs with service
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
        // App handles navigation state internally

        // Reset navigation started flag to allow route calculation
        if (allowResume) {
            Logging.d(TAG, "Resuming navigation - resetting navigation flags")
            // App handles navigation state internally
        }

        isNavigating.set(true)
        tripResponse = trip
        currentTrip = trip
        
        // Don't stop Service navigation sync here - let it continue until Activity's Navigator has an active route
        // The sync will automatically stop when Activity's RouteProgressListener becomes active
        // This prevents a gap in updates during route calculation
        
        // Update trip status to IN_PROGRESS when navigation starts or resumes
        // This ensures database always has correct status when navigation is active
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentStatus = trip.status
                if (!currentStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                    databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                    Logging.d(TAG, "Trip ${trip.id} status updated from $currentStatus to IN_PROGRESS (navigation ${if (allowResume) "resumed" else "started"})")
                    
                    // Update local trip status
                    withContext(Dispatchers.Main) {
                        currentTrip = trip.copy(status = "IN_PROGRESS")
                        tripResponse = tripResponse?.copy(status = "IN_PROGRESS")
                    }
                } else {
                    Logging.d(TAG, "Trip ${trip.id} already IN_PROGRESS (navigation ${if (allowResume) "resumed" else "started"})")
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
        bookingNfcManager.updatePassengerCounts()

        // Read settings from DB before starting navigation (not from memory)
        // This ensures we use the latest simulate value even if settings changed while Activity was paused
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val dbSettings = settingsManager.getSettings(vehicleId.toInt())
                if (dbSettings != null) {
                    withContext(Dispatchers.Main) {
                        currentSettings = dbSettings
                        Logging.d(TAG, "Settings read from DB before navigation start: simulate=${dbSettings.simulate}, devmode=${dbSettings.devmode}")
                    }
                } else {
                    Logging.w(TAG, "No settings found in DB before navigation start, using current settings")
                }
                
                // Start navigation after settings are read
                withContext(Dispatchers.Main) {
        Logging.d(TAG, "Calling startNavigation()...")
        startNavigation()
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to read settings from DB before navigation start: ${e.message}", e)
                // Still start navigation even if settings read fails
                withContext(Dispatchers.Main) {
                    Logging.d(TAG, "Calling startNavigation()... (settings read failed)")
                    startNavigation()
                }
            }
        }

        // Don't overwrite messageViewText here - RouteProgressListener will update it immediately
        // with navigation info (distance, speed, waypoint name) as soon as route progress starts
        foregroundService?.updateNotification("Navigating...")
    }


    private fun handleTripCancellation(cancelledTripId: Int) {
        Logging.d(TAG, "=== HANDLING TRIP CANCELLATION ===")
        Logging.d(TAG, "Cancelled Trip ID: $cancelledTripId")
        Logging.d(TAG, "Current Trip ID: ${currentTrip?.id}")
        Logging.d(TAG, "IsNavigating: ${isNavigating.get()}")

        // Check if the cancelled trip is our current trip
        if (currentTrip?.id == cancelledTripId) {
            Logging.d(TAG, "Current trip was cancelled - stopping all activities")

            // Stop navigation if active
            if (isNavigating.get()) {
                Logging.d(TAG, "Stopping active navigation")
                stopNavigationAndCleanup()
            }

            // Clear countdown
            countdownText = ""

            // Clear current trip
            currentTrip = null
            tripResponse = null

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
            // Clear cache when navigation stops
            lastKnownDistance = null
            lastKnownTime = null
            lastKnownWaypointName = null
            
            // Reset route calculation status
            isRouteCalculated = false
            
            // Stop navigation using App (same as HeadlessNavigActivity)
            app?.getNavigationExample()?.stopHeadlessNavigation()
            
            // Stop progress updates
            serviceNavigationSyncJob?.cancel()
            serviceNavigationSyncJob = null

            Logging.d(TAG, "Navigation stopped and cleaned up")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during navigation cleanup: ${e.message}", e)
        }
    }

    private fun onNavigationComplete() {
        Logging.d(TAG, "=== NAVIGATION COMPLETE ===")

        // Store trip ID before clearing to update status
        val completedTripId = currentTrip?.id

        // Clear cache when navigation completes
        lastKnownDistance = null
        lastKnownTime = null
        lastKnownWaypointName = null
        
        // Reset route calculation status
        isRouteCalculated = false

        isNavigating.set(false)
        currentTrip = null
        tripResponse = null

        // Update trip status to COMPLETED in database BEFORE silent fetch
        // This prevents silent fetch from finding the trip still "in_progress" and restarting navigation
        if (completedTripId != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Logging.d(TAG, "Updating trip $completedTripId status to COMPLETED in database")
                    databaseManager.updateTripStatus(completedTripId, "COMPLETED")
                    val completionTimestamp = System.currentTimeMillis()
                    databaseManager.updateTripCompletionTimestamp(completedTripId, completionTimestamp)
                    Logging.d(TAG, "Trip $completedTripId marked as COMPLETED with timestamp: $completionTimestamp")
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to update trip status to COMPLETED: ${e.message}", e)
                }
            }
        }

        // Read settings from DB on navigation completion (not from API)
        // Check devmode/deactivate/logout and route accordingly
        lifecycleScope.launch(Dispatchers.IO) {
            val vehicleId = vehicleSecurityManager.getVehicleId()
            try {
                Logging.d(TAG, "Reading settings from DB after navigation completion")
                val dbSettings = settingsManager.getSettings(vehicleId.toInt())
                
                if (dbSettings != null) {
                    withContext(Dispatchers.Main) {
                        currentSettings = dbSettings
                        Logging.d(TAG, "Settings read from DB after navigation: simulate=${dbSettings.simulate}, devmode=${dbSettings.devmode}, deactivate=${dbSettings.deactivate}, logout=${dbSettings.logout}")
                        
                        // PRIORITY 1: If logout or deactivate = true, immediately exit to LauncherActivity
                        if (dbSettings.logout || dbSettings.deactivate) {
                            Logging.w(TAG, "Logout or deactivate detected after navigation - immediately exiting to LauncherActivity")
                            exitToLauncherActivity()
                        }
                        // PRIORITY 2: If devmode = true, exit to LauncherActivity (devmode requires MainActivity)
                        else if (dbSettings.devmode) {
                            Logging.d(TAG, "devmode=true detected after navigation - exiting to LauncherActivity")
                            exitToLauncherActivity()
                        }
                        // PRIORITY 3: Otherwise, stay in AutoModeHeadlessActivity
                        else {
                            Logging.d(TAG, "Settings allow staying in AutoModeHeadlessActivity after navigation")
                            // Apply settings (check other settings)
                            settingsManager.applySettings(this@AutoModeHeadlessActivity, dbSettings)
                        }
                    }
                } else {
                    Logging.w(TAG, "No settings found in DB after navigation completion")
                    // Optionally fetch from API in background to update DB
                    launch(Dispatchers.IO) {
                        try {
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                result.onSuccess { settings ->
                                withContext(Dispatchers.Main) {
                    currentSettings = settings
                                    // Check settings after API fetch
                    if (settings.logout || settings.deactivate) {
                        exitToLauncherActivity()
                                    } else if (settings.devmode) {
                            exitToLauncherActivity()
                        }
                    }
                            }
                        } catch (e: Exception) {
                            Logging.e(TAG, "Exception fetching settings from API after navigation: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception reading settings from DB on trip completion: ${e.message}", e)
            }
        }

        // Reset to listening state
        messageViewText = "Auto Mode: Waiting for trip..."
        countdownText = ""
        foregroundService?.updateNotification("Auto Mode: Waiting for trip...")

        // Silent fetch after navigation completes
        // Wait a bit for trip status update to complete before fetching
        lifecycleScope.launch(Dispatchers.IO) {
            // Small delay to ensure trip status update completes
            delay(500)
            activeTripListener?.performSilentBackendFetch()
        }

        Logging.d(TAG, "Returned to listening state")
    }

    private fun initializeNavigationComponents() {
        try {
            Logging.d(TAG, "Initializing navigation components...")

            // Initialize App (same as HeadlessNavigActivity)
            // App creates its own TripSectionValidator internally
            initializeApp()

            // Set up MQTT service and callbacks on App's TripSectionValidator
            app?.getTripSectionValidator()?.let { validator ->
            // Initialize MQTT service in trip section validator
            mqttService?.let {
                    validator.initializeMqttService(it)
                    Logging.d(TAG, "MQTT service initialized in App's trip section validator")
            }
            
            // Set callback for trip deletion - when trip is not found in DB, transition to waiting state
                validator.setTripDeletedCallback { deletedTripId ->
                Logging.w(TAG, "ACTIVITY: Trip $deletedTripId deleted during navigation - transitioning to waiting state")
                lifecycleScope.launch {
                    handleTripDeletedDuringNavigation(deletedTripId)
                    }
                }
            }

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

            // App handles network state internally - no need to update RouteCalculator

            // App handles network state internally - no need to update NavigationHandler

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

        // App handles network state internally - no need to update RouteCalculator

        Logging.d(TAG, "Network monitoring started")
    }

    private fun initializeApp() {
        try {
            // Ensure HERE SDK is initialized before creating App
            if (SDKNativeEngine.getSharedInstance() == null) {
                Logging.e(TAG, "HERE SDK not initialized, initializing now...")
                initializeHERESDK()
            }

            // Create MessageViewUpdater for navigation updates
            messageViewUpdater = MessageViewUpdater()

            // Create App with null mapView for headless mode (same as HeadlessNavigActivity)
            app = App(applicationContext, null, messageViewUpdater!!, tripResponse)
            Logging.d(TAG, "App instance created (headless mode): $app")

            // Set up route calculation callback
            app?.setOnRouteCalculatedCallback {
                updateRouteCalculationStatus(true)
                // Update waypoint progress data when route is calculated
                updateWaypointProgressData()
                // Start progress updates only after route is calculated
                if (isNavigating.get()) {
                    startNavigationProgressUpdates()
                }
            }
            
            // Set TripSectionValidator in BookingNfcManager for waypoint name updates
            app?.getTripSectionValidator()?.let { validator ->
                bookingNfcManager.setTripSectionValidator(validator)
            }

            // Now that App is created, update it with trip data if available
            updateTripDataWhenReady()
        } catch (e: Exception) {
            Logging.e(TAG, "Error initializing App: ${e.message}", e)
            messageViewUpdater?.updateText("Error initializing navigation: ${e.message}")
        }
    }
    
    private fun updateTripDataWhenReady() {
        if (tripResponse != null && app != null) {
            Logging.d(TAG, "Updating App with trip data after both are ready")
            val simulate = currentSettings?.simulate ?: false
            app?.updateTripData(tripResponse, simulate)
        }
    }

    private fun startNavigation() {
        tripResponse?.let { trip ->
            // Clear cache when navigation starts
            lastKnownDistance = null
            lastKnownTime = null
            lastKnownWaypointName = null
            
            // Reset route calculation status
            isRouteCalculated = false
            
            // Get simulate value from settings
            val simulate = currentSettings?.simulate ?: false

            // Use App.updateTripData() to start navigation (same as HeadlessNavigActivity)
            // App handles route calculation and navigation start internally
            if (app != null) {
                Logging.d(TAG, "Starting navigation via App.updateTripData()")
                app?.updateTripData(trip, simulate)
                
                // Progress updates will start automatically when route calculation callback is invoked
                // (see initializeApp() route calculation callback)
            } else {
                Logging.e(TAG, "App not initialized, cannot start navigation")
            }
        }
    }
    
    /**
     * Updates route calculation status
     */
    fun updateRouteCalculationStatus(calculated: Boolean) {
        isRouteCalculated = calculated
        Logging.d(TAG, "Route calculation status updated: $calculated")
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
    
    // Update waypoint progress data from App's TripSectionValidator (match HeadlessNavigActivity pattern)
    private fun updateWaypointProgressData() {
        app?.getTripSectionValidator()?.let { validator ->
            val progress = validator.getCurrentWaypointProgress()
            
            // Apply cached values to items with null distance/time if waypoint matches
            val updatedProgress = progress.map { waypoint ->
                if (waypoint.isNext && 
                    waypoint.remainingDistanceInMeters == null && 
                    lastKnownDistance != null &&
                    extractWaypointName(waypoint.waypointName) == lastKnownWaypointName) {
                    // Apply cached values to next waypoint if it matches cached waypoint
                    waypoint.copy(
                        remainingDistanceInMeters = lastKnownDistance,
                        remainingTimeInSeconds = lastKnownTime
                    )
                } else {
                    waypoint
                }
            }
            
            waypointProgressData = updatedProgress
            
            // Update next waypoint name in booking manager from progress data (the one with isNext=true)
            // This ensures nextWaypointName in UI matches the green highlighted waypoint in overlay
            val nextWaypointProgress = updatedProgress.find { it.isNext } 
                ?: updatedProgress.firstOrNull { !it.isPassed }
            
            if (nextWaypointProgress != null) {
                val waypointName = extractWaypointName(nextWaypointProgress.waypointName)
                bookingNfcManager.nextWaypointName.value = waypointName
                Logging.d(TAG, "Updated nextWaypointName from progress data: $waypointName")
            }
        }
    }
    
    // Start periodic updates for waypoint progress data when overlay is visible (match HeadlessNavigActivity pattern)
    private fun startWaypointProgressUpdates() {
        lifecycleScope.launch {
            while (isActive && showWaypointOverlay) {
                updateWaypointProgressData()
                delay(2000) // Update every 2 seconds
            }
        }
    }
    
    // Update navigation display using App's progress data (same pattern as HeadlessNavigActivity)
    private fun updateNavigationDisplay() {
        if (!isNavigating.get()) return
        
        val waypointProgress = getProgressFromApp()
        val trip = tripResponse ?: return
        
        Logging.d(TAG, "updateNavigationDisplay: waypointProgress size=${waypointProgress.size}")
        
        // Get next waypoint info from progress - try multiple strategies
        var nextWaypoint = waypointProgress.find { it.isNext }
        
        // Fallback: if no waypoint has isNext=true, find first unpassed waypoint
        if (nextWaypoint == null && waypointProgress.isNotEmpty()) {
            nextWaypoint = waypointProgress.firstOrNull { !it.isPassed }
            Logging.d(TAG, "No waypoint with isNext=true, using first unpassed waypoint: ${nextWaypoint?.waypointName}")
        }
        
        val speedInKmh = getCurrentSpeedFromApp() * 3.6
        
        if (nextWaypoint != null) {
            val name = extractWaypointName(nextWaypoint.waypointName)
            
            // Update cache when valid data is available
            val distance = nextWaypoint.remainingDistanceInMeters
            val time = nextWaypoint.remainingTimeInSeconds
            
            if (distance != null && distance != Double.MAX_VALUE && distance > 0) {
                // Valid data - update cache and sync to Service
                lastKnownDistance = distance
                lastKnownTime = time
                lastKnownWaypointName = name
                
                // Sync cache to Service so Service can use it when Activity goes to background
                foregroundService?.syncCacheFromActivity(distance, time, name)
            } else if (lastKnownWaypointName == name && lastKnownDistance != null) {
                // Use cached values if waypoint hasn't changed
                Logging.d(TAG, "Using cached distance for waypoint: $name")
                
                // Sync cache to Service (even if using cached values)
                foregroundService?.syncCacheFromActivity(lastKnownDistance, lastKnownTime, name)
            } else {
                // Activity has null distance - check Service's cache before clearing
                val serviceCache = foregroundService?.getCacheForSync()
                val serviceDistance = serviceCache?.first
                val serviceTime = serviceCache?.second
                val serviceWaypointName = serviceCache?.third
                
                // If Service has valid cache for the same waypoint, use Service's cache
                if (serviceDistance != null && serviceWaypointName == name) {
                    Logging.d(TAG, "Activity has null distance, using Service's cache: distance=$serviceDistance, waypoint=$name")
                    lastKnownDistance = serviceDistance
                    lastKnownTime = serviceTime
                    lastKnownWaypointName = serviceWaypointName
                    
                    // Don't sync to Service - Service already has this cache
                } else {
                    // Both Activity and Service have null/cleared cache - clear Activity's cache
                    Logging.d(TAG, "Both Activity and Service have null cache, clearing Activity cache")
                    lastKnownDistance = null
                    lastKnownTime = null
                    lastKnownWaypointName = null
                    
                    // Don't sync null to Service - Service might have valid cache that we shouldn't overwrite
                    // Only sync if we're sure both are null (but we can't be sure, so don't sync)
                }
            }
            
            // Use current data if available, otherwise use cached data
            val displayDistance = distance ?: lastKnownDistance
            val displayTime = time ?: lastKnownTime
            
            Logging.d(TAG, "Next waypoint: $name, distance=$displayDistance, isNext=${nextWaypoint.isNext}")
            
            // Update waypoint name directly from progress data (the one with isNext=true)
            // This ensures nextWaypointName in UI matches the green highlighted waypoint in overlay
            bookingNfcManager.nextWaypointName.value = name
            
            // Also update waypoint progress data for overlay
            updateWaypointProgressData()
            
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
            
            messageViewText = navigationText
            
            // Activity always updates notification when active (Service will skip when Activity is active)
            foregroundService?.updateNotification(navigationText)
        } else {
            // No waypoint data available - show only speed
            val navigationText = "Speed: ${String.format("%.1f", speedInKmh)} km/h"
            messageViewText = navigationText
            foregroundService?.updateNotification(navigationText)
            Logging.d(TAG, "No waypoint progress data available, showing speed only")
        }
        
        // Check for completion - only if route is calculated and we have waypoint progress data
        if (isRouteCalculated && waypointProgress.isNotEmpty()) {
            val allWaypointsPassed = trip.waypoints.all { it.is_passed }
            val lastProgress = waypointProgress.lastOrNull()
            val remainingDistance = lastProgress?.remainingDistanceInMeters ?: 0.0
            
            if (allWaypointsPassed && remainingDistance <= 10.0) {
                isNavigating.set(false)
                messageViewText = "Auto Mode: Waiting for trip..."
                foregroundService?.updateNotification("Auto Mode: Waiting for trip...")
                onNavigationComplete()
            }
        } else if (!isRouteCalculated && waypointProgress.isEmpty()) {
            // Route not calculated yet - show calculating message
            messageViewText = "Calculating route..."
            foregroundService?.updateNotification("Calculating route...")
        }
    }
    
    // Get current speed from App's NavigationExample (same as HeadlessNavigActivity)
    private fun getCurrentSpeedFromApp(): Double {
        return app?.getNavigationExample()?.let { navExample ->
            try {
                // Get speed from NavigationHandler's continuously updated speed (updated via NavigableLocationListener)
                navExample.getNavigationHandler()?.currentSpeedInMetersPerSecond ?: 0.0
            } catch (e: Exception) {
                0.0
            }
        } ?: 0.0
    }
    
    // Navigation is handled by App internally - no need for startHeadlessGuidance
    // Set up periodic progress updates (same pattern as HeadlessNavigActivity waypoint overlay)
    private fun startNavigationProgressUpdates() {
        // Stop any existing update job
        serviceNavigationSyncJob?.cancel()
        
        // Start periodic updates to read progress from App's TripSectionValidator
        serviceNavigationSyncJob = lifecycleScope.launch {
            while (isActive && isNavigating.get()) {
                updateNavigationDisplay()
                bookingNfcManager.updatePassengerCounts()
                delay(2000) // Update every 2 seconds (same as HeadlessNavigActivity)
            }
        }
        
        // Start waypoint progress updates if overlay is visible
        if (showWaypointOverlay) {
            startWaypointProgressUpdates()
        }
    }
    
    // Navigation is handled by App internally - no listener wrapping needed
    // Progress updates are done via periodic polling (same as HeadlessNavigActivity waypoint overlay)

    // setupHeadlessListeners, handleRouteDeviation, and setupLocationSource removed
    // NavigationHandler and NavigationExample now handle all listener setup, route deviation, and location source

    // Booking and NFC functionality has been moved to BookingNfcManager
    // All booking/NFC methods removed - use bookingNfcManager instead

    private fun refreshTripDataFromDatabase() {
        tripResponse?.let { oldTrip ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val freshTrip = databaseManager.getTripById(oldTrip.id)
                    withContext(Dispatchers.Main) {
                        if (freshTrip != null) {
                            // If trip status changed to IN_PROGRESS, clear any confirmation state
                            val wasScheduled = oldTrip.status.equals("SCHEDULED", ignoreCase = true)
                            val nowInProgress = freshTrip.status.equals("IN_PROGRESS", ignoreCase = true)

                            if (wasScheduled && nowInProgress) {
                                Logging.d(TAG, "Trip status changed from SCHEDULED to IN_PROGRESS - starting navigation")

                                // Start navigation if not already navigating
                                if (!isNavigating.get()) {
                                    Logging.d(TAG, "Trip became IN_PROGRESS - starting navigation")
                                    startNavigationInternal(freshTrip, allowResume = true)
                                }
                            }

                            tripResponse = freshTrip
                            currentTrip = freshTrip
                            
                            // Update booking manager with fresh trip data
                            bookingNfcManager.updateTrip(freshTrip)
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
     * Gets the next waypoint info from TripSectionValidator
     * Returns null if trip is not in progress, no waypoint data available, or trip is completed
     * Prioritizes waypoint progress data over "under timer" check
     */


    private fun registerConfirmationReceiver() {
        try {
            confirmationReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Logging.d(TAG, "=== CONFIRMATION RECEIVER TRIGGERED ===")
                    Logging.d(TAG, "Received action: ${intent?.action}")
                    Logging.d(TAG, "Intent extras: ${intent?.extras}")

                    when (intent?.action) {
                        TripConfirmationReceiver.ACTION_START_CONFIRMED -> {
                            Logging.d(TAG, "Processing START_CONFIRMED action (confirmation removed, ignoring)")
                        }
                        TripConfirmationReceiver.ACTION_CANCEL_CONFIRMED -> {
                            Logging.d(TAG, "Processing CANCEL_CONFIRMED action (confirmation removed, ignoring)")
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
                            // Settings are already saved to DB by MQTT service
                            // Refresh settings from database and handle based on priority
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
                                        
                                        val wasNavigating = isNavigating.get()
                                        val simulateChanged = currentSettings?.simulate != it.simulate
                                        val devmodeChanged = currentSettings?.devmode != it.devmode
                                        
                                        // Update memory with new settings
                                        currentSettings = it
                                        Logging.d(TAG, "Settings updated from broadcast: simulate=${it.simulate}, devmode=${it.devmode}, deactivate=${it.deactivate}, logout=${it.logout}")

                                        // PRIORITY 1: If logout OR deactivate = true, immediately exit (even if navigating)
                                        if (it.logout || it.deactivate) {
                                            Logging.d(TAG, "Logout or deactivate detected from broadcast - immediately exiting to LauncherActivity")
                                            if (wasNavigating) {
                                            stopNavigationAndCleanup()
                                            }
                                            exitToLauncherActivity()
                                        }
                                        // PRIORITY 2: If simulate or devmode changed during navigation, continue navigation
                                        // New simulate value will be used on next navigation start
                                        // devmode will be checked on navigation completion
                                        else if (wasNavigating && (simulateChanged || devmodeChanged)) {
                                            Logging.d(TAG, "Settings changed during navigation (simulate=$simulateChanged, devmode=$devmodeChanged) - continuing navigation, will use new values on next start/completion")
                                            // Settings are already saved to DB by MQTT service
                                            // Just update memory and continue navigation
                                        }
                                        // PRIORITY 3: For other settings changes, only go back if NOT navigating
                                        else if (!wasNavigating) {
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
     * Validate if Activity's countdown is still valid
     * Checks: trip matches, departure time hasn't passed, countdown format is valid
     */
    private fun isCountdownValid(trip: TripResponse, countdownText: String): Boolean {
        try {
            // Check if countdown text matches expected format "Depart in: MM:SS"
            if (!countdownText.startsWith("Depart in: ")) {
                Logging.d(TAG, "Countdown text doesn't match expected format: $countdownText")
                return false
            }
            
            // Check if trip matches current trip
            if (currentTrip?.id != trip.id) {
                Logging.d(TAG, "Countdown trip ${trip.id} doesn't match current trip ${currentTrip?.id}")
                return false
            }
            
            // Check if departure time hasn't passed
            val departureTimeMillis = trip.departure_time * 1000
            val currentTime = System.currentTimeMillis()
            val twoMinutesInMs = 2 * 60 * 1000
            
            // Countdown should only be valid if we're more than 2 minutes before departure
            // (countdown starts 2 minutes before departure)
            if (currentTime >= departureTimeMillis - twoMinutesInMs) {
                Logging.d(TAG, "Countdown invalid - departure time has passed or within 2-minute window")
                return false
            }
            
            // Parse countdown text to extract remaining time
            val timePart = countdownText.removePrefix("Depart in: ").trim()
            val parts = timePart.split(":")
            if (parts.size != 2) {
                Logging.d(TAG, "Countdown text format invalid: $timePart")
                return false
            }
            
            val minutes = parts[0].toIntOrNull()
            val seconds = parts[1].toIntOrNull()
            
            if (minutes == null || seconds == null) {
                Logging.d(TAG, "Countdown time values invalid: minutes=$minutes, seconds=$seconds")
                return false
            }
            
            // Calculate remaining time from countdown text
            val remainingMs = (minutes * 60 + seconds) * 1000L
            val expectedRemainingMs = (departureTimeMillis - twoMinutesInMs) - currentTime
            
            // Allow some tolerance (within 5 seconds) for countdown accuracy
            val tolerance = 5000L
            if (kotlin.math.abs(remainingMs - expectedRemainingMs) > tolerance) {
                Logging.d(TAG, "Countdown time mismatch: countdown shows ${remainingMs}ms, expected ${expectedRemainingMs}ms")
                return false
            }
            
            Logging.d(TAG, "Countdown is valid: trip=${trip.id}, remaining=${remainingMs}ms")
            return true
        } catch (e: Exception) {
            Logging.e(TAG, "Error validating countdown: ${e.message}", e)
            return false
        }
    }

    
    /**
     * Sync Activity's countdown state to Service
     * Called when Activity goes to background to ensure Service continues countdown
     */
    private fun syncCountdownToService() {
        try {
            val trip = currentTrip
            if (trip == null || countdownText.isEmpty()) {
                Logging.d(TAG, "No active countdown to sync to Service")
                return
            }
            
            // Validate countdown is still valid
            if (!isCountdownValid(trip, countdownText)) {
                Logging.d(TAG, "Activity countdown is invalid, not syncing to Service")
                return
            }
            
            // Calculate remaining time
            val departureTimeMillis = trip.departure_time * 1000
            val currentTime = System.currentTimeMillis()
            val twoMinutesInMs = 2 * 60 * 1000
            val timeUntilDeparture = departureTimeMillis - currentTime
            val remainingMs = timeUntilDeparture - twoMinutesInMs
            
            if (remainingMs <= 0) {
                Logging.d(TAG, "Countdown time has passed, not syncing to Service")
                return
            }
            
            Logging.d(TAG, "Syncing countdown to Service: trip=${trip.id}, remaining=${remainingMs}ms, text=$countdownText")
            foregroundService?.syncCountdownFromActivity(trip, countdownText, remainingMs)
        } catch (e: Exception) {
            Logging.e(TAG, "Error syncing countdown to Service: ${e.message}", e)
        }
    }
    
    /**
     * Start periodic sync of navigation state from Service
     * Used when Service is navigating and Activity is active but Activity's Navigator is not active
     * This ensures Activity's UI and notification stay updated with Service's navigation progress
     */
    private fun startServiceNavigationSync() {
        // Stop any existing sync job
        stopServiceNavigationSync()
        
        // Start Service sync - Activity will use periodic polling for progress updates
        
        Logging.d(TAG, "Starting periodic Service navigation sync")
        
        serviceNavigationSyncJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && !isDestroyed) {
                delay(1000) // Update every 1 second (same frequency as RouteProgressListener)
                
                try {
                    val service = foregroundService
                    if (service == null) {
                        Logging.d(TAG, "Service not available for navigation sync")
                        continue
                    }
                    
                    // Only sync if Service is navigating and Activity is active
                    val serviceIsNavigating = service.isNavigatingForSync()
                    val serviceHasActiveRoute = service.hasActiveRouteForSync()
                    
                    if (!serviceIsNavigating && !serviceHasActiveRoute) {
                        // Service is no longer navigating - stop sync
                        Logging.d(TAG, "Service is no longer navigating - stopping sync")
                        stopServiceNavigationSync()
                        break
                    }
                    
                    // Check if Activity has started its own navigation (has active route)
                    // If Activity's Navigator is active, Activity's RouteProgressListener will handle updates
                    val activityHasActiveRoute = try {
                        val route = app?.getNavigationExample()?.getHeadlessNavigator()?.route
                        route != null
                    } catch (e: Exception) {
                        false
                    }
                    
                    if (activityHasActiveRoute) {
                        // Activity's Navigator is active - Activity's periodic polling will handle updates
                        // Stop Service sync since Activity is handling navigation
                        Logging.d(TAG, "Activity Navigator is active - stopping Service sync and starting Activity's own updates")
                        stopServiceNavigationSync()
                        // Start Activity's own navigation progress updates to ensure UI continues updating
                        startNavigationProgressUpdates()
                        break
                    }
                    
                    // Get current navigation text from Service
                    val serviceNavigationText = service.getCurrentNavigationTextForSync()
                    if (serviceNavigationText.isNotEmpty() && (serviceNavigationText.contains("Next:") || serviceNavigationText.contains("km/h"))) {
                        // Update Activity UI with Service's navigation text (distance/speed)
                        if (messageViewText != serviceNavigationText) {
                            messageViewText = serviceNavigationText
                            Logging.d(TAG, "ACTIVITY: Updated messageViewText from Service: $serviceNavigationText")
                        }
                        
                        // Update waypoint progress data and nextWaypointName from progress data if available
                        // This ensures waypoint name and overlay stay in sync even when using Service sync
                        // If Activity's App is initialized and has route data, use progress data
                        // Otherwise, fall back to trip data
                        updateWaypointProgressData()
                        
                        // Also update trip in booking manager for passenger counts
                        val trip = currentTrip
                        if (trip != null) {
                            bookingNfcManager.updateTrip(trip)
                        }
                        
                        // Update notification (Activity is active, so it should update notification)
                        foregroundService?.updateNotification(serviceNavigationText)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in Service navigation sync: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Stop periodic sync of navigation state from Service
     */
    private fun stopServiceNavigationSync() {
        serviceNavigationSyncJob?.cancel()
        serviceNavigationSyncJob = null
        Logging.d(TAG, "Stopped periodic Service navigation sync")
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
                isNavigating.set(false)
                countdownText = ""
                
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
                    countdownText = ""
                } else if (dbTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                    Logging.d(TAG, "New trip is SCHEDULED - triggering countdown/navigation check")
                    // Trigger ActiveTripListener to check departure time and start countdown/navigation
                    activeTripListener?.checkAndStartCountdownForTrip(dbTrip)
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
                    
                    // Clear countdown if active
                    countdownText = ""
                    
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
                countdownText = ""
                
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
                isNavigating.set(false)
                countdownText = ""
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
            val serviceHasActiveRoute = service.hasActiveRouteForSync()
            
            Logging.d(TAG, "Database trip: ${dbTrip?.id} (status: ${dbTrip?.status})")
            Logging.d(TAG, "Service trip: ${serviceTrip?.id} (status: ${serviceTrip?.status})")
            Logging.d(TAG, "Activity trip: ${currentTrip?.id} (status: ${currentTrip?.status})")
            Logging.d(TAG, "Service navigating: $serviceIsNavigating, Activity navigating: ${isNavigating.get()}")
            Logging.d(TAG, "Service has active route: $serviceHasActiveRoute")
            Logging.d(TAG, "Service countdown: $serviceCountdownText, Activity countdown: $countdownText")
            
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
                bookingNfcManager.updatePassengerCounts()
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
                
                // CRITICAL FIX: Check Service's Navigator route state, not Activity's Navigator
                // Activity and Service have separate NavigationExample instances, so Activity's Navigator
                // will always be null even if Service is navigating
                val serviceHasActiveRoute = service?.hasActiveRouteForSync() ?: false
                
                Logging.d(TAG, "Service navigation state check:")
                Logging.d(TAG, "  - serviceIsNavigating: $serviceIsNavigating")
                Logging.d(TAG, "  - serviceNavigatingSameTrip: $serviceNavigatingSameTrip")
                Logging.d(TAG, "  - serviceHasActiveRoute: $serviceHasActiveRoute")
                
                if (serviceNavigatingSameTrip || serviceHasActiveRoute) {
                    // Service is already navigating (either flag is set OR route is active) - just attach to it, don't restart
                    Logging.d(TAG, "Service is already navigating trip ${authoritativeTrip.id} (flag=${serviceIsNavigating}, route=${serviceHasActiveRoute}) - attaching to existing navigation")
                    isNavigating.set(true)
                    
                    // Clear conflicting state
                    countdownText = ""
                    
                    // Update UI immediately with current navigation state from Service
                    // Get current navigation text from Service (contains distance, speed)
                    val serviceNavigationText = service?.getCurrentNavigationTextForSync() ?: ""
                    if (serviceNavigationText.isNotEmpty() && (serviceNavigationText.contains("Next:") || serviceNavigationText.contains("km/h"))) {
                        // Service has live navigation data - use it
                        messageViewText = serviceNavigationText
                        Logging.d(TAG, "Using Service navigation text: $serviceNavigationText")
                    } else {
                        // Fallback to static message if Service text not available yet
                        val origin = authoritativeTrip.route.origin.custom_name ?: authoritativeTrip.route.origin.google_place_name
                        messageViewText = "Navigating: $origin"
                        Logging.d(TAG, "Using fallback navigation text: Navigating: $origin")
                    }
                    
                    // Update booking manager with trip (this updates passenger counts and waypoint name)
                    bookingNfcManager.updateTrip(authoritativeTrip)
                    
                    // Don't restart navigation - Service is already handling it
                    // Start periodic sync from Service to update Activity UI and notification
                    startServiceNavigationSync()
                    Logging.d(TAG, "Attached to existing Service navigation - UI updated with current state, periodic sync started")
                } else {
                    // Service is not navigating AND has no active route - start navigation
                    Logging.d(TAG, "Navigation should be active (database shows IN_PROGRESS) but Service is not navigating - starting navigation recovery")
                    Logging.d(TAG, "  - Service navigating flag: $serviceIsNavigating")
                    Logging.d(TAG, "  - Service has active route: $serviceHasActiveRoute")
                    isNavigating.set(true)
                    
                    // Clear conflicting state
                    countdownText = ""
                    
                    // Start navigation recovery - Service's Navigator doesn't have a route
                    Logging.d(TAG, "Starting navigation recovery from database state (Service Navigator has no active route)")
                    startNavigationInternal(authoritativeTrip, allowResume = true)
                }
            } else if (!shouldBeNavigating && isNavigating.get()) {
                // Navigation is active but trip status is not IN_PROGRESS
                // This means status update failed or didn't complete - fix it by updating status
                Logging.w(TAG, "Navigation is active but trip is not IN_PROGRESS - updating status to IN_PROGRESS")
                
                authoritativeTrip?.let { trip ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                            Logging.d(TAG, "Trip ${trip.id} status updated to IN_PROGRESS (navigation was active but status was ${trip.status})")
                            
                            // Update local trip status
                            withContext(Dispatchers.Main) {
                                currentTrip = trip.copy(status = "IN_PROGRESS")
                                tripResponse = tripResponse?.copy(status = "IN_PROGRESS")
                            }
                        } catch (e: Exception) {
                            Logging.e(TAG, "Failed to update trip status to IN_PROGRESS: ${e.message}", e)
                            // If update fails, navigation is still active, so don't stop it
                        }
                    }
                }
            }
            
            // Sync countdown state - only if trip is SCHEDULED and not IN_PROGRESS
            if (!shouldBeNavigating && authoritativeTrip.status.equals("SCHEDULED", ignoreCase = true)) {
                // Countdown is now handled by ActiveTripListener
                // Just sync the countdown text from Service if it's different
                if (serviceCountdownText.isNotEmpty() && countdownText != serviceCountdownText) {
                    Logging.d(TAG, "Syncing countdown text from Service: $serviceCountdownText")
                    countdownText = serviceCountdownText
                } else if (countdownText.isEmpty() && serviceCountdownText.isEmpty()) {
                    // Neither Activity nor Service has countdown - trigger countdown check
                    // This ensures countdown starts if trip was created while Activity was paused
                    Logging.d(TAG, "No countdown active - triggering countdown check for trip ${authoritativeTrip.id}")
                    activeTripListener?.checkAndStartCountdownForTrip(authoritativeTrip)
                } else if (countdownText.isEmpty() && serviceCountdownText.isNotEmpty()) {
                    // Service has countdown but Activity doesn't - sync and ensure Activity's countdown is running
                    Logging.d(TAG, "Service has countdown but Activity doesn't - syncing and triggering countdown check")
                    countdownText = serviceCountdownText
                    activeTripListener?.checkAndStartCountdownForTrip(authoritativeTrip)
                } else if (countdownText.isNotEmpty() && serviceCountdownText.isEmpty()) {
                    // Activity has countdown but Service doesn't - clear Activity countdown
                    Logging.d(TAG, "Activity has countdown but Service doesn't - clearing Activity countdown")
                    countdownText = ""
                }
            }
            
            // Confirmation logic removed - ActiveTripListener handles countdown and navigation start
            
            // Update passenger counts
            bookingNfcManager.updatePassengerCounts()
            
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
        
        // Sync cache to Service when Activity resumes (so Service can use it if Activity goes to background)
        if (isNavigating.get() && lastKnownDistance != null) {
            foregroundService?.syncCacheFromActivity(lastKnownDistance, lastKnownTime, lastKnownWaypointName)
            Logging.d(TAG, "Synced cache to Service on resume: distance=$lastKnownDistance, waypoint=$lastKnownWaypointName")
        }

        // PRIORITY 0: Quick synchronous check of Service state to update UI immediately
        // This prevents showing "waiting" when Service is actually navigating
        val service = foregroundService
        if (service != null) {
            val serviceIsNavigating = service.isNavigatingForSync()
            val serviceTrip = service.getCurrentTripForSync()
            val serviceHasActiveRoute = service.hasActiveRouteForSync()
            
            Logging.d(TAG, "onResume - Quick Service check (synchronous):")
            Logging.d(TAG, "  - serviceIsNavigating: $serviceIsNavigating")
            Logging.d(TAG, "  - serviceTrip: ${serviceTrip?.id}")
            Logging.d(TAG, "  - serviceHasActiveRoute: $serviceHasActiveRoute")
            
            // If Service is navigating, immediately update Activity state to prevent "waiting" UI
            if (serviceIsNavigating || serviceHasActiveRoute) {
                val trip = serviceTrip ?: currentTrip
                if (trip != null && trip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                    Logging.d(TAG, "Service is navigating - immediately updating Activity state to prevent 'waiting' UI")
                    isNavigating.set(true)
                    currentTrip = trip
                    tripResponse = trip
                    
                    // Update UI immediately with current navigation state from Service
                    val serviceNavigationText = service.getCurrentNavigationTextForSync()
                    if (serviceNavigationText.isNotEmpty() && (serviceNavigationText.contains("Next:") || serviceNavigationText.contains("km/h"))) {
                        // Service has live navigation data - use it
                        messageViewText = serviceNavigationText
                        Logging.d(TAG, "Using Service navigation text on resume: $serviceNavigationText")
                    } else {
                        // Fallback to static message if Service text not available yet
                        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                        messageViewText = "Navigating: $origin"
                    }
                    
                    // Update booking manager with trip
                    bookingNfcManager.updateTrip(trip)
                    
                    // Check if Activity Navigator has an active route
                    // If Activity Navigator is active, use Activity's own updates instead of Service sync
                    val activityHasActiveRoute = try {
                        val route = app?.getNavigationExample()?.getHeadlessNavigator()?.route
                        route != null
                    } catch (e: Exception) {
                        false
                    }
                    
                    if (activityHasActiveRoute) {
                        // Activity Navigator is active - use Activity's own updates
                        Logging.d(TAG, "Activity Navigator is active on resume - starting Activity's own updates")
                        // Update waypoint progress data to populate overlay if navigation was started in background
                        updateWaypointProgressData()
                        startNavigationProgressUpdates()
                    } else {
                        // Activity Navigator not active - use Service sync
                        Logging.d(TAG, "Activity Navigator not active on resume - starting Service sync")
                        // Update waypoint progress data to populate overlay if navigation was started in background
                        updateWaypointProgressData()
                        startServiceNavigationSync()
                    }
                } else if (serviceIsNavigating || serviceHasActiveRoute) {
                    // Service is navigating but trip status is not IN_PROGRESS (might be SCHEDULED with countdown)
                    // Still update waypoint progress data if Service has active route
                    if (serviceHasActiveRoute) {
                        Logging.d(TAG, "Service has active route on resume - updating waypoint progress data")
                        updateWaypointProgressData()
                    }
                }
            }
        }

        // Notify booking manager of resume
        bookingNfcManager.onActivityResume(this)

        // Always verify MQTT connection and re-register callbacks
        // This ensures MQTT is connected and callbacks are active whenever Activity is resumed
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Small delay to ensure MQTT service state is stable
                delay(300)
                
                // Check MQTT service health and connection
                val mqttServiceInstance = mqttService
                if (mqttServiceInstance != null && !isDestroyed) {
                    // MQTT service exists - check if it's healthy (initialized and active)
                    val isHealthy = mqttServiceInstance.isServiceActive()
                    val isConnected = mqttServiceInstance.isConnected()
                    
                    Logging.d(TAG, "MQTT health check on resume: healthy=$isHealthy, connected=$isConnected")
                    
                    if (isHealthy) {
                        // MQTT service is healthy - ensure it's connected
                        if (!isConnected) {
                            Logging.d(TAG, "MQTT service is healthy but not connected, triggering reconnection...")
                            mqttServiceInstance.onAppForeground() // This will trigger reconnection if needed
                            // Wait a bit for connection attempt
                            delay(1000)
                        }
                        
                        // MQTT trip callbacks are now handled by ActiveTripListener
                        // Booking bundle callback is handled by BookingNfcManager
                        Logging.d(TAG, "MQTT service verified on resume (service healthy, connected=${mqttServiceInstance.isConnected()})")
                    } else {
                        Logging.d(TAG, "MQTT service not healthy (inactive), skipping callback registration")
                    }
                } else {
                    Logging.d(TAG, "MQTT service not available, skipping callback registration")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error checking MQTT health and re-registering callbacks: ${e.message}", e)
            }
        }

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
            countdownText.isEmpty() && 
            currentTrip == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                activeTripListener?.performSilentBackendFetch()
            }
        }

        // Notify MQTT service that app is in foreground
        mqttService?.onAppForeground()

        // NFC reader is managed by BookingNfcManager
        // No need to handle it here
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
                    val serviceHasActiveRoute = foregroundService?.hasActiveRouteForSync() ?: false

                    Logging.d(TAG, "onNewIntent - Service navigation state check:")
                    Logging.d(TAG, "  - serviceIsNavigating: $serviceIsNavigating")
                    Logging.d(TAG, "  - serviceNavigatingSameTrip: $serviceNavigatingSameTrip")
                    Logging.d(TAG, "  - serviceHasActiveRoute: $serviceHasActiveRoute")

                    // If Service is navigating and trip is IN_PROGRESS, attach to existing navigation
                    if (activeTrip.status.equals("IN_PROGRESS", ignoreCase = true) && (serviceNavigatingSameTrip || serviceHasActiveRoute)) {
                        Logging.d(TAG, "Service is already navigating trip ${activeTrip.id} (flag=${serviceIsNavigating}, route=${serviceHasActiveRoute}) - attaching to existing navigation (not restarting)")
                        
                        // Sync state from Service to attach to existing navigation
                        currentTrip = activeTrip
                        tripResponse = activeTrip
                        isNavigating.set(true)
                        
                        // Update UI immediately with current navigation state from Service
                        val serviceNavigationText = foregroundService?.getCurrentNavigationTextForSync() ?: ""
                        if (serviceNavigationText.isNotEmpty() && (serviceNavigationText.contains("Next:") || serviceNavigationText.contains("km/h"))) {
                            messageViewText = serviceNavigationText
                            Logging.d(TAG, "Using Service navigation text on new intent: $serviceNavigationText")
                        } else {
                            val origin = activeTrip.route.origin.custom_name ?: activeTrip.route.origin.google_place_name
                            messageViewText = "Navigating: $origin"
                        }
                        
                        // Update booking manager with trip (this updates passenger counts and waypoint name)
                        bookingNfcManager.updateTrip(activeTrip)
                        
                        // Sync full state from Service
                        syncStateFromService(activeTrip)
                        
                        // Start periodic sync from Service to keep UI and notification updated
                        startServiceNavigationSync()
                        
                        // Don't call handleTripReceived - it would restart navigation
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
        
        // Sync countdown to Service before pausing (so Service can continue it in background)
        syncCountdownToService()
        
        // Stop Service navigation sync when Activity is paused
        stopServiceNavigationSync()

        // Notify MQTT service that app is in background
        mqttService?.onAppBackground()

        // Notify booking manager of pause
        bookingNfcManager.onActivityPause(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        isDestroyed = true
        Logging.d(TAG, "=== AutoModeHeadlessActivity DESTROY STARTED ===")

        // Mark activity as inactive
        isActivityActive.set(false)

        // Stop ActiveTripListener
        activeTripListener?.stop()
        activeTripListener = null
        
        // Stop Service navigation sync
        stopServiceNavigationSync()

        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null

        // CRITICAL: Stop navigation and location services FIRST before disposing SDK
        // This prevents service connection leaks from HERE SDK's internal services
        // Use App.detach() to clean up (same as HeadlessNavigActivity)
        app?.detach()
        app = null

        // Clean up booking and NFC manager
        bookingNfcManager.cleanup()

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

        // MQTT trip callbacks are now handled by ActiveTripListener
        // Booking bundle callback is handled by BookingNfcManager.cleanup()
        Logging.d(TAG, "MQTT trip callbacks are handled by ActiveTripListener")

        // Stop periodic trip data refresh
        tripRefreshHandler.removeCallbacks(tripRefreshRunnable)

        handler.removeCallbacksAndMessages(null)

        // Clean up map downloader
        mapDownloaderManager?.cleanup()

        // CRITICAL: Wait for HERE SDK service connections to unbind before disposing SDK
        // This prevents ServiceConnectionLeaked errors
        try {
            Logging.d(TAG, "Waiting for HERE SDK service connections to unbind...")
            Thread.sleep(500) // 500ms grace period for service connections to unbind
            Logging.d(TAG, "Grace period completed")
        } catch (e: InterruptedException) {
            Logging.w(TAG, "Interrupted while waiting for service cleanup: ${e.message}")
        }

        // Unbind and stop foreground service BEFORE disposing SDK
        // This ensures Service's LocationEngine is stopped before SDK disposal
        try {
            Logging.d(TAG, "Stopping AutoModeHeadlessForegroundService...")
            
            // Unbind from service first (if bound)
            if (foregroundService != null) {
                unbindService(serviceConnection)
                Logging.d(TAG, "Service unbound")
            }

            // Stop the service explicitly (even if not bound, to ensure cleanup)
            val serviceIntent = Intent(this, AutoModeHeadlessForegroundService::class.java)
            stopService(serviceIntent)
            Logging.d(TAG, "Service stop requested")

            // Wait for service to fully stop its LocationEngine
            // This is critical to allow HERE SDK's internal service connections to unbind
            try {
                Thread.sleep(300) // 300ms for service cleanup
                Logging.d(TAG, "Service cleanup grace period completed")
            } catch (e: InterruptedException) {
                Logging.w(TAG, "Interrupted while waiting for service cleanup: ${e.message}")
            }

            // Null out the reference
            foregroundService = null

            Logging.d(TAG, "AutoModeHeadlessForegroundService stopped and unbound")
        } catch (e: Exception) {
            Logging.e(TAG, "Error stopping foreground service: ${e.message}", e)
            foregroundService = null
        }

        // Dispose HERE SDK only after all services are stopped and connections are unbound
        // NOTE: Service will re-initialize SDK if needed (it's START_STICKY and may restart)
        disposeHERESDK()
        
        Logging.d(TAG, "=== AutoModeHeadlessActivity DESTROY COMPLETE ===")
    }

    private fun disposeHERESDK() {
        // Free HERE SDK resources before the application shuts down.
        // Usually, this should be called only on application termination.
        // Afterwards, the HERE SDK is no longer usable unless it is initialized again.
        try {
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                Logging.d(TAG, "Disposing HERE SDK...")
                
                // Additional cleanup to ensure all service connections are released
                // NOTE: LocationEngine should already be stopped by NavigationExample cleanup
                try {
                    // Verify no LocationEngine instances are running
                    // (This is defensive - should already be handled by NavigationExample)
                    Logging.d(TAG, "Verifying location services are stopped before SDK disposal")
                } catch (e: Exception) {
                    Logging.w(TAG, "Error verifying location services: ${e.message}")
                }
                
                // Dispose the SDK
                sdkNativeEngine.dispose()
                Logging.d(TAG, "HERE SDK disposed successfully")
                
                // For safety reasons, we explicitly set the shared instance to null to avoid situations,
                // where a disposed instance is accidentally reused.
                SDKNativeEngine.setSharedInstance(null)
                Logging.d(TAG, "HERE SDK shared instance cleared")
            } else {
                Logging.d(TAG, "HERE SDK not initialized, nothing to dispose")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error disposing HERE SDK: ${e.message}", e)
        }
    }
}


