package com.gocavgo.validator

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.VectorDrawable
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.util.fastForEach
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripStatus
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.trip.TripProgressTracker
import com.gocavgo.validator.nfc.NFCReaderHelper
import com.here.sdk.core.Anchor2D
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.LanguageCode
import com.here.sdk.core.Location
import com.here.sdk.core.LocationListener
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.location.LocationEngine
import com.here.sdk.location.LocationEngineStatus
import com.here.sdk.location.LocationFeature
import com.here.sdk.location.LocationStatusListener
import com.here.sdk.maploader.DownloadRegionsStatusListener
import com.here.sdk.maploader.DownloadableRegionsCallback
import com.here.sdk.maploader.MapDownloader
import com.here.sdk.maploader.MapDownloaderTask
import com.here.sdk.maploader.MapLoaderError
import com.here.sdk.maploader.MapLoaderException
import com.here.sdk.maploader.PersistentMapStatus
import com.here.sdk.maploader.PersistentMapRepairError
import com.here.sdk.maploader.Region
import com.here.sdk.maploader.RegionId
import com.here.sdk.mapview.ImageFormat
import com.here.sdk.mapview.LineCap
import com.here.sdk.mapview.MapImage
import com.here.sdk.mapview.MapImageFactory
import com.here.sdk.mapview.MapMarker
import com.here.sdk.mapview.MapMeasureDependentRenderSize
import com.here.sdk.mapview.MapPolyline
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import com.here.sdk.mapview.RenderSize
import com.here.sdk.navigation.DestinationReachedListener
import com.here.sdk.navigation.DistanceType
import com.here.sdk.navigation.DynamicCameraBehavior
import com.here.sdk.navigation.LocationSimulator
import com.here.sdk.navigation.LocationSimulatorOptions
import com.here.sdk.navigation.Milestone
import com.here.sdk.navigation.MilestoneStatus
import com.here.sdk.navigation.MilestoneStatusListener
import com.here.sdk.navigation.NavigableLocation
import com.here.sdk.navigation.NavigableLocationListener
import com.here.sdk.navigation.Navigator
import com.here.sdk.navigation.RouteDeviation
import com.here.sdk.navigation.RouteDeviationListener
import com.here.sdk.navigation.RouteProgress
import com.here.sdk.navigation.RouteProgressListener
import com.here.sdk.navigation.SafetyCameraWarningListener
import com.here.sdk.navigation.SpeedLimit
import com.here.sdk.navigation.SpeedLimitListener
import com.here.sdk.navigation.SpeedWarningListener
import com.here.sdk.navigation.SpeedWarningStatus
import com.here.sdk.navigation.VisualNavigator
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.RefreshRouteOptions
import com.here.sdk.routing.Route
import com.here.sdk.routing.RouteHandle
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.RoutingError
import com.here.sdk.routing.RoutingInterface
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngineOptions
import com.here.sdk.trafficawarenavigation.DynamicRoutingListener
import com.here.sdk.transport.TransportMode
import com.here.time.Duration
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import androidx.core.graphics.toColorInt
import com.gocavgo.validator.dataclass.SavePlaceResponse
import com.gocavgo.validator.dataclass.TripWaypoint
import com.here.sdk.maploader.SDKCache
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class Navigator : AppCompatActivity() {

    companion object {
        private const val TAG = "Navigator"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_SHOW_MAP = "show_map"
        const val EXTRA_IS_SIMULATED = "is_simulated"
        const val EXTRA_NETWORK_CONNECTED = "network_connected"
        const val EXTRA_CONNECTION_TYPE = "connection_type"
        const val EXTRA_IS_METERED = "is_metered"
    }

    private var mapView: MapView? = null
    private var visualNavigator: VisualNavigator? = null
    private var navigator: Navigator? = null
    private var locationSimulator: LocationSimulator? = null
    private var locationEngine: LocationEngine? = null
    private val handler = Handler(Looper.getMainLooper())
    private var offlineSwitchRunnable: Runnable? = null

    // Speed display UI elements
    private var speedValueTextView: android.widget.TextView? = null
    private var speedUnitTextView: android.widget.TextView? = null
    private var speedAccuracyTextView: android.widget.TextView? = null
    private var speedDisplayContainer: android.widget.LinearLayout? = null

    // Speed tracking variables
    private var currentSpeedKmh = 0.0
    private var speedAccuracy = 0.0
    private var lastSpeedUpdateTime = 0L
    private val SPEED_UPDATE_INTERVAL = 1000L // Update every 1 second

    private var onlineRoutingEngine: RoutingEngine? = null
    private var offlineRoutingEngine: OfflineRoutingEngine? = null
    private var dynamicRoutingEngine: DynamicRoutingEngine? = null
    private var mapDownloader: MapDownloader? = null
    private var showMap: Boolean = false
    private var isSimulated: Boolean = true
    private val waypointMarkers = mutableListOf<MapMarker>()
    private var routeMapPolyline: MapPolyline? = null

    // Map data management
    private var downloadableRegions = mutableListOf<Region>()
    private val mapDownloaderTasks = mutableListOf<MapDownloaderTask>()

    private var useDynamicRouting: Boolean = false
    private var currentRoute: Route? = null
    private var isMapDataReady = false
    
    // Map download management
    private var rwandaRegion: Region? = null
    private var downloadRetryCount = 0
    private val MAX_DOWNLOAD_RETRIES = 3
    private val DOWNLOAD_RETRY_DELAY_MS = 5000L // 5 seconds
    private var lastUpdateCheckTime = 0L
    private val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private var isDownloadInProgress = false
    private var downloadProgressCallback: ((Int) -> Unit)? = null
    
    // Map repair management
    private var isRepairInProgress = false
    private var repairRetryCount = 0
    private val MAX_REPAIR_RETRIES = 2
    private val REPAIR_RETRY_DELAY_MS = 3000L // 3 seconds

    // Trip data
    private var tripResponse: TripResponse? = null
    private lateinit var databaseManager: DatabaseManager
    private var tripProgressTracker: TripProgressTracker? = null
    private var mqttService: MqttService? = null

    private var networkMonitor: NetworkMonitor? = null
    private var isNetworkConnected = false
    private var currentConnectionType = "UNKNOWN"
    private var isConnectionMetered = true
    private var currentUserLocation: Location? = null
    private val markerImageCache = mutableMapOf<Int, MapImage>()

    // Headless mode UI elements
    private var currentLocationText: android.widget.TextView? = null
    private var waypointsContainer: android.widget.LinearLayout? = null
    private var validationStatusText: android.widget.TextView? = null
    
    // Custom numeric keyboard elements
    private var inputDisplay: android.widget.TextView? = null
    private var currentInput = ""
    private val maxDigits = 6
    private var isValidationInProgress = false

    // NFC functionality
    private var nfcReaderHelper: NFCReaderHelper? = null
    private val recentBookings = mutableListOf<BookingRecord>()
    private var isDestroyed = false
    private var currentBookingDialog: BookingConfirmationDialog? = null
    
    // Map download progress dialog
    private var mapDownloadDialog: MapDownloadProgressDialog? = null

    // Booking data class
    data class BookingRecord(
        val nfcId: String,
        val waypointName: String,
        val timestamp: Long,
        val price: Double
    )

    // Camera behavior toggle state
    private var isCameraBehaviorEnabled = true

    // Navigation state management
    private var isNavigationStarted = false

    // Route deviation handling
    private var isReturningToRoute = false
    private var deviationCounter = 0
    private val DEVIATION_THRESHOLD_METERS = 50
    private val MIN_DEVIATION_EVENTS = 3

    // Receiver for booking bundle saved events
    private var bookingBundleReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Prevent soft keyboard from appearing
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)


        // Initialize database manager
        databaseManager = DatabaseManager.getInstance(this)

        // Get trip ID and map visibility from intent
        val tripId = intent.getIntExtra(EXTRA_TRIP_ID, -1)
        showMap = intent.getBooleanExtra(EXTRA_SHOW_MAP, false)
        isSimulated = intent.getBooleanExtra(EXTRA_IS_SIMULATED, true)

        // Get network status from intent extras
        isNetworkConnected = intent.getBooleanExtra(EXTRA_NETWORK_CONNECTED, false)
        currentConnectionType = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: "UNKNOWN"
        isConnectionMetered = intent.getBooleanExtra(EXTRA_IS_METERED, true)

        Log.d(TAG, "=== NETWORK STATUS FROM MAINACTIVITY ===")
        Log.d(TAG, "Connected: $isNetworkConnected")
        Log.d(TAG, "Type: $currentConnectionType")
        Log.d(TAG, "Metered: $isConnectionMetered")
        Log.d(TAG, "=====================================")

        if (tripId == -1) {
            Log.e(TAG, "No trip ID provided. Cannot start navigation.")
            finish()
            return
        }

        // Fetch trip from database
        lifecycleScope.launch {
            tripResponse = databaseManager.getTripById(tripId)
            if (tripResponse == null) {
                Log.e(TAG, "Trip with ID $tripId not found in database. Cannot start navigation.")
                finish()
                return@launch
            }

            // Get MQTT service instance
            mqttService = MqttService.getInstance()

            // Initialize trip progress tracker with MQTT service
            tripProgressTracker = TripProgressTracker(tripId, databaseManager, lifecycleScope, this@Navigator, mqttService)

            logTripInfo()
            initializeNetworkMonitoring()
            initializeHERESDK()
            setupUI(savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }

            initializeComponents()

            // Initialize NFC reader after trip data is loaded (for headless mode)
            if (!showMap) {
                initializeNFCReader()
                Log.d(TAG, "NFC reader initialized after trip data loaded")
            }

            // Register receiver for booking bundle saved overlay
            registerBookingBundleReceiver()
            
            // Also register direct callback with MQTT service for more reliable notifications
            registerMqttBookingBundleCallback()
        }
    }

    private fun logTripInfo() {
        tripResponse?.let { trip ->
            Log.d(TAG, "=== TRIP INFORMATION ===")
            Log.d(TAG, "Trip ID: ${trip.id}")
            Log.d(TAG, "Vehicle: ${trip.vehicle.license_plate}")
            Log.d(TAG, "Driver: ${trip.vehicle.driver?.name ?: "No driver assigned"}")
            Log.d(TAG, "Status: ${trip.status}")
            Log.d(TAG, "Seats: ${trip.seats}")
            Log.d(TAG, "Origin: ${trip.route.origin.google_place_name}")
            Log.d(TAG, "Destination: ${trip.route.destination.google_place_name}")
            Log.d(TAG, "Waypoints: ${trip.waypoints.size}")
            Log.d(TAG, "Custom waypoints: ${trip.has_custom_waypoints}")
            Log.d(TAG, "Connection mode: ${trip.connection_mode}")
            Log.d(TAG, "Network Status: Connected=$isNetworkConnected, Type=$currentConnectionType, Metered=$isConnectionMetered")
            trip.waypoints.forEachIndexed { index, waypoint ->
                Log.d(TAG, "  Waypoint ${index + 1}: ${waypoint.location.google_place_name} (Order: ${waypoint.order})")
            }
            Log.d(TAG, "=======================")
        }
    }

    private fun initializeNetworkMonitoring() {
        Log.d(TAG, "=== INITIALIZING NETWORK MONITORING ===")
        Log.d(TAG, "Using network status from MainActivity:")
        Log.d(TAG, "  Connected: $isNetworkConnected")
        Log.d(TAG, "  Type: $currentConnectionType")
        Log.d(TAG, "  Metered: $isConnectionMetered")

        // Use the received network status instead of re-scanning
        recommendRoutingStrategy(isNetworkConnected, isConnectionMetered)

        // Set initial routing mode based on initial network status from MainActivity
        val initialUseDynamic = getNetworkBasedRoutingRecommendation()
        if (useDynamicRouting != initialUseDynamic) {
            useDynamicRouting = initialUseDynamic
            Log.d(TAG, "Initial routing mode set to: ${if (useDynamicRouting) "Dynamic (online)" else "Offline"}")
        } else {
            Log.d(TAG, "Initial routing mode remains: ${if (useDynamicRouting) "Dynamic (online)" else "Offline"}")
        }

        // Set up network monitoring for future changes only
        networkMonitor = NetworkMonitor(this) { connected, type, metered ->
            Log.d(TAG, "=== NETWORK STATE CHANGED ===")
            Log.d(TAG, "Previous: Connected=$isNetworkConnected, Type=$currentConnectionType, Metered=$isConnectionMetered")
            Log.d(TAG, "New: Connected=$connected, Type=$type, Metered=$metered")

            // Only handle changes, not initial setup
            if (connected != isNetworkConnected || type != currentConnectionType || metered != isConnectionMetered) {
                isNetworkConnected = connected
                currentConnectionType = type
                isConnectionMetered = metered
                handleNetworkStateChange(connected, type, metered)
            } else {
                Log.d(TAG, "No actual network change detected, ignoring")
            }
            Log.d(TAG, "==============================")
        }

        networkMonitor?.startMonitoring()
        Log.d(TAG, "Network monitoring started in Navigator for future changes")
    }

    private fun getNetworkBasedRoutingRecommendation(): Boolean {
        return when {
            !isNetworkConnected -> false
            currentConnectionType == "WIFI" && !isConnectionMetered -> true
            currentConnectionType.startsWith("CELLULAR") && isConnectionMetered -> false
            currentConnectionType.startsWith("CELLULAR") && !isConnectionMetered -> true
            else -> isNetworkConnected
        }
    }

    private fun recommendRoutingStrategy(internetAvailable: Boolean, isMetered: Boolean) {
        Log.d(TAG, "=== ROUTING STRATEGY RECOMMENDATION ===")

        val recommendation = when {
            !internetAvailable -> {
                "OFFLINE ROUTING ONLY - No internet connection detected"
            }
            isMetered -> {
                "HYBRID ROUTING RECOMMENDED - Connection is metered, prefer offline with dynamic fallback"
            }
            else -> {
                "DYNAMIC ROUTING OPTIMAL - Fast unmetered connection available"
            }
        }

        Log.d(TAG, "Recommendation: $recommendation")
        Log.d(TAG, "======================================")
    }

    private fun handleNetworkStateChange(connected: Boolean, type: String, metered: Boolean) {
        Log.d(TAG, "=== HANDLING NETWORK CHANGE ===")

        offlineSwitchRunnable?.let {
            handler.removeCallbacks(it)
            Log.d(TAG, "Cancelled pending offline switch task")
        }
        offlineSwitchRunnable = null

        when {
            !connected -> {
                Log.w(TAG, "Lost network connection - switching to offline mode")
                if (useDynamicRouting) {
                    Log.d(TAG, "Auto-switching from dynamic to offline routing")

                    offlineSwitchRunnable = Runnable {
                        if (!isNetworkConnected) {
                            toggleRoutingMode(false)
                        }
                        offlineSwitchRunnable = null
                    }

                    handler.postDelayed(offlineSwitchRunnable!!, 120000)
                    Log.d(TAG, "Scheduled offline switch in 2 minutes")
                }
                dynamicRoutingEngine?.stop()
            }

            connected && type == "CELLULAR" && metered -> {
                Log.w(TAG, "On metered cellular connection - using offline routing to save data")
                if (useDynamicRouting) {
                    Log.d(TAG, "Switching to offline routing to conserve data")
                    toggleRoutingMode(false)
                }
            }

            connected && type == "WIFI" && !metered -> {
                Log.d(TAG, "Fast WiFi connection available - dynamic routing optimal")
                if (!useDynamicRouting && currentRoute != null) {
                    Log.d(TAG, "Switching to dynamic routing for better traffic awareness")
                    toggleRoutingMode(true)
                }
                
                // Check for map updates on WiFi
                if (!isMapDataReady && rwandaRegion != null) {
                    Log.d(TAG, "WiFi available, checking for map updates")
                    checkForMapUpdatesOnNetworkAvailable()
                }
            }

            connected && type.startsWith("CELLULAR") && !metered -> {
                Log.d(TAG, "Unlimited cellular connection - dynamic routing available")
                if (!useDynamicRouting && currentRoute != null) {
                    toggleRoutingMode(true)
                }
            }
        }

        Log.d(TAG, "==============================")
    }

    private fun initializeHERESDK(lowMEm: Boolean = false) {
        val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID
        val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET
        val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
        val options = SDKOptions(authenticationMode)
        if(lowMEm) {
            options.lowMemoryMode = true
            Log.d(TAG, "Initialised in Low memory mode")
        }
        try {
            SDKNativeEngine.makeSharedInstance(this, options)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of HERE SDK failed: " + e.error.name)
        }
    }

    private fun setupUI(savedInstanceState: Bundle?) {
        if (showMap) {
            setContentView(R.layout.activity_navigator)
            mapView = findViewById(R.id.mapView)
            mapView?.onCreate(savedInstanceState)

            // Initialize speed display UI elements
            initializeSpeedDisplay()
        } else {
            setContentView(R.layout.activity_navigator_headless)
            initializeHeadlessUI()
        }
    }

    private fun initializeSpeedDisplay() {
        try {
            speedValueTextView = findViewById(R.id.speedValue)
            speedUnitTextView = findViewById(R.id.speedUnit)
            speedAccuracyTextView = findViewById(R.id.speedAccuracy)
            speedDisplayContainer = findViewById(R.id.speedDisplayContainer)

            // Initialize with default values
            updateSpeedDisplay(0.0, 0.0)

            Log.d(TAG, "=== SPEED DISPLAY INITIALIZED ===")
            Log.d(TAG, "Speed display UI elements found and initialized")
            Log.d(TAG, "Speed display will be updated from location data")
            Log.d(TAG, "===============================")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speed display UI: ${e.message}", e)
        }
    }

    private fun initializeHeadlessUI() {
        try {
            // Initialize UI elements
            currentLocationText = findViewById(R.id.currentLocationText)
            waypointsContainer = findViewById(R.id.waypointsContainer)
            validationStatusText = findViewById(R.id.validationStatusText)
            
            // Initialize custom numeric keyboard
            initializeCustomNumericKeyboard()

            // Update UI with trip information first
            updateWaypointsDisplay()

            // Initialize NFC reader only after trip data is loaded
            if (tripResponse != null) {
                initializeNFCReader()
                Log.d(TAG, "=== HEADLESS UI INITIALIZED ===")
                Log.d(TAG, "Headless mode UI elements initialized successfully")
                Log.d(TAG, "Custom numeric keyboard initialized")
                Log.d(TAG, "NFC reader ready for card scanning")
                Log.d(TAG, "Trip data loaded: ${tripResponse?.id}")
            } else {
                Log.w(TAG, "Trip data not yet loaded, NFC reader will be initialized later")
            }
            Log.d(TAG, "===============================")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize headless UI: ${e.message}", e)
        }
    }

    private fun initializeCustomNumericKeyboard() {
        try {
            // Initialize input display
            inputDisplay = findViewById(R.id.inputDisplay)
            currentInput = ""
            updateInputDisplay()

            // Disable external keyboard
            disableExternalKeyboard()

            // Set up numeric button listeners
            setupNumericButtonListeners()

            Log.d(TAG, "Custom numeric keyboard initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize custom numeric keyboard: ${e.message}", e)
        }
    }

    private fun setupNumericButtonListeners() {
        // Number buttons (0-9)
        val numberButtons = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        numberButtons.forEachIndexed { index, buttonId ->
            findViewById<android.widget.Button>(buttonId)?.setOnClickListener {
                addDigit(index.toString())
            }
        }

        // Action buttons
        findViewById<android.widget.Button>(R.id.btnClear)?.setOnClickListener {
            forceClearInput()
        }

        findViewById<android.widget.Button>(R.id.btnDelete)?.setOnClickListener {
            if (currentInput.isNotEmpty()) {
                deleteLastDigit()
            } else {
                forceClearInput()
            }
        }
    }

    private fun addDigit(digit: String) {
        if (isValidationInProgress) {
            Log.d(TAG, "Validation in progress, ignoring input: $digit")
            return
        }
        
        if (currentInput.length < maxDigits) {
            currentInput += digit
            updateInputDisplay()
            Log.d(TAG, "Added digit: $digit, Current input: $currentInput")
            
            // Check if we have 6 digits
            if (currentInput.length == maxDigits) {
                Log.d(TAG, "=== 6-DIGIT CODE COMPLETE ===")
                Log.d(TAG, "Complete code: $currentInput")
                Log.d(TAG, "=============================")
                
                // Validate ticket with the 6-digit code
                validateTicketByNumber(currentInput)
            }
        } else {
            Log.d(TAG, "Maximum digits reached, ignoring input: $digit")
        }
    }

    private fun deleteLastDigit() {
        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
            updateInputDisplay()
            Log.d(TAG, "Deleted last digit, Current input: $currentInput")
        } else {
            Log.d(TAG, "No digits to delete")
        }
    }

    private fun clearInput() {
        currentInput = ""
        resetInputDisplay()
        Log.d(TAG, "Input cleared")
    }

    /**
     * Force clear input and reset display (used during validation)
     */
    private fun forceClearInput() {
        currentInput = ""
        isValidationInProgress = false
        resetInputDisplay()
        Log.d(TAG, "Input force cleared")
    }

    /**
     * Reset input display to normal state
     */
    private fun resetInputDisplay() {
        inputDisplay?.let { display ->
            display.text = currentInput
            display.setTextColor("#000000".toColorInt()) // Black color for normal state
            display.background = ContextCompat.getDrawable(this, R.drawable.input_field_background)
            // Reset text size to normal
            display.textSize = 24f
        }
    }

    private fun updateInputDisplay() {
        inputDisplay?.text = currentInput
    }

    /**
     * Validate ticket by 6-digit ticket number
     */
    private fun validateTicketByNumber(ticketNumber: String) {
        Log.d(TAG, "=== VALIDATING TICKET ===")
        Log.d(TAG, "Ticket Number: $ticketNumber")
        
        // Set validation flag
        isValidationInProgress = true
        
        // Show loading state
        showTicketValidationLoading()
        
        lifecycleScope.launch {
            try {
                val ticket = databaseManager.getTicketByNumber(ticketNumber)
                
                handler.post {
                    if (ticket != null) {
                        Log.d(TAG, "=== TICKET FOUND ===")
                        Log.d(TAG, "Ticket ID: ${ticket.id}")
                        Log.d(TAG, "Booking ID: ${ticket.booking_id}")
                        Log.d(TAG, "Ticket Number: ${ticket.ticket_number}")
                        Log.d(TAG, "Is Used: ${ticket.is_used}")
                        Log.d(TAG, "From: ${ticket.pickup_location_name}")
                        Log.d(TAG, "To: ${ticket.dropoff_location_name}")
                        Log.d(TAG, "Car Plate: ${ticket.car_plate}")
                        Log.d(TAG, "Car Company: ${ticket.car_company}")
                        Log.d(TAG, "Pickup Time: ${ticket.pickup_time}")
                        Log.d(TAG, "==================")
                        
                        showTicketValidationSuccess(ticket)
                    } else {
                        Log.d(TAG, "=== TICKET NOT FOUND ===")
                        Log.d(TAG, "No ticket found with number: $ticketNumber")
                        Log.d(TAG, "=========================")
                        
                        showTicketValidationError("Invalid ticket number")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error validating ticket: ${e.message}", e)
                handler.post {
                    showTicketValidationError("Error validating ticket: ${e.message}")
                }
            }
        }
    }

    /**
     * Show loading state during ticket validation
     */
    private fun showTicketValidationLoading() {
        // Show loading in input field
        inputDisplay?.let { display ->
            "Validating...".also { display.text = it }
            display.setTextColor("#FFFFFF".toColorInt()) // White text for better visibility
            display.background = ContextCompat.getDrawable(this, R.drawable.input_field_loading_background)
            // Use normal text size since we reduced padding
            display.textSize = 22f
        }
        
        // Show loading status in top section
        validationStatusText?.let { statusText ->
            "Validating...".also { statusText.text = it }
            statusText.setTextColor("#FF8C00".toColorInt()) // Orange color for loading
            statusText.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * Show success state when ticket is found
     */
    private fun showTicketValidationSuccess(ticket: com.gocavgo.validator.database.TicketEntity) {
        // Clear input field and reset to normal state
        inputDisplay?.let { display ->
            display.text = ""
            display.setTextColor("#000000".toColorInt()) // Black color for normal state
            display.background = ContextCompat.getDrawable(this, R.drawable.input_field_background)
            display.textSize = 24f
        }
        
        // Show success status in top section
        validationStatusText?.let { statusText ->
            "✓ VALID TICKET".also { statusText.text = it }
            statusText.setTextColor("#00AA00".toColorInt()) // Green color for success
            statusText.visibility = android.view.View.VISIBLE
        }
        
        // Log ticket details (like NFC validation)
        logTicketDetails(ticket)
        
        // Reset input after showing success (no dialog)
        handler.postDelayed({
            isValidationInProgress = false
            clearInput()
            hideValidationStatus()
        }, 3000) // Clear after 3 seconds
    }

    /**
     * Show error state when ticket is not found
     */
    private fun showTicketValidationError(errorMessage: String) {
        // Clear input field and reset to normal state
        inputDisplay?.let { display ->
            display.text = errorMessage
            display.setTextColor("#000000".toColorInt()) // Black color for normal state
            display.background = ContextCompat.getDrawable(this, R.drawable.input_field_background)
            display.textSize = 24f
        }
        
        // Show error status in top section
        validationStatusText?.let { statusText ->
            "❌ INVALID TICKET".also { statusText.text = it }
            statusText.setTextColor("#FF0000".toColorInt()) // Red color for error
            statusText.visibility = android.view.View.VISIBLE
        }
        
        // Reset input after showing error (no dialog)
        handler.postDelayed({
            isValidationInProgress = false
            clearInput()
            hideValidationStatus()
        }, 3000) // Clear after 3 seconds
    }

    /**
     * Hide validation status text
     */
    private fun hideValidationStatus() {
        validationStatusText?.visibility = android.view.View.GONE
    }

    /**
     * Log ticket details (like NFC validation)
     */
    private fun logTicketDetails(ticket: com.gocavgo.validator.database.TicketEntity) {
        try {
            Log.d(TAG, "=== TICKET VALIDATION SUCCESS ===")
            Log.d(TAG, "🎫 TICKET DETAILS")
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━")
            Log.d(TAG, "Ticket Number: ${ticket.ticket_number}")
            Log.d(TAG, "From: ${ticket.pickup_location_name}")
            Log.d(TAG, "To: ${ticket.dropoff_location_name}")
            Log.d(TAG, "Car Plate: ${ticket.car_plate}")
            Log.d(TAG, "Car Company: ${ticket.car_company}")
            Log.d(TAG, "Status: ${if (ticket.is_used) "USED" else "VALID"}")
            if (ticket.used_at != null) {
                val usedTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(ticket.used_at))
                Log.d(TAG, "Used At: $usedTime")
            }
            if (ticket.validated_by != null) {
                Log.d(TAG, "Validated By: ${ticket.validated_by}")
            }
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━")
            Log.d(TAG, "===============================")
                
        } catch (e: Exception) {
            Log.e(TAG, "Error logging ticket details: ${e.message}", e)
        }
    }

    /**
     * Disable external keyboard to ensure only custom numeric keyboard is used
     */
    private fun disableExternalKeyboard() {
        try {
            // Hide the input display from external keyboard focus
            inputDisplay?.isFocusable = false
            inputDisplay?.isFocusableInTouchMode = false
            inputDisplay?.isClickable = false
            
            // Disable soft keyboard
            inputDisplay?.setOnClickListener {
                // Do nothing - prevent external keyboard from appearing
            }
            
            Log.d(TAG, "External keyboard disabled, only custom numeric keyboard available")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling external keyboard: ${e.message}", e)
        }
    }

    private fun initializeNFCReader() {
        try {
            nfcReaderHelper = NFCReaderHelper(
                context = this,
                onTagRead = { tag ->
                    handleNFCTagRead(tag)
                },
                onError = { error ->
                    Log.e(TAG, "NFC Error: $error")
                }
            )

            if (nfcReaderHelper?.isNfcSupported() == true) {
                if (nfcReaderHelper?.isNfcEnabled() == true) {
                    nfcReaderHelper?.enableNfcReader(this)
                    Log.d(TAG, "NFC reader enabled and ready")
                } else {
                    Log.w(TAG, "NFC is disabled. Please enable NFC in settings.")
                }
            } else {
                Log.w(TAG, "NFC not supported on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NFC reader: ${e.message}", e)
        }
    }

    private fun handleNFCTagRead(tag: android.nfc.Tag) {
        try {
            val nfcId = tag.id.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "=== NFC TAG READ ===")
            Log.d(TAG, "NFC ID: $nfcId")
            Log.d(TAG, "Activity destroyed: $isDestroyed")
            Log.d(TAG, "Trip response null: ${tripResponse == null}")

            // Check if activity is destroyed before processing
            if (isDestroyed) {
                Log.w(TAG, "Activity is destroyed, ignoring NFC tag read")
                return
            }

            // Check if tripResponse is available
            if (tripResponse == null) {
                Log.e(TAG, "Trip response is null! Cannot show destinations.")
                Log.e(TAG, "This means trip data hasn't been loaded yet.")
                return
            }

            Log.d(TAG, "Trip response available: ${tripResponse?.id}")
            Log.d(TAG, "Waypoints count: ${tripResponse?.waypoints?.size}")
            Log.d(TAG, "Current thread: ${Thread.currentThread().name}")

            // Check for existing booking first
            Log.d(TAG, "Checking for existing booking...")
            lifecycleScope.launch {
                try {
                    val existingBookingResult = databaseManager.getExistingBookingByNfcTag(nfcId)

                    handler.post {
                        if (!isDestroyed) {
                            when (existingBookingResult) {
                                is com.gocavgo.validator.service.ExistingBookingResult.Found -> {
                                    Log.d(TAG, "Found existing booking, showing ticket display")
                                    showExistingTicketDialog(existingBookingResult.booking, existingBookingResult.payment, existingBookingResult.ticket)
                                }
                                is com.gocavgo.validator.service.ExistingBookingResult.NotFound -> {
                                    Log.d(TAG, "No existing booking found, showing destination selection")
                                    // If there's already a booking confirmation dialog, handle new booking request
                                    if (currentBookingDialog != null) {
                                        Log.d(TAG, "Booking confirmation active, handling new booking request")
                                        currentBookingDialog?.handleNewBookingRequest()
                                        currentBookingDialog = null
                                    }

                                    Log.d(TAG, "Calling showWaypointSelectionDialog...")
                                    showWaypointSelectionDialog(nfcId)
                                }
                                is com.gocavgo.validator.service.ExistingBookingResult.Error -> {
                                    Log.e(TAG, "Error checking existing booking: ${existingBookingResult.message}")
                                    // Show error and proceed with normal flow
                                    showBookingError("Error checking existing booking: ${existingBookingResult.message}")
                                }
                            }
                        } else {
                            Log.w(TAG, "Activity destroyed, not showing dialog")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking existing booking: ${e.message}", e)
                    handler.post {
                        if (!isDestroyed) {
                            showBookingError("Error checking existing booking: ${e.message}")
                        }
                    }
                }
            }
            Log.d(TAG, "==================")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling NFC tag: ${e.message}", e)
        }
    }

    private fun updateWaypointsDisplay() {
        tripResponse?.let { trip ->
            waypointsContainer?.removeAllViews()

            // Get available destinations (waypoints + final destination)
            val availableDestinations = getAvailableWaypoints()

            if (availableDestinations.isEmpty()) {
                val noWaypointsText = android.widget.TextView(this).apply {
                    "No available destinations".also { text = it }
                    textSize = 16f
                    setTextColor("#666666".toColorInt())
                    gravity = android.view.Gravity.CENTER
                }
                waypointsContainer?.addView(noWaypointsText)
            } else {
                availableDestinations.forEach { destination ->
                    val waypointView = createWaypointView(destination)
                    waypointsContainer?.addView(waypointView)
                }
            }
        }
    }


    private fun getCurrentLocationName(): String {
        tripResponse?.let { trip ->
            // Find the last passed waypoint to determine current location
            val passedWaypoints = trip.waypoints.filter { it.is_passed }.sortedBy { it.order }

            return when {
                passedWaypoints.isEmpty() -> getLocationDisplayName(trip.route.origin)
                passedWaypoints.size == trip.waypoints.size -> getLocationDisplayName(trip.route.destination)
                else -> getLocationDisplayName(passedWaypoints.last().location)
            }
        }
        return "Unknown"
    }

    private fun getLocationDisplayName(location: SavePlaceResponse): String {
        return location.custom_name?.takeIf { it.isNotBlank() } ?: location.google_place_name
    }

    private fun getAvailableWaypoints(): List<AvailableDestination> {
        tripResponse?.let { trip ->
            val allWaypoints = trip.waypoints
            val availableWaypoints = allWaypoints.filter { !it.is_passed }.sortedBy { it.order }
            val passedWaypoints = allWaypoints.filter { it.is_passed }

            Log.d(TAG, "=== DEBUGGING AVAILABLE DESTINATIONS ===")
            Log.d(TAG, "Total waypoints: ${allWaypoints.size}")
            Log.d(TAG, "Passed waypoints: ${passedWaypoints.size}")
            Log.d(TAG, "Available waypoints: ${availableWaypoints.size}")
            allWaypoints.forEach { wp ->
                val displayName = getLocationDisplayName(wp.location)
                Log.d(TAG, "  Waypoint: $displayName (passed: ${wp.is_passed}, order: ${wp.order})")
            }

            // Create a list that includes both waypoints and final destination
            val availableDestinations = mutableListOf<AvailableDestination>()

            // Add intermediate waypoints
            availableWaypoints.forEach { waypoint ->
                availableDestinations.add(
                    AvailableDestination(
                        id = waypoint.id,
                        location = waypoint.location,
                        price = waypoint.price,
                        order = waypoint.order,
                        isFinalDestination = false
                    )
                )
            }

            // Always add final destination (it should always be available for booking)
            val finalDestinationPrice = calculateFinalDestinationPrice()
            availableDestinations.add(
                AvailableDestination(
                    id = -1, // Special ID for final destination
                    location = trip.route.destination,
                    price = finalDestinationPrice,
                    order = Int.MAX_VALUE, // Always last
                    isFinalDestination = true
                )
            )

            Log.d(TAG, "Final available destinations: ${availableDestinations.size}")
            availableDestinations.forEach { dest ->
                val displayName = getLocationDisplayName(dest.location)
                Log.d(TAG, "  - $displayName (final: ${dest.isFinalDestination}, price: ${dest.price})")
            }
            Log.d(TAG, "=====================================")

            return availableDestinations
        }
        Log.w(TAG, "No trip response available for waypoints")
        return emptyList()
    }

    private fun calculateFinalDestinationPrice(): Double {
        tripResponse?.let { trip ->
            // Calculate trip price based on current location
            val currentLocation = getCurrentLocationName()
            val origin = getLocationDisplayName(trip.route.origin)
            val allWaypoints = trip.waypoints.sortedBy { it.order }
            val currentWaypointIndex = getCurrentWaypointIndex()

            return when {
                // If we're at origin, use the last waypoint's price as full trip price
                currentLocation == origin -> {
                    val lastWaypoint = allWaypoints.maxByOrNull { it.order }
                    lastWaypoint?.price ?: 1000.0
                }

                // If we're at an intermediate waypoint, calculate segment price to final destination
                currentWaypointIndex >= 0 -> {
                    val currentWaypoint = allWaypoints[currentWaypointIndex]
                    val lastWaypoint = allWaypoints.maxByOrNull { it.order }
                    val segmentPrice = (lastWaypoint?.price ?: 1000.0) - currentWaypoint.price
                    maxOf(0.0, segmentPrice)
                }

                // Fallback to default trip price
                else -> 1000.0
            }
        }
        return 1000.0
    }

    // Data class for available destinations (both waypoints and final destination)
    data class AvailableDestination(
        val id: Int,
        val location: SavePlaceResponse,
        val price: Double,
        val order: Int,
        val isFinalDestination: Boolean
    )

    private fun createWaypointView(destination: AvailableDestination): android.widget.LinearLayout {
        val inflater = layoutInflater
        val waypointView = inflater.inflate(R.layout.waypoint_item, waypointsContainer, false) as android.widget.LinearLayout

        val nameText = waypointView.findViewById<android.widget.TextView>(R.id.waypointName)
        val priceText = waypointView.findViewById<android.widget.TextView>(R.id.waypointPrice)
        val distanceText = waypointView.findViewById<android.widget.TextView>(R.id.waypointDistance)
        val statusText = waypointView.findViewById<android.widget.TextView>(R.id.waypointStatus)

        // Set waypoint name with flag icon
        val destinationDisplayName = getLocationDisplayName(destination.location)
        val flagIcon = if (destination.isFinalDestination) "🏁" else "🚩"
        "$flagIcon $destinationDisplayName".also { nameText.text = it }

        // Calculate and display price
        val price = calculateDestinationPrice(destination)
        priceText.text = price

        // Set distance information
        distanceText.text = if (destination.isFinalDestination) {
            "Final Destination"
        } else {
            "Distance: Unknown" // We don't have distance for final destination
        }

        // Set status
        statusText.text = if (destination.isFinalDestination) "Final" else "Available"

        // Remove click functionality - waypoints are only bookable via NFC card tap
        waypointView.isClickable = false
        waypointView.isFocusable = false

        return waypointView
    }


    private fun calculateDestinationPrice(destination: AvailableDestination): String {
        tripResponse?.let { trip ->
            val currentLocation = getCurrentLocationName()
            val origin = getLocationDisplayName(trip.route.origin)

            // Get all waypoints sorted by order
            val allWaypoints = trip.waypoints.sortedBy { it.order }
            val currentWaypointIndex = getCurrentWaypointIndex()

            return when {
                // If we're at origin, show full price from origin to this destination
                currentLocation == origin -> {
                    "${destination.price.toInt()} RWF"
                }

                // If we're at an intermediate waypoint, calculate segment price
                currentWaypointIndex >= 0 && !destination.isFinalDestination -> {
                    val currentWaypoint = allWaypoints[currentWaypointIndex]
                    val segmentPrice = destination.price - currentWaypoint.price
                    "${maxOf(0.0, segmentPrice).toInt()} RWF"
                }

                // For final destination, use the trip price
                destination.isFinalDestination -> {
                    "${destination.price.toInt()} RWF"
                }

                // Fallback to destination's base price
                else -> "${destination.price.toInt()} RWF"
            }
        }

        return "${destination.price.toInt()} RWF"
    }

    private fun getCurrentWaypointIndex(): Int {
        tripResponse?.let { trip ->
            val allWaypoints = trip.waypoints.sortedBy { it.order }

            // Find the last passed waypoint index
            val passedWaypoints = allWaypoints.filter { it.is_passed }
            return if (passedWaypoints.isEmpty()) {
                -1 // At origin
            } else {
                val lastPassedWaypoint = passedWaypoints.maxByOrNull { it.order }
                allWaypoints.indexOf(lastPassedWaypoint)
            }
        }
        return -1
    }

    private fun showWaypointSelectionDialog(nfcId: String) {
        Log.d(TAG, "=== SHOWING CUSTOM DESTINATION SELECTION DIALOG ===")
        Log.d(TAG, "NFC ID: $nfcId")

        val availableDestinations = getAvailableWaypoints()

        Log.d(TAG, "Available destinations count: ${availableDestinations.size}")
        availableDestinations.forEach { dest ->
            val displayName = getLocationDisplayName(dest.location)
            Log.d(TAG, "  - $displayName (final: ${dest.isFinalDestination})")
        }

        if (availableDestinations.isEmpty()) {
            Log.w(TAG, "No available destinations found!")
            android.app.AlertDialog.Builder(this)
                .setTitle("No Available Destinations")
                .setMessage("All waypoints have been passed. Trip is complete.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val currentLocation = getCurrentLocationName()

        try {
            // Create and show custom dialog
            val dialog = DestinationSelectionDialog.newInstance(
                nfcId = nfcId,
                currentLocation = currentLocation,
                destinations = availableDestinations
            ) { selectedDestination ->
                Log.d(TAG, "User selected destination: ${getLocationDisplayName(selectedDestination.location)}")
                processBooking(nfcId, selectedDestination)
            }

            dialog.show(supportFragmentManager, "DestinationSelectionDialog")
            Log.d(TAG, "Custom dialog shown successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing custom dialog: ${e.message}", e)
            // Fallback: show a simple message
            android.app.AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Unable to show destinations. Please try again.")
                .setPositiveButton("OK", null)
                .show()
        }
    }


    private fun processBooking(nfcId: String, destination: AvailableDestination) {
        try {
            val destinationDisplayName = getLocationDisplayName(destination.location)
            val currentLocation = getCurrentLocationName()
            val priceText = calculateDestinationPrice(destination)

            // Create booking record for recent bookings list
            val booking = BookingRecord(
                nfcId = nfcId,
                waypointName = destinationDisplayName,
                timestamp = System.currentTimeMillis(),
                price = destination.price
            )

            recentBookings.add(0, booking) // Add to beginning of list
            if (recentBookings.size > 10) {
                recentBookings.removeAt(recentBookings.size - 1) // Keep only last 10
            }

            Log.d(TAG, "=== BOOKING PROCESSED ===")
            Log.d(TAG, "NFC ID: $nfcId")
            Log.d(TAG, "From: $currentLocation")
            Log.d(TAG, "To: $destinationDisplayName")
            Log.d(TAG, "Price: $priceText")
            Log.d(TAG, "Is Final Destination: ${destination.isFinalDestination}")
            Log.d(TAG, "========================")

            // Create complete booking with payment and ticket in database
            createCompleteBooking(nfcId, destination, currentLocation, destinationDisplayName, priceText)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing booking: ${e.message}", e)
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
            lifecycleScope.launch {
                try {
                    val result = databaseManager.createBookingWithPaymentAndTicket(
                        tripId = trip.id,
                        nfcId = nfcId,
                        fromLocation = currentLocation,
                        toLocation = destinationDisplayName,
                        price = destination.price,
                        userPhone = "NFC_USER",
                        userName = "NFC Card User"
                    )

                    when (result) {
                        is com.gocavgo.validator.service.BookingCreationResult.Success -> {
                            Log.d(TAG, "=== COMPLETE BOOKING CREATED ===")
                            Log.d(TAG, "Booking ID: ${result.bookingId}")
                            Log.d(TAG, "Payment ID: ${result.paymentId}")
                            Log.d(TAG, "Ticket ID: ${result.ticketId}")
                            Log.d(TAG, "Ticket Number: ${result.ticketNumber}")
                            Log.d(TAG, "QR Code: ${result.qrCode}")
                            Log.d(TAG, "===============================")

                            // Show booking confirmation dialog with ticket details
                            showBookingConfirmationWithTicket(
                                nfcId,
                                currentLocation,
                                destinationDisplayName,
                                priceText,
                                result.ticketNumber,
                                result.qrCode
                            )
                        }
                        is com.gocavgo.validator.service.BookingCreationResult.Error -> {
                            Log.e(TAG, "Failed to create complete booking: ${result.message}")
                            // Show error dialog
                            showBookingError("Failed to create booking: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating complete booking: ${e.message}", e)
                    showBookingError("Error creating booking: ${e.message}")
                }
            }
        } ?: run {
            Log.e(TAG, "No trip response available for booking creation")
            showBookingError("No trip data available")
        }
    }


    private fun showBookingConfirmationWithTicket(
        nfcId: String,
        fromLocation: String,
        toLocation: String,
        price: String,
        ticketNumber: String,
        qrCode: String
    ) {
        try {
            // Dismiss any existing booking dialog
            currentBookingDialog?.dismiss()

            // Create and show new booking confirmation dialog with ticket details
            currentBookingDialog = BookingConfirmationDialog.newInstance(
                nfcId = nfcId,
                fromLocation = fromLocation,
                toLocation = toLocation,
                price = price,
                ticketNumber = ticketNumber,
                qrCode = qrCode,
                onNewBookingRequested = {
                    // This will be called when a new NFC card is tapped during confirmation
                    Log.d(TAG, "New booking requested during confirmation")
                }
            )

            currentBookingDialog?.show(supportFragmentManager, "BookingConfirmationDialog")
            Log.d(TAG, "Booking confirmation dialog with ticket shown")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing booking confirmation with ticket: ${e.message}", e)
        }
    }

    private fun showBookingError(errorMessage: String) {
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("Booking Error")
                .setMessage(errorMessage)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing booking error dialog: ${e.message}", e)
        }
    }

    private fun showExistingTicketDialog(
        booking: com.gocavgo.validator.database.BookingEntity,
        payment: com.gocavgo.validator.database.PaymentEntity,
        ticket: com.gocavgo.validator.database.TicketEntity
    ) {
        try {
            // Dismiss any existing booking dialog
            currentBookingDialog?.dismiss()

            // Create and show ticket display dialog
            val ticketDialog = TicketDisplayDialog.newInstance(
                booking = booking,
                payment = payment,
                ticket = ticket,
                onNewBookingRequested = {
                    // This will be called when a new NFC card is tapped during ticket display
                    Log.d(TAG, "New booking requested during ticket display")
                }
            )

            ticketDialog.show(supportFragmentManager, "TicketDisplayDialog")
            Log.d(TAG, "Existing ticket dialog shown")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing existing ticket dialog: ${e.message}", e)
        }
    }


    private fun updateSpeedFromLocation(location: Location) {
        val currentTime = System.currentTimeMillis()

        // Throttle updates to avoid UI spam
        if (currentTime - lastSpeedUpdateTime < SPEED_UPDATE_INTERVAL) {
            return
        }

        lastSpeedUpdateTime = currentTime

        val speed = location.speedInMetersPerSecond ?: 0.0
        val accuracy = location.speedAccuracyInMetersPerSecond ?: 0.0

        updateSpeedDisplay(speed, accuracy)
    }

    @SuppressLint("DefaultLocale")
    private fun updateSpeedDisplay(speedInMetersPerSecond: Double, accuracyInMetersPerSecond: Double) {
        try {
            // Convert m/s to km/h
            currentSpeedKmh = speedInMetersPerSecond * 3.6
            speedAccuracy = accuracyInMetersPerSecond * 3.6

            // Update UI on main thread
            handler.post {
                String.format("%.0f", currentSpeedKmh).also { speedValueTextView?.text = it }

                // Show accuracy if available and significant
                if (speedAccuracy > 0.1) {
                    "±${String.format("%.1f", speedAccuracy)} km/h".also { speedAccuracyTextView?.text = it }
                    speedAccuracyTextView?.visibility = android.view.View.VISIBLE
                } else {
                    speedAccuracyTextView?.visibility = android.view.View.GONE
                }

                // Change color based on speed (optional visual feedback)
                val speedColor = when {
                    currentSpeedKmh > 100 -> "#FF4444" // Red for high speed
                    currentSpeedKmh > 60 -> "#FFAA00"  // Orange for medium speed
                    else -> "#FFFFFF"                  // White for normal speed
                }
                speedValueTextView?.setTextColor(speedColor.toColorInt())
            }

            Log.d(TAG, "Speed updated: ${String.format("%.1f", currentSpeedKmh)} km/h (±${String.format("%.1f", speedAccuracy)} km/h)")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating speed display: ${e.message}", e)
        }
    }


    private fun resetSpeedDisplay() {
        handler.post {
            speedValueTextView?.text = "0"
            speedAccuracyTextView?.visibility = android.view.View.GONE
            speedValueTextView?.setTextColor("#FFFFFF".toColorInt())
        }
        currentSpeedKmh = 0.0
        speedAccuracy = 0.0
    }


    private fun initializeComponents() {
        initializeRoutingEngine()
        initializeLocationEngine()
        initializeMapDownloader()
    }

    private fun initializeLocationEngine() {
        try {
            locationEngine = LocationEngine()
            Log.d(TAG, "LocationEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of LocationEngine failed: ${e.error.name}")
            // Continue without GPS if initialization fails
        }
    }
    private fun initializeRoutingEngine() {
        try {
            onlineRoutingEngine = RoutingEngine()
            offlineRoutingEngine = OfflineRoutingEngine()
            createDynamicRoutingEngine()
            Log.d(TAG, "All routing engines initialized successfully")
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of routing engines failed: " + e.error.name)
        }
    }

    private fun createDynamicRoutingEngine() {
        val dynamicRoutingOptions = DynamicRoutingEngineOptions().apply {
            minTimeDifference = Duration.ofSeconds(30)
            minTimeDifferencePercentage = 0.1
            pollInterval = Duration.ofMinutes(10)
        }

        try {
            dynamicRoutingEngine = DynamicRoutingEngine(dynamicRoutingOptions)
            Log.d(TAG, "DynamicRoutingEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of DynamicRoutingEngine failed: " + e.error.name)
        }
    }

    fun toggleRoutingMode(useDynamic: Boolean) {
        val previousMode = useDynamicRouting
        useDynamicRouting = useDynamic

        Log.d(TAG, "=== ROUTING MODE CHANGE ===")
        Log.d(TAG, "Previous Mode: ${if (previousMode) "Dynamic" else "Offline"}")
        Log.d(TAG, "New Mode: ${if (useDynamic) "Dynamic" else "Offline"}")
        Log.d(
            TAG,
            "Network Status: Connected=$isNetworkConnected, Type=$currentConnectionType, Metered=$isConnectionMetered"
        )
        Log.d(
            TAG,
            "Network Recommendation: ${if (getNetworkBasedRoutingRecommendation()) "Dynamic" else "Offline"}"
        )

        if (useDynamic && !isNetworkConnected) {
            Log.w(TAG, "WARNING: Dynamic routing requested but no network connection available!")
        }

        if (useDynamic && isConnectionMetered) {
            Log.w(TAG, "WARNING: Dynamic routing on metered connection - may incur data charges")
        }

        Log.d(TAG, "==========================")

        currentRoute?.let { route ->
            if (visualNavigator?.route != null || navigator?.route != null) {
                Log.d(TAG, "Restarting navigation with new routing engine")
                restartNavigationWithNewEngine(route)
            }
        }
    }

    private fun restartNavigationWithNewEngine(route: Route) {
        Log.d(TAG, "Restarting navigation with new routing engine")

        dynamicRoutingEngine?.stop()
        stopCurrentNavigation()

        handler.postDelayed({
            startGuidance(route)
        }, 100)
    }

    private fun stopCurrentNavigation() {
        Log.d(TAG, "Stopping current navigation instances")

        visualNavigator?.let { nav ->
            nav.stopRendering()
            nav.routeProgressListener = null
            nav.destinationReachedListener = null
            nav.speedLimitListener = null
            nav.milestoneStatusListener = null
            nav.safetyCameraWarningListener = null
            nav.speedWarningListener = null
            nav.route = null
            visualNavigator = null
            Log.d(TAG, "Visual navigator stopped and disposed")
        }

        navigator?.let { nav ->
            nav.routeProgressListener = null
            nav.route = null
            navigator = null
            Log.d(TAG, "Headless navigator stopped and disposed")
        }

        locationEngine?.let { engine ->
            try {
                engine.stop()
                Log.d(TAG, "Real location engine stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location engine: ${e.message}")
            }
        }

        locationSimulator?.let { simulator ->
            simulator.stop()
            simulator.listener = null
            locationSimulator = null
            Log.d(TAG, "Location simulator stopped")
        }
    }

    private fun initializeMapDownloader() {
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            ?: throw RuntimeException("SDKNativeEngine not initialized.")

        val storagePath = sdkNativeEngine.options.cachePath
        val persistentMapStoragePath = sdkNativeEngine.options.persistentMapStoragePath
        Log.d(TAG, "Cache storage path: $storagePath")
        Log.d(TAG, "Persistent map storage path: $persistentMapStoragePath")

        MapDownloader.fromEngineAsync(
            sdkNativeEngine
        ) { mapDownloader ->
            this@Navigator.mapDownloader = mapDownloader
            Log.d(TAG, "MapDownloader initialized successfully")

            checkExistingMapData()

            if (!isMapDataReady) {
                downloadRegionsList()
            }
        }
    }
    
    private fun showMapDownloadDialog() {
        if (mapDownloadDialog?.isVisible == true) {
            return // Dialog already showing
        }
        
        try {
            mapDownloadDialog = MapDownloadProgressDialog.newInstance()
            mapDownloadDialog?.setOnCancelListener {
                Log.d(TAG, "Map download cancelled by user")
                cancelMapDownloads()
            }
            mapDownloadDialog?.setOnRetryListener {
                Log.d(TAG, "Map download retry requested by user")
                rwandaRegion?.let { region ->
                    downloadRetryCount = 0
                    downloadRwandaMapWithRetry(region)
                }
            }
            mapDownloadDialog?.setOnOkListener {
                Log.d(TAG, "Map download dialog dismissed")
            }
            
            mapDownloadDialog?.show(supportFragmentManager, "MapDownloadProgressDialog")
            Log.d(TAG, "Map download progress dialog shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing map download dialog: ${e.message}", e)
        }
    }
    
    private fun dismissMapDownloadDialog() {
        try {
            mapDownloadDialog?.dismiss()
            mapDownloadDialog = null
            Log.d(TAG, "Map download progress dialog dismissed")
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing map download dialog: ${e.message}", e)
        }
    }
    
    private fun updateMapDownloadProgress(progress: Int, totalSizeMB: Long) {
        try {
            mapDownloadDialog?.updateProgress(progress, totalSizeMB)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating map download progress: ${e.message}", e)
        }
    }

    private fun checkExistingMapData() {
        mapDownloader?.let { downloader ->
            try {
                // First check the persistent map status for corruption
                checkPersistentMapStatus(downloader)
                
                val installedRegions = downloader.installedRegions
                Log.d(TAG, "=== INSTALLED REGIONS CHECK ===")
                Log.d(
                    TAG,
                    "Total installed regions: ${installedRegions.size} ${installedRegions.fastForEach { it.regionId }}"
                )

                var rwandaFound = false
                var rwandaRegionInstalled: com.here.sdk.maploader.InstalledRegion? = null
                
                for (region in installedRegions) {
                    val sizeInMB = region.sizeOnDiskInBytes / (1024 * 1024)
                    Log.d(
                        TAG,
                        "Installed region: ${region.regionId.id}, Size: ${sizeInMB}MB, Status: ${region.status}"
                    )

                    if (region.regionId.id.toString().contains("25726922", ignoreCase = true) ||
                        region.regionId.id.toString().contains("25726922", ignoreCase = true)
                    ) {
                        rwandaFound = true
                        rwandaRegionInstalled = region
                        Log.d(TAG, "Found existing Rwanda map data!")
                    }
                }

                if (rwandaFound && rwandaRegionInstalled != null) {
                    Log.d(TAG, "Rwanda map data already available")
                    
                    // Check if map data needs update
                    checkForMapUpdates(rwandaRegionInstalled)
                    
                    isMapDataReady = true
                    proceedWithNavigation()
                } else {
                    Log.d(TAG, "No Rwanda map data found, will download after region list")
                    showMapDownloadDialog()
                    mapDownloadDialog?.showCheckingForUpdates()
                }

                Log.d(TAG, "==============================")
            } catch (e: MapLoaderException) {
                Log.e(TAG, "Error checking installed regions: ${e.error}")
                // Continue with download attempt even if check fails
                proceedWithMapDownload()
            }
        }
    }

    /**
     * Check the persistent map status for corruption and attempt repair if needed
     */
    private fun checkPersistentMapStatus(downloader: MapDownloader) {
        try {
            val persistentMapStatus = downloader.initialPersistentMapStatus
            Log.d(TAG, "=== PERSISTENT MAP STATUS CHECK ===")
            Log.d(TAG, "Persistent map status: $persistentMapStatus")
            
            when (persistentMapStatus) {
                PersistentMapStatus.OK -> {
                    Log.d(TAG, "Persistent map data is healthy")
                }
                PersistentMapStatus.PENDING_UPDATE -> {
                    Log.w(TAG, "Persistent map data has pending updates")
                    // This is not an error, just needs update
                }
                PersistentMapStatus.CORRUPTED -> {
                    Log.w(TAG, "Persistent map data is corrupted, attempting repair...")
                    attemptMapRepair(downloader)
                }
                else -> {
                    Log.w(TAG, "Unknown persistent map status: $persistentMapStatus")
                    attemptMapRepair(downloader)
                }
            }
            Log.d(TAG, "===============================")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking persistent map status: ${e.message}", e)
        }
    }

    /**
     * Attempt to repair corrupted map data
     */
    private fun attemptMapRepair(downloader: MapDownloader) {
        if (isRepairInProgress) {
            Log.d(TAG, "Repair already in progress, skipping")
            return
        }
        
        if (repairRetryCount >= MAX_REPAIR_RETRIES) {
            Log.e(TAG, "Maximum repair retries reached ($MAX_REPAIR_RETRIES), proceeding without repair")
            mapDownloadDialog?.showError("Map data repair failed after multiple attempts. Please restart the app.")
            return
        }
        
        repairRetryCount++
        isRepairInProgress = true
        
        Log.d(TAG, "=== ATTEMPTING MAP REPAIR ===")
        Log.d(TAG, "Repair attempt: $repairRetryCount/$MAX_REPAIR_RETRIES")
        
        // Show repair dialog
        mapDownloadDialog?.showRepairing(repairRetryCount, MAX_REPAIR_RETRIES)
        
        downloader.repairPersistentMap { persistentMapRepairError ->
            isRepairInProgress = false

            if (persistentMapRepairError == null) {
                Log.d(TAG, "=== MAP REPAIR SUCCESSFUL ===")
                Log.d(TAG, "Map data repair completed successfully!")
                Log.d(TAG, "=============================")

                // Reset retry count on success
                repairRetryCount = 0

                // Show success and proceed
                mapDownloadDialog?.showRepairSuccess()

                // Re-check map data after successful repair
                handler.postDelayed({
                    checkExistingMapData()
                }, 1000)

            } else {
                Log.e(TAG, "=== MAP REPAIR FAILED ===")
                Log.e(TAG, "Repair error: ${persistentMapRepairError.name}")
                Log.e(TAG, "=========================")

                handleRepairError(persistentMapRepairError, downloader)
            }
        }
    }

    /**
     * Handle repair errors and determine next action
     */
    private fun handleRepairError(error: PersistentMapRepairError, downloader: MapDownloader) {
        when (error) {
            PersistentMapRepairError.BROKEN_UPDATE -> {
                Log.e(TAG, "Repair not possible, need to clear and re-download")
                handleRepairNotPossible(downloader)
            }
            PersistentMapRepairError.PARTIALLY_RESTORED -> {
                Log.e(TAG, "Repair failed, will retry")
                scheduleRepairRetry(downloader)
            }
            PersistentMapRepairError.UNKNOWN -> {
                Log.w(TAG, "Repair was interrupted, will retry")
                scheduleRepairRetry(downloader)
            }
            else -> {
                Log.e(TAG, "Unknown repair error: ${error.name}, will retry")
                scheduleRepairRetry(downloader)
            }
        }
    }

    /**
     * Schedule a repair retry with delay
     */
    private fun scheduleRepairRetry(downloader: MapDownloader) {
        if (repairRetryCount < MAX_REPAIR_RETRIES) {
            val delay = REPAIR_RETRY_DELAY_MS * repairRetryCount // Exponential backoff
            Log.d(TAG, "Scheduling repair retry in ${delay}ms")
            
            handler.postDelayed({
                if (!isMapDataReady && !isDestroyed) {
                    attemptMapRepair(downloader)
                }
            }, delay)
        } else {
            Log.e(TAG, "Maximum repair retries reached, proceeding with corrupted data")
            mapDownloadDialog?.showRepairFailed("Map data repair failed after multiple attempts. Navigation may not work properly.")
            proceedWithNavigation()
        }
    }

    /**
     * Handle case where repair is not possible - clear cache and re-download
     */
    private fun handleRepairNotPossible(downloader: MapDownloader) {
        Log.w(TAG, "=== REPAIR NOT POSSIBLE ===")
        Log.w(TAG, "Clearing map cache and will re-download")
        
        try {
            // Clear the map cache
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()

            sdkNativeEngine?.let { engine ->
                SDKCache.fromEngine(engine).clearCache {
                    Log.d(TAG, "Map cache cleared successfully")
                }

            }
            
            // Delete all installed regions
            val installedRegions = downloader.installedRegions
            if (installedRegions.isNotEmpty()) {
                val regionIds = installedRegions.map { it.regionId }
                downloader.deleteRegions(regionIds) { error, deletedRegionIds ->
                    if (error == null) {
                        Log.d(TAG, "Successfully deleted ${deletedRegionIds?.size ?: 0} corrupted regions")
                        mapDownloadDialog?.showClearingData()
                        
                        // Reset flags and start fresh download
                        isMapDataReady = false
                        repairRetryCount = 0
                        downloadRetryCount = 0
                        
                        // Proceed with fresh download
                        handler.postDelayed({
                            downloadRegionsList()
                        }, 2000)
                    } else {
                        Log.e(TAG, "Failed to delete corrupted regions: ${error.name}")
                        mapDownloadDialog?.showError("Failed to clear corrupted data. Please restart the app.")
                    }
                }
            } else {
                Log.d(TAG, "No installed regions to delete")
                mapDownloadDialog?.showClearingData()
                
                // Reset flags and start fresh download
                isMapDataReady = false
                repairRetryCount = 0
                downloadRetryCount = 0
                
                // Proceed with fresh download
                handler.postDelayed({
                    downloadRegionsList()
                }, 2000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing map data: ${e.message}", e)
            mapDownloadDialog?.showError("Error clearing corrupted data. Please restart the app.")
        }
        
        Log.w(TAG, "==========================")
    }

    private fun downloadRegionsList() {
        mapDownloader?.let { downloader ->
            Log.d(TAG, "Downloading list of available regions...")

            downloader.getDownloadableRegions(
                LanguageCode.EN_US,
                object : DownloadableRegionsCallback {
                    override fun onCompleted(
                        mapLoaderError: MapLoaderError?,
                        regions: MutableList<Region>?
                    ) {
                        if (mapLoaderError != null) {
                            Log.e(TAG, "Error downloading regions list: $mapLoaderError")
                            return
                        }

                        if (regions != null) {
                            downloadableRegions = regions
                            Log.d(TAG, "=== DOWNLOADABLE REGIONS ===")
                            Log.d(TAG, "Found ${regions.size} top-level regions")

                            logAllRegions(regions)

                        val rwandaRegion = findRwandaRegion(regions)
                        if (rwandaRegion != null) {
                            Log.d(TAG, "Found Rwanda region!")
                            this@Navigator.rwandaRegion = rwandaRegion
                            if (!isMapDataReady) {
                                downloadRwandaMapWithRetry(rwandaRegion)
                            } else {
                                dismissMapDownloadDialog()
                                proceedWithNavigation()
                            }
                        } else {
                            Log.e(TAG, "Rwanda region not found in available regions!")
                            mapDownloadDialog?.showError("Rwanda region not found in available regions")
                        }
                            Log.d(TAG, "============================")
                        }
                    }
                })
        }
    }

    private fun logAllRegions(regions: List<Region>) {
        for (region in regions) {
            val sizeInMB = region.sizeOnDiskInBytes / (1024 * 1024)
            Log.d(TAG, "Continent: ${region.name}, Size: ${sizeInMB}MB")

            region.childRegions?.let { childRegions ->
                Log.d(TAG, "  Countries in ${region.name}:")
                for (childRegion in childRegions) {
                    val childSizeInMB = childRegion.sizeOnDiskInBytes / (1024 * 1024)
                    Log.d(
                        TAG,
                        "    - ${childRegion.name} (ID: ${childRegion.regionId.id}, Size: ${childSizeInMB}MB)"
                    )

                    childRegion.childRegions?.let { subRegions ->
                        for (subRegion in subRegions.take(5)) {
                            val subSizeInMB = subRegion.sizeOnDiskInBytes / (1024 * 1024)
                            Log.d(TAG, "        └ ${subRegion.name} (${subSizeInMB}MB)")
                        }
                        if (subRegions.size > 5) {
                            Log.d(TAG, "        └ ... and ${subRegions.size - 5} more sub-regions")
                        }
                    }
                }
            }
        }
    }

    private fun findRwandaRegion(regions: List<Region>): Region? {
        val rwandaNames = listOf(
            "Rwanda", "RWANDA", "rwanda",
            "Republic of Rwanda", "République du Rwanda",
            "U Rwanda"
        )

        for (region in regions) {
            for (name in rwandaNames) {
                if (region.name.equals(name, ignoreCase = true) ||
                    region.name.contains(name, ignoreCase = true)
                ) {
                    Log.d(TAG, "Found Rwanda at top level: ${region.name}")
                    return region
                }
            }

            region.childRegions?.let { childRegions ->
                for (childRegion in childRegions) {
                    for (name in rwandaNames) {
                        if (childRegion.name.equals(name, ignoreCase = true) ||
                            childRegion.name.contains(name, ignoreCase = true)
                        ) {
                            Log.d(TAG, "Found Rwanda in ${region.name}: ${childRegion.name}")
                            return childRegion
                        }
                    }

                    childRegion.childRegions?.let { subRegions ->
                        for (subRegion in subRegions) {
                            for (name in rwandaNames) {
                                if (subRegion.name.equals(name, ignoreCase = true) ||
                                    subRegion.name.contains(name, ignoreCase = true)
                                ) {
                                    Log.d(
                                        TAG,
                                        "Found Rwanda in ${childRegion.name}: ${subRegion.name}"
                                    )
                                    return subRegion
                                }
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    private fun removeOriginMarker() {
        mapView?.let { mapView ->
            if (waypointMarkers.isNotEmpty()) {
                val originMarker = waypointMarkers[0]
                mapView.mapScene.removeMapMarker(originMarker)
                waypointMarkers.removeAt(0)
                Log.d(TAG, "Removed origin marker - navigation started")
            }
        }
    }

    private fun downloadRwandaMapWithRetry(rwandaRegion: Region) {
        if (isDownloadInProgress) {
            Log.w(TAG, "Download already in progress, skipping retry")
            return
        }
        
        if (downloadRetryCount >= MAX_DOWNLOAD_RETRIES) {
            Log.e(TAG, "Maximum download retries reached ($MAX_DOWNLOAD_RETRIES), proceeding without offline maps")
            mapDownloadDialog?.showError("Maximum download retries reached. Proceeding without offline maps.")
            return
        }
        
        downloadRetryCount++
        Log.d(TAG, "Starting Rwanda map download (attempt $downloadRetryCount/$MAX_DOWNLOAD_RETRIES)")
        
        if (downloadRetryCount > 1) {
            mapDownloadDialog?.showRetrying(downloadRetryCount, MAX_DOWNLOAD_RETRIES)
        } else {
            mapDownloadDialog?.showDownloading(0, (rwandaRegion.sizeOnDiskInBytes / (1024 * 1024)).toInt())
        }
        
        downloadRwandaMap(rwandaRegion)
    }
    
    private fun downloadRwandaMap(rwandaRegion: Region) {
        mapDownloader?.let { downloader ->
            val sizeInMB = rwandaRegion.sizeOnDiskInBytes / (1024 * 1024)
            Log.d(TAG, "=== DOWNLOADING RWANDA MAP ===")
            Log.d(TAG, "Region: ${rwandaRegion.name}")
            Log.d(TAG, "Size: ${sizeInMB}MB")
            Log.d(TAG, "ID: ${rwandaRegion.regionId.id}")
            Log.d(TAG, "Attempt: $downloadRetryCount/$MAX_DOWNLOAD_RETRIES")
            Log.d(TAG, "==============================")

            isDownloadInProgress = true
            val regionIds = listOf(rwandaRegion.regionId)
            val downloadTask =
                downloader.downloadRegions(regionIds, object : DownloadRegionsStatusListener {
                    override fun onDownloadRegionsComplete(
                        mapLoaderError: MapLoaderError?,
                        regionIds: List<RegionId>?
                    ) {
                        isDownloadInProgress = false
                        
                        if (mapLoaderError != null) {
                            Log.e(TAG, "Rwanda map download failed: $mapLoaderError")
                            handleDownloadError(mapLoaderError, rwandaRegion)
                            return
                        }

                    if (regionIds != null) {
                        Log.d(TAG, "=== DOWNLOAD COMPLETED ===")
                        Log.d(TAG, "Successfully downloaded Rwanda map!")
                        Log.d(TAG, "Downloaded regions: ${regionIds.map { it.id }}")
                        Log.d(TAG, "==========================")

                        // Reset retry count on success
                        downloadRetryCount = 0
                        isMapDataReady = true
                        mapDownloadDialog?.showSuccess()
                        proceedWithNavigation()
                    }
                    }

                    override fun onProgress(regionId: RegionId, percentage: Int) {
                        Log.d(
                            TAG,
                            "Downloading Rwanda map: ${percentage}% (Region: ${regionId.id})"
                        )
                        // Notify progress callback if set
                        downloadProgressCallback?.invoke(percentage)
                        
                        // Update dialog progress
                        updateMapDownloadProgress(percentage, sizeInMB)
                    }

                    override fun onPause(mapLoaderError: MapLoaderError?) {
                        if (mapLoaderError == null) {
                            Log.d(TAG, "Rwanda map download paused by user")
                        } else {
                            Log.e(TAG, "Rwanda map download paused due to error: $mapLoaderError")
                            handleDownloadError(mapLoaderError, rwandaRegion)
                        }
                    }

                    override fun onResume() {
                        Log.d(TAG, "Rwanda map download resumed")
                    }
                })

            mapDownloaderTasks.add(downloadTask)
        }
    }
    
    private fun handleDownloadError(error: MapLoaderError, rwandaRegion: Region) {
        Log.e(TAG, "=== DOWNLOAD ERROR HANDLING ===")
        Log.e(TAG, "Error: $error")
        Log.e(TAG, "Retry count: $downloadRetryCount/$MAX_DOWNLOAD_RETRIES")
        
        when (error) {
            MapLoaderError.NETWORK_CONNECTION_ERROR -> {
                Log.w(TAG, "Network error detected, will retry when network is available")
                mapDownloadDialog?.showNetworkWaiting()
                scheduleRetryOnNetworkAvailable(rwandaRegion)
            }
            MapLoaderError.NOT_ENOUGH_SPACE -> {
                Log.e(TAG, "Insufficient storage for map download")
                mapDownloadDialog?.showError("Insufficient storage space for map download")
                // Don't retry for storage issues
            }
            MapLoaderError.INVALID_ARGUMENT -> {
                Log.e(TAG, "Invalid parameters for download")
                mapDownloadDialog?.showError("Invalid download parameters")
                // Don't retry for parameter issues
            }
            MapLoaderError.INTERNAL_ERROR -> {
                Log.w(TAG, "Internal error, will retry")
                scheduleRetryWithDelay(rwandaRegion)
            }
            MapLoaderError.NOT_READY -> {
                Log.w(TAG, "Downloader not ready, will retry")
                scheduleRetryWithDelay(rwandaRegion)
            }
            else -> {
                Log.w(TAG, "Unknown error, will retry")
                mapDownloadDialog?.showError("Download failed: ${error.name}")
                scheduleRetryWithDelay(rwandaRegion)
            }
        }
        Log.e(TAG, "===============================")
    }
    
    private fun scheduleRetryWithDelay(rwandaRegion: Region) {
        if (downloadRetryCount < MAX_DOWNLOAD_RETRIES) {
            val delay = DOWNLOAD_RETRY_DELAY_MS * downloadRetryCount // Exponential backoff
            Log.d(TAG, "Scheduling retry in ${delay}ms")
            
            handler.postDelayed({
                if (!isMapDataReady && !isDestroyed) {
                    downloadRwandaMapWithRetry(rwandaRegion)
                }
            }, delay)
        } else {
            Log.e(TAG, "Maximum retries reached, proceeding without offline maps")
            proceedWithNavigation()
        }
    }
    
    private fun scheduleRetryOnNetworkAvailable(rwandaRegion: Region) {
        // Wait for network to become available
        networkMonitor?.let { monitor ->
            val networkCheckRunnable = object : Runnable {
                override fun run() {
                    if (isNetworkConnected && !isMapDataReady && !isDestroyed) {
                        Log.d(TAG, "Network available, retrying download")
                        downloadRwandaMapWithRetry(rwandaRegion)
                    } else if (!isMapDataReady && !isDestroyed) {
                        // Check again in 10 seconds
                        handler.postDelayed(this, 10000)
                    }
                }
            }
            handler.postDelayed(networkCheckRunnable, 5000) // Initial 5 second delay
        } ?: run {
            // Fallback to regular retry if no network monitor
            scheduleRetryWithDelay(rwandaRegion)
        }
    }

    private fun proceedWithNavigation() {
        Log.d(TAG, "Proceeding with navigation setup...")
        
        // Dismiss map download dialog if showing
        dismissMapDownloadDialog()

        if (!isSimulated) {
            // Start location engine first, then wait for location
            startLocationEngineAndWaitForLocation()
        } else {
            // For simulated mode, proceed immediately
            isNavigationStarted = true
            calculateRouteAndStartNavigation()
        }
    }


    private fun startLocationEngineAndWaitForLocation() {
        locationEngine?.let { engine ->
            Log.d(TAG, "Starting location engine for real-time navigation...")

            val locationStatusListener: LocationStatusListener = object : LocationStatusListener {
                override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
                    Log.d(TAG, "Location engine status: ${locationEngineStatus.name}")
                    when (locationEngineStatus) {
                        LocationEngineStatus.ENGINE_STARTED -> {
                            Log.d(TAG, "Location engine started successfully")
                        }
                        LocationEngineStatus.ENGINE_STOPPED -> {
                            Log.w(TAG, "Location engine stopped")
                        }
                        else -> {
                            Log.d(TAG, "Location engine status: ${locationEngineStatus.name}")
                        }
                    }
                }

                override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
                    for (feature in features) {
                        Log.w(TAG, "Location feature not available: ${feature.name}")
                    }
                }
            }

            val locationListener = LocationListener { location ->
                Log.d(TAG, "Received location: ${location.coordinates.latitude}, ${location.coordinates.longitude}, speed: ${location.speedInMetersPerSecond}")
                currentUserLocation = location

                // Update speed display if showing map
                if (showMap) {
                    updateSpeedFromLocation(location)
                }

                // Start navigation once we have a location (only once)
                if (currentUserLocation != null && !isNavigationStarted) {
                    Log.d(TAG, "Location acquired, starting navigation...")
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
                handler.postDelayed({
                    if (currentUserLocation == null && !isNavigationStarted) {
                        Log.w(TAG, "No location received within timeout, falling back to simulated navigation")
                        isSimulated = true
                        isNavigationStarted = true
                        calculateRouteAndStartNavigation()
                    }
                }, 10000) // 10 second timeout

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start location engine: ${e.message}", e)
                Log.w(TAG, "Falling back to simulated navigation due to GPS failure")
                isSimulated = true
                isNavigationStarted = true
                calculateRouteAndStartNavigation()
            }
        } ?: run {
            Log.w(TAG, "Location engine not available, falling back to simulated navigation")
            isSimulated = true
            isNavigationStarted = true
            calculateRouteAndStartNavigation()
        }
    }


    private fun calculateRouteAndStartNavigation() {
        tripResponse?.let { trip ->
            var origin: Waypoint? = null
            if (currentUserLocation == null && !isSimulated) {
                Log.w(TAG, "No current user location available, switching to simulated navigation")
                isSimulated = true
            } else {
                currentUserLocation?.let {
                    origin = Waypoint(it.coordinates)
                    origin.headingInDegrees = it.bearingInDegrees
                    mapView?.camera?.lookAt(it.coordinates)

                }
            }
            val waypoints = createWaypointsFromTrip(trip, origin)

            val carOptions = CarOptions().apply {
                routeOptions.optimizeWaypointsOrder = false // Keep original order from trip
                routeOptions.enableTolls = true
                routeOptions.alternatives = 1
                routeOptions.enableRouteHandle = true // Required for rerouting functionality
            }

            val routingEngine = getSelectedRoutingEngine()

            Log.d(
                TAG,
                "Calculating route using: ${if (useDynamicRouting) "Online RoutingEngine" else "Offline RoutingEngine"}"
            )
            Log.d(TAG, "Trip ID: ${trip.id}")
            Log.d(TAG, "Waypoints: ${waypoints.size}")

            routingEngine.calculateRoute(waypoints, carOptions) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val route = routes[0]
                    currentRoute = route

                    // Set route in progress tracker
                    tripProgressTracker?.setCurrentRoute(route)

                    Log.d(
                        TAG,
                        "Route calculated successfully using ${if (useDynamicRouting) "online" else "offline"} engine"
                    )
                    logRouteInfo(route)

                    if (showMap) {
                        showRouteOnMap(route)
                        addWaypointMarkersToMap(waypoints)
                    }

                    startGuidance(route)
                } else {
                    Log.e(TAG, "Route calculation failed: ${routingError?.name}")
                    if (useDynamicRouting && routingError != null) {
                        Log.d(TAG, "Online routing failed, trying offline routing as fallback")
                        fallbackToOfflineRouting(waypoints, carOptions)
                    }
                }
            }
        } ?: run {
            Log.e(TAG, "No trip data available for route calculation")
        }
    }

    private fun createWaypointsFromTrip(trip: TripResponse, origin: Waypoint? = null): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        if(origin != null) {
            waypoints.add(origin)
        }

        // Add origin
        val origin = Waypoint(
            GeoCoordinates(
                trip.route.origin.latitude,
                trip.route.origin.longitude
            )
        ).apply {
            type = WaypointType.STOPOVER
        }
        waypoints.add(origin)

        // Add intermediate waypoints sorted by order
        val sortedWaypoints = trip.waypoints.sortedBy { it.order }
        sortedWaypoints.forEach { tripWaypoint ->
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

        Log.d(TAG, "Created ${waypoints.size} waypoints from trip data:")
        Log.d(TAG, "  Origin: ${trip.route.origin.google_place_name}")
        sortedWaypoints.forEachIndexed { index, waypoint ->
            Log.d(TAG, "  Waypoint ${index + 1}: ${waypoint.location.google_place_name}")
        }
        Log.d(TAG, "  Destination: ${trip.route.destination.google_place_name}")

        return waypoints
    }

    private fun addWaypointMarkersToMap(waypoints: List<Waypoint>) {
        mapView?.let { mapView ->
            waypointMarkers.forEach { mapView.mapScene.removeMapMarker(it) }
            waypointMarkers.clear()

            waypoints.forEachIndexed { index, waypoint ->
                val marker = when (index) {
                    0 -> createMarker(waypoint.coordinates)
                    waypoints.size - 1 -> createMarker(waypoint.coordinates)
                    else -> createMarker(waypoint.coordinates)
                }

                mapView.mapScene.addMapMarker(marker)
                waypointMarkers.add(marker)
            }

            Log.d(TAG, "Added ${waypointMarkers.size} waypoint markers to map")
        }
    }

    private fun createMarker(coordinates: GeoCoordinates): MapMarker {
        val mapImage = createMapImageFromVectorDrawable(R.drawable.ic_marker, 32)
        val anchor = Anchor2D(0.5, 1.0)
        return MapMarker(coordinates, mapImage, anchor)
    }

    private fun createMapImageFromVectorDrawable(drawableRes: Int, sizeInDp: Int = 31): MapImage {
        markerImageCache[drawableRes]?.let { return it }

        try {
            val vectorDrawable = ContextCompat.getDrawable(this, drawableRes) as? VectorDrawable
                ?: throw IllegalArgumentException("Resource $drawableRes is not a VectorDrawable")

            val density = this.resources.displayMetrics.density
            val sizeInPixels = (sizeInDp * density).toInt()

            val bitmap = createBitmap(sizeInPixels, sizeInPixels)

            val canvas = Canvas(bitmap)
            vectorDrawable.setBounds(0, 0, sizeInPixels, sizeInPixels)
            vectorDrawable.draw(canvas)

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()

            val mapImage = MapImage(byteArray, ImageFormat.PNG)

            markerImageCache[drawableRes] = mapImage

            Log.d(
                TAG,
                "Created MapImage from vector drawable $drawableRes with size ${sizeInPixels}px (${sizeInDp}dp)"
            )
            return mapImage

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MapImage from vector drawable $drawableRes: ${e.message}")
            return MapImageFactory.fromResource(
                this.resources,
                android.R.drawable.ic_menu_mylocation
            )
        }
    }

    private fun showRouteOnMap(route: Route) {
        mapView?.let { mapView ->
            routeMapPolyline?.let {
                mapView.mapScene.removeMapPolyline(it)
            }

            val routeGeoPolyline = route.geometry
            val widthInPixels = 20f
            val routeColor = Color.valueOf(0f, 0.56f, 0.54f, 0.63f)
            routeMapPolyline = MapPolyline(
                routeGeoPolyline, MapPolyline.SolidRepresentation(
                    MapMeasureDependentRenderSize(RenderSize.Unit.PIXELS, widthInPixels.toDouble()),
                    routeColor,
                    LineCap.ROUND
                )
            )
            mapView.mapScene.addMapPolyline(routeMapPolyline!!)

            Log.d(TAG, "Route displayed on map with ${route.sections.size} sections")
        }
    }

    private fun getSelectedRoutingEngine(): RoutingInterface {
        return if (useDynamicRouting && isNetworkAvailable()) {
            onlineRoutingEngine!!
        } else {
            Log.d(TAG, "Using offline routing engine")
            offlineRoutingEngine!!
        }
    }

    private fun fallbackToOfflineRouting(waypoints: List<Waypoint>, carOptions: CarOptions) {
        offlineRoutingEngine?.calculateRoute(
            waypoints, carOptions
        ) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                val route = routes[0]
                currentRoute = route

                // Set route in progress tracker
                tripProgressTracker?.setCurrentRoute(route)

                Log.d(TAG, "Route calculated successfully using offline engine (fallback)")
                logRouteInfo(route)
                startGuidance(route)
            } else {
                Log.e(
                    TAG,
                    "Both online and offline route calculation failed: ${routingError?.name}"
                )
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        // Reflect the actual connectivity provided by MainActivity and monitored by NetworkMonitor
        return isNetworkConnected
    }

    private fun logRouteInfo(route: Route) {
        val distanceInMeters = route.lengthInMeters
        val distanceInKm = distanceInMeters / 1000.0
        val durationInSeconds = route.duration.seconds
        val durationInMinutes = TimeUnit.SECONDS.toMinutes(durationInSeconds)
        val hours = durationInMinutes / 60
        val minutes = durationInMinutes % 60

        tripResponse?.let { trip ->
            Log.d(TAG, "=== ROUTE INFORMATION ===")
            Log.d(TAG, "Trip ID: ${trip.id}")
            Log.d(TAG, "Vehicle: ${trip.vehicle.license_plate}")
            Log.d(TAG, "Total Distance: ${String.format("%.2f", distanceInKm)} km")
            Log.d(TAG, "Estimated Duration: ${hours}h ${minutes}m")
            Log.d(TAG, "Origin: ${trip.route.origin.google_place_name}")
            Log.d(TAG, "Destination: ${trip.route.destination.google_place_name}")
            Log.d(TAG, "Intermediate waypoints: ${trip.waypoints.size}")
            Log.d(TAG, "Navigation Mode: ${if (showMap) "Visual Navigator" else "Headless Navigator"}")
            Log.d(
                TAG,
                "Routing Mode: ${if (useDynamicRouting) "Dynamic (with live traffic)" else "Offline"}"
            )
            Log.d(
                TAG,
                "Map Data Status: ${if (isMapDataReady) "Offline Ready" else "Cache/Online Only"}"
            )
            Log.d(TAG, "========================")
        }
    }

    private fun startGuidance(route: Route) {
        removeOriginMarker()

        // Reset deviation state for new route
        resetDeviationState()

        // Update trip status to in_progress as soon as guidance starts
        updateTripStatusToInProgress { success ->
            if (success) {
                // Send trip start notification via MQTT only after status update is complete
                tripProgressTracker?.sendTripStartNotification()
            } else {
                Log.e(TAG, "Failed to update trip status, skipping MQTT notification")
            }
        }

        if (showMap && mapView != null) {
            startVisualNavigation(route)
        } else {
            startHeadlessNavigation(route)
        }

        if (useDynamicRouting) {
            startDynamicSearchForBetterRoutes(route)
        }
    }

    private fun startDynamicSearchForBetterRoutes(route: Route) {
        dynamicRoutingEngine?.let { engine ->
            try {
                Log.d(TAG, "Starting dynamic routing for better route search")
                engine.start(route, object : DynamicRoutingListener {
                    override fun onBetterRouteFound(
                        newRoute: Route,
                        etaDifferenceInSeconds: Int,
                        distanceDifferenceInMeters: Int
                    ) {
                        Log.d(TAG, "=== BETTER ROUTE FOUND ===")
                        Log.d(TAG, "ETA difference: ${etaDifferenceInSeconds}s")
                        Log.d(TAG, "Distance difference: ${distanceDifferenceInMeters}m")

                        if (etaDifferenceInSeconds < -60 || distanceDifferenceInMeters < -500) {
                            Log.d(TAG, "Accepting better route automatically")
                            updateToNewRoute(newRoute)
                        } else {
                            Log.d(
                                TAG,
                                "Better route found but improvement not significant enough for auto-switch"
                            )
                        }
                        Log.d(TAG, "========================")
                    }

                    override fun onRoutingError(routingError: RoutingError) {
                        Log.w(TAG, "Error in dynamic routing: ${routingError.name}")
                    }
                })
            } catch (e: DynamicRoutingEngine.StartException) {
                Log.e(TAG, "Failed to start dynamic routing: ${e.message}")
            }
        }
    }

    private fun updateToNewRoute(newRoute: Route) {
        currentRoute = newRoute

        // Update route in progress tracker
        tripProgressTracker?.setCurrentRoute(newRoute)

        visualNavigator?.route = newRoute
        navigator?.route = newRoute

        Log.d(TAG, "Updated to better route")
        logRouteInfo(newRoute)
    }

    private fun startVisualNavigation(route: Route) {
        try {
            visualNavigator = VisualNavigator()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of VisualNavigator failed: " + e.error.name)
        }

        mapView?.mapScene?.loadScene(MapScheme.NORMAL_DAY) { mapError ->
            if (mapError == null) {
                visualNavigator?.let { nav ->
                    nav.startRendering(mapView!!)
                    nav.cameraBehavior = if (isCameraBehaviorEnabled) DynamicCameraBehavior() else null

                    setupNavigationListeners(nav)
                    setupRouteProgressListener(nav)

                    nav.destinationReachedListener = DestinationReachedListener {
                        Log.d(TAG, "Destination reached!")
                        nav.stopRendering()
                    }

                    nav.route = route
                    setupLocationSource(nav, route, isSimulator = isSimulated)
                    Log.d(TAG, "Visual navigation started")
                }
            } else {
                Log.e(TAG, "Map scene loading failed: ${mapError.name}")
            }
        }
    }

    private fun startHeadlessNavigation(route: Route) {
        try {
            navigator = Navigator()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of Navigator failed: " + e.error.name)
        }

        navigator?.let { nav ->
            setupRouteProgressListener(nav)
            nav.route = route
            setupLocationSource(nav, route, isSimulated)
            Log.d(TAG, "Headless navigation started")
        }
    }

    private fun setupNavigationListeners(nav: VisualNavigator) {
        nav.speedLimitListener = SpeedLimitListener { speedLimit: SpeedLimit ->
            val currentSpeedLimit = getCurrentSpeedLimit(speedLimit)
            when (currentSpeedLimit) {
                null -> Log.d(TAG, "Speed limit data unavailable")
                0.0 -> Log.d(TAG, "No speed limit on this road")
                else -> Log.d(TAG, "Current speed limit: $currentSpeedLimit m/s")
            }
        }

        nav.milestoneStatusListener =
            MilestoneStatusListener { milestone: Milestone, milestoneStatus: MilestoneStatus ->
                when {
                    milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.REACHED -> {
                        Log.d(TAG, "🎉 WAYPOINT REACHED!")
                        Log.d(TAG, "Waypoint index: ${milestone.waypointIndex}")
                        Log.d(TAG, "Original coordinates: ${milestone.originalCoordinates}")
                        Log.d(TAG, "Map-matched coordinates: ${milestone.mapMatchedCoordinates}")

                        updateWaypointMarker(milestone.waypointIndex!!, true)
                        updateTripWaypointStatus(milestone.waypointIndex!!, true)
                    }

                    milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.MISSED -> {
                        Log.w(TAG, "⚠️ WAYPOINT MISSED!")
                        Log.w(TAG, "Waypoint index: ${milestone.waypointIndex}")
                        Log.w(TAG, "Original coordinates: ${milestone.originalCoordinates}")

                        handleMissedWaypoint()
                        updateWaypointMarker(milestone.waypointIndex!!, false)
                        updateTripWaypointStatus(milestone.waypointIndex!!, false)
                    }

                    milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.REACHED -> {
                        Log.d(
                            TAG,
                            "🔄 System waypoint reached at: ${milestone.mapMatchedCoordinates}"
                        )
                    }

                    milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.MISSED -> {
                        Log.w(
                            TAG,
                            "⚠️ System waypoint missed at: ${milestone.mapMatchedCoordinates}"
                        )
                    }
                }
            }

        nav.safetyCameraWarningListener = SafetyCameraWarningListener { warning ->
            val distance = warning.distanceToCameraInMeters
            val speedLimit = warning.speedLimitInMetersPerSecond
            when (warning.distanceType) {
                DistanceType.AHEAD -> Log.d(
                    TAG,
                    "Safety camera ${warning.type.name} ahead: ${distance}m, limit: ${speedLimit}m/s"
                )

                DistanceType.PASSED -> Log.d(
                    TAG,
                    "Safety camera ${warning.type.name} passed: ${distance}m"
                )

                DistanceType.REACHED -> Log.d(TAG, "Safety camera ${warning.type.name} reached")
            }
        }

        nav.speedWarningListener = SpeedWarningListener { status ->
            when (status) {
                SpeedWarningStatus.SPEED_LIMIT_EXCEEDED -> {
                    Log.d(TAG, "Speed limit exceeded!")
                    val ringtoneUri =
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val ringtone = RingtoneManager.getRingtone(this, ringtoneUri)
                    ringtone.play()
                }

                SpeedWarningStatus.SPEED_LIMIT_RESTORED -> {
                    Log.d(TAG, "Speed back within limits")
                }
            }
        }
    }

    private fun updateTripWaypointStatus(waypointIndex: Int, reached: Boolean) {
        tripResponse?.let { trip ->
            // Map waypoint index to trip waypoints (accounting for origin being index 0)
            if (waypointIndex > 0 && waypointIndex <= trip.waypoints.size) {
                val tripWaypointIndex = waypointIndex - 1 // Adjust for origin
                val sortedWaypoints = trip.waypoints.sortedBy { it.order }
                if (tripWaypointIndex < sortedWaypoints.size) {
                    val tripWaypoint = sortedWaypoints[tripWaypointIndex]

                    val waypointDisplayName = getLocationDisplayName(tripWaypoint.location)
                    Log.d(TAG, "Waypoint $waypointDisplayName ${if (reached) "reached" else "missed"}")

                    // Update local waypoint status
                    if (reached) {
                        updateWaypointAsPassed(tripWaypoint)
                    }

                    // Here you could update the waypoint status in your backend
                    // updateWaypointStatusOnServer(tripWaypoint.id, reached)
                }
            }
        }
    }

    private fun updateWaypointAsPassed(waypoint: TripWaypoint) {
        // Update the waypoint status in the local trip response
        tripResponse?.let { trip ->
            val updatedWaypoints = trip.waypoints.map { wp ->
                if (wp.id == waypoint.id) {
                    wp.copy(is_passed = true, is_next = false)
                } else {
                    wp
                }
            }

            // Update the next waypoint as the next one
            val nextWaypoint = updatedWaypoints
                .filter { !it.is_passed }
                .minByOrNull { it.order }

            val finalWaypoints = updatedWaypoints.map { wp ->
                if (nextWaypoint != null && wp.id == nextWaypoint.id) {
                    wp.copy(is_next = true)
                } else {
                    wp.copy(is_next = false)
                }
            }

            tripResponse = trip.copy(waypoints = finalWaypoints)

            // Update headless UI if in headless mode
            if (!showMap) {
                updateWaypointsDisplay()
            }

            val waypointDisplayName = getLocationDisplayName(waypoint.location)
            Log.d(TAG, "Waypoint $waypointDisplayName marked as passed")
        }
    }

    private fun updateWaypointMarker(waypointIndex: Int, reached: Boolean) {
        if (waypointIndex < waypointMarkers.size) {
            Log.d(
                TAG,
                "Updated marker for waypoint $waypointIndex - ${if (reached) "reached" else "missed"}"
            )
        }
    }

    private fun handleMissedWaypoint() {
        Log.w(TAG, "=== HANDLING MISSED WAYPOINT ===")
        Log.w(TAG, "Consider recalculating route to include missed waypoint")

        currentRoute?.let { route ->
            Log.w(TAG, "Current route has ${route.sections.size} sections")
            Log.w(TAG, "Continuing with current route despite missed waypoint")
        }
        Log.w(TAG, "===============================")
    }

    private fun getCurrentSpeedLimit(speedLimit: SpeedLimit): Double? {
        Log.d(
            TAG, "Speed limits - Regular: ${speedLimit.speedLimitInMetersPerSecond}, " +
                    "School: ${speedLimit.schoolZoneSpeedLimitInMetersPerSecond}, " +
                    "Time-dependent: ${speedLimit.timeDependentSpeedLimitInMetersPerSecond}"
        )
        return speedLimit.effectiveSpeedLimitInMetersPerSecond()
    }

    private fun setupRouteProgressListener(navInstance: Any) {
        val progressListener = RouteProgressListener { routeProgress ->
            try {
                // Log navigation progress
                logNavigationProgress(routeProgress)

                // Update trip progress tracker
                tripProgressTracker?.onRouteProgressUpdate(routeProgress)

                // Send periodic MQTT updates (every 30 seconds)
                sendPeriodicMqttUpdate()
                
                // Check MQTT health periodically (every 2 minutes)
                checkMqttHealthPeriodically()
            } catch (e: Exception) {
                Log.e(TAG, "Error in route progress listener: ${e.message}", e)
            }
        }


        val navigableLocationListener = NavigableLocationListener { currentNavigableLocation: NavigableLocation ->
            try {
                val lastMapMatchedLocation = currentNavigableLocation.mapMatchedLocation
                if (lastMapMatchedLocation == null) {
                    Log.w(TAG, "No map-matched location available, You are Off-road")
                    return@NavigableLocationListener
                }

                if (lastMapMatchedLocation.isDrivingInTheWrongWay) {
                    // For two-way streets, this value is always false. This feature is supported in tracking mode and when deviating from a route.
                    Log.d(
                        TAG,
                        "This is a one way road. User is driving against the allowed traffic direction."
                    )
                }

                val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond ?: 0.0
                val accuracy = currentNavigableLocation.originalLocation.speedAccuracyInMetersPerSecond ?: 0.0
                Log.d(
                    TAG,
                    "Driving speed (m/s): $speed plus/minus an accuracy of: $accuracy"
                )

                // Update speed display if showing map
                if (showMap) {
                    updateSpeedFromLocation(currentNavigableLocation.originalLocation)
                }

                // Update trip progress tracker with current speed and position
                tripProgressTracker?.updateCurrentSpeedAndPosition(
                    speedInMetersPerSecond = speed,
                    speedAccuracyInMetersPerSecond = accuracy,
                    location = currentNavigableLocation.originalLocation
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in navigable location listener: ${e.message}", e)
            }
        }

        val routeDeviationListener = RouteDeviationListener { routeDeviation ->
            try {
                handleRouteDeviation(routeDeviation)
            } catch (e: Exception) {
                Log.e(TAG, "Error in route deviation listener: ${e.message}", e)
            }
        }

        when (navInstance) {
            is VisualNavigator -> {
                navInstance.routeProgressListener = progressListener
                navInstance.navigableLocationListener = navigableLocationListener
                navInstance.routeDeviationListener = routeDeviationListener
                Log.d(TAG, "Route deviation listener set up for VisualNavigator")
            }
            is Navigator -> {
                navInstance.routeProgressListener = progressListener
                navInstance.navigableLocationListener = navigableLocationListener
                navInstance.routeDeviationListener = routeDeviationListener
                Log.d(TAG, "Route deviation listener set up for headless Navigator")
            }
        }
    }

    private fun handleRouteDeviation(routeDeviation: RouteDeviation) {
        val route = currentRoute
        if (route == null) {
            Log.w(TAG, "No current route available for deviation handling")
            return
        }

        // Get current geographic coordinates
        val currentMapMatchedLocation = routeDeviation.currentLocation.mapMatchedLocation
        val currentGeoCoordinates = currentMapMatchedLocation?.coordinates
            ?: routeDeviation.currentLocation.originalLocation.coordinates

        // Get last geographic coordinates on route
        // Get last geographic coordinates on route.
        val lastGeoCoordinatesOnRoute: GeoCoordinates?
        if (routeDeviation.lastLocationOnRoute != null) {
            val lastMapMatchedLocationOnRoute =
                routeDeviation.lastLocationOnRoute!!.mapMatchedLocation
            lastGeoCoordinatesOnRoute =
                lastMapMatchedLocationOnRoute?.coordinates
                    ?: routeDeviation.lastLocationOnRoute!!.originalLocation.coordinates
        } else {
            Log.d(
                TAG,
                "User was never following the route. So, we take the start of the route instead."
            )
            lastGeoCoordinatesOnRoute =
                route.sections[0].departurePlace.originalCoordinates
        }

        val distanceInMeters = currentGeoCoordinates.distanceTo(lastGeoCoordinatesOnRoute!!).toInt()
        Log.d(TAG, "Route deviation detected: ${distanceInMeters}m from route")

        // Count deviation events - wait for multiple events before deciding on action
        deviationCounter++

        if (isReturningToRoute) {
            Log.d(TAG, "Rerouting is already in progress, ignoring deviation event")
            return
        }

        // Check if deviation is significant enough to trigger rerouting
        if (distanceInMeters > DEVIATION_THRESHOLD_METERS && deviationCounter >= MIN_DEVIATION_EVENTS) {
            Log.d(TAG, "=== ROUTE DEVIATION DETECTED ===")
            Log.d(TAG, "Deviation distance: ${distanceInMeters}m (threshold: ${DEVIATION_THRESHOLD_METERS}m)")
            Log.d(TAG, "Deviation events: $deviationCounter (minimum: $MIN_DEVIATION_EVENTS)")
            Log.d(TAG, "Starting rerouting process...")

            isReturningToRoute = true
            performRerouting(routeDeviation, currentGeoCoordinates, currentMapMatchedLocation)
        } else {
            Log.d(TAG, "Deviation not significant enough for rerouting: ${distanceInMeters}m (need ${DEVIATION_THRESHOLD_METERS}m+) or not enough events: $deviationCounter (need $MIN_DEVIATION_EVENTS)")
        }
    }

    private fun resetDeviationState() {
        isReturningToRoute = false
        deviationCounter = 0
        Log.d(TAG, "Deviation state reset for new route")
    }

    private fun performRerouting(
        routeDeviation: RouteDeviation,
        currentGeoCoordinates: GeoCoordinates,
        currentMapMatchedLocation: com.here.sdk.navigation.MapMatchedLocation?
    ) {
        val route = currentRoute ?: return

        // Use current location as new starting point for the route
        val newStartingPoint = Waypoint(currentGeoCoordinates)

        // Improve route calculation by setting the heading direction
        currentMapMatchedLocation?.bearingInDegrees?.let { bearing ->
            newStartingPoint.headingInDegrees = bearing
            Log.d(TAG, "Setting heading direction: ${bearing}°")
        }

        Log.d(TAG, "Calculating new route from current position...")

        // Use the return-to-route algorithm to find the fastest way back to the original route
        val routingEngine = getSelectedRoutingEngine()

        routingEngine.returnToRoute(
            route,
            newStartingPoint,
            routeDeviation.lastTraveledSectionIndex,
            routeDeviation.traveledDistanceOnLastSectionInMeters
        ) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                val newRoute = routes[0]
                Log.d(TAG, "=== REROUTING SUCCESSFUL ===")
                Log.d(TAG, "New route calculated successfully")

                // Update current route
                currentRoute = newRoute

                // Update route in progress tracker
                tripProgressTracker?.setCurrentRoute(newRoute)

                // Update navigators with new route
                visualNavigator?.route = newRoute
                navigator?.route = newRoute

                // Update map display if showing map
                if (showMap) {
                    showRouteOnMap(newRoute)
                }

                // Log new route information
                logRouteInfo(newRoute)

                Log.d(TAG, "==========================")
            } else {
                Log.e(TAG, "Rerouting failed: ${routingError?.name}")
                Log.w(TAG, "Continuing with original route despite deviation")
            }

            // Reset flags and counter
            isReturningToRoute = false
            deviationCounter = 0
            Log.d(TAG, "Rerouting process completed")
        }
    }

    private fun logNavigationProgress(routeProgress: RouteProgress) {
        val maneuverProgressList = routeProgress.maneuverProgress
        if (maneuverProgressList.isNotEmpty()) {
            val nextManeuverProgress = maneuverProgressList[0]
            nextManeuverProgress?.let { progress ->
                val remainingDistance = progress.remainingDistanceInMeters / 1000.0

                val sectionProgressList = routeProgress.sectionProgress
                if (sectionProgressList.isNotEmpty()) {
                    val lastSection = sectionProgressList.last()
                    val totalRemainingDistance = lastSection.remainingDistanceInMeters / 1000.0
                    val totalRemainingTime = lastSection.remainingDuration.seconds / 60

                    Log.d(TAG, "=== NAVIGATION UPDATE ===")
                    Log.d(TAG, "Next maneuver: ${String.format("%.2f", remainingDistance)} km")
                    Log.d(
                        TAG,
                        "Total remaining: ${String.format("%.2f", totalRemainingDistance)} km"
                    )
                    Log.d(TAG, "ETA: $totalRemainingTime minutes")
                    Log.d(TAG, "========================")
                }
            }
        }
    }

    private fun setupLocationSource(locationListener: LocationListener, route: Route, isSimulator: Boolean) {
        if (!isSimulator) {
            locationEngine?.let { engine ->
                Log.d(TAG, "Setting up location source for real-time navigation")

                // Check if location engine is already running
                try {
                    // Add the navigation-specific location listener
                    engine.addLocationListener(locationListener)
                    Log.d(TAG, "Added navigation location listener to existing location engine")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add location listener: ${e.message}")
                    // Fallback to simulated navigation if GPS fails
                    Log.w(TAG, "Falling back to simulated navigation due to GPS failure")
                    isSimulated = true
                    isNavigationStarted = true
                    calculateRouteAndStartNavigation()
                }
            } ?: run {
                Log.w(TAG, "Location engine not available, falling back to simulated navigation")
                isSimulated = true
                isNavigationStarted = true
                calculateRouteAndStartNavigation()
            }
        } else {
            try {
                locationSimulator = LocationSimulator(route, LocationSimulatorOptions())
            } catch (e: InstantiationErrorException) {
                Log.e(TAG, "Initialization of LocationSimulator failed: ${e.error.name}")
                throw RuntimeException("Initialization of LocationSimulator failed: " + e.error.name)
            }

            locationSimulator?.let { simulator ->
                simulator.listener = locationListener
                simulator.start()
                Log.d(TAG, "Location simulation started")
            }
        }
    }

    fun refreshNetworkStatus() {
        Log.d(TAG, "=== REFRESHING NETWORK STATUS ===")
        Log.d(TAG, "Current state: Connected=$isNetworkConnected, Type=$currentConnectionType, Metered=$isConnectionMetered")

        // Use current state instead of re-scanning
        recommendRoutingStrategy(isNetworkConnected, isConnectionMetered)

        // Only check for actual changes via network monitor
        networkMonitor?.getCurrentNetworkState()?.let { state ->
            if (state.isConnected != isNetworkConnected ||
                state.connectionType != currentConnectionType ||
                state.isMetered != isConnectionMetered) {
                Log.d(TAG, "Network state changed during refresh, updating...")
                handleNetworkStateChange(state.isConnected, state.connectionType, state.isMetered)
            } else {
                Log.d(TAG, "No network state change detected during refresh")
            }
        }
        Log.d(TAG, "===============================")
    }

    fun cancelMapDownloads() {
        for (task in mapDownloaderTasks) {
            task.cancel()
        }
        Log.d(TAG, "Cancelled ${mapDownloaderTasks.size} download tasks")
        mapDownloaderTasks.clear()
        isDownloadInProgress = false
    }
    
    /**
     * Pause map downloads when app goes to background
     */
    private fun pauseMapDownloads() {
        if (isDownloadInProgress && mapDownloaderTasks.isNotEmpty()) {
            Log.d(TAG, "=== PAUSING MAP DOWNLOADS ===")
            Log.d(TAG, "App going to background, pausing ${mapDownloaderTasks.size} download tasks")
            
            for (task in mapDownloaderTasks) {
                try {
                    task.pause()
                    Log.d(TAG, "Download task paused successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error pausing download task: ${e.message}")
                }
            }
            
            // Update dialog to show paused state
            mapDownloadDialog?.showPaused()
            
            Log.d(TAG, "=============================")
        }
    }
    
    /**
     * Resume map downloads when app comes to foreground
     */
    private fun resumeMapDownloads() {
        if (isDownloadInProgress && mapDownloaderTasks.isNotEmpty()) {
            Log.d(TAG, "=== RESUMING MAP DOWNLOADS ===")
            Log.d(TAG, "App returned to foreground, resuming ${mapDownloaderTasks.size} download tasks")
            
            for (task in mapDownloaderTasks) {
                try {
                    task.resume()
                    Log.d(TAG, "Download task resumed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming download task: ${e.message}")
                }
            }
            
            // Update dialog to show resumed state
            mapDownloadDialog?.let { dialog ->
                "Downloading map data...".also { dialog.statusText?.text = it }
                "Map download resumed. Downloading Rwanda map data for offline navigation...".also { dialog.detailsText?.text = it }
            }
            
            Log.d(TAG, "==============================")
        }
    }
    
    /**
     * Check for map updates for installed regions
     */
    private fun checkForMapUpdates(installedRegion: com.here.sdk.maploader.InstalledRegion) {
        val currentTime = System.currentTimeMillis()
        
        // Check if enough time has passed since last update check
        if (currentTime - lastUpdateCheckTime < UPDATE_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Map update check skipped - last check was ${(currentTime - lastUpdateCheckTime) / (1000 * 60 * 60)} hours ago")
            return
        }
        
        Log.d(TAG, "=== CHECKING FOR MAP UPDATES ===")
        Log.d(TAG, "Checking updates for region: ${installedRegion.regionId.id}")
        
        mapDownloader?.let { downloader ->
            try {
                // Check if there are updates available
                downloader.getDownloadableRegions(LanguageCode.EN_US) { error, regions ->
                    if (error != null) {
                        Log.e(TAG, "Error checking for updates: $error")
                        return@getDownloadableRegions
                    }
                    
                    regions?.let { availableRegions ->
                        val updatedRegion = findRwandaRegion(availableRegions)
                        if (updatedRegion != null) {
                            val installedSize = installedRegion.sizeOnDiskInBytes
                            val availableSize = updatedRegion.sizeOnDiskInBytes
                            
                            Log.d(TAG, "Installed size: ${installedSize / (1024 * 1024)}MB")
                            Log.d(TAG, "Available size: ${availableSize / (1024 * 1024)}MB")
                            
                            // Check if there's a significant size difference (indicating updates)
                            val sizeDifference = kotlin.math.abs(availableSize - installedSize)
                            val sizeDifferenceMB = sizeDifference / (1024 * 1024)
                            
                            if (sizeDifferenceMB > 10) { // 10MB threshold for updates
                                Log.d(TAG, "Map update available! Size difference: ${sizeDifferenceMB}MB")
                                handleMapUpdateAvailable(updatedRegion, installedRegion)
                            } else {
                                Log.d(TAG, "No significant map updates available")
                            }
                        }
                    }
                    
                    lastUpdateCheckTime = currentTime
                    Log.d(TAG, "===============================")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during map update check: ${e.message}", e)
            }
        }
    }
    
    /**
     * Handle when a map update is available
     */
    private fun handleMapUpdateAvailable(updatedRegion: Region, installedRegion: com.here.sdk.maploader.InstalledRegion) {
        Log.d(TAG, "=== MAP UPDATE AVAILABLE ===")
        Log.d(TAG, "Current version size: ${installedRegion.sizeOnDiskInBytes / (1024 * 1024)}MB")
        Log.d(TAG, "Updated version size: ${updatedRegion.sizeOnDiskInBytes / (1024 * 1024)}MB")
        
        // Store the updated region for potential download
        rwandaRegion = updatedRegion
        
        // For now, just log the update availability
        // In a production app, you might want to:
        // 1. Show a notification to the user
        // 2. Schedule the update for later (e.g., during WiFi)
        // 3. Ask user permission before downloading
        
        Log.d(TAG, "Map update will be downloaded on next app restart or manual trigger")
        Log.d(TAG, "=============================")
    }
    


    
    /**
     * Proceed with map download if needed (fallback method)
     */
    private fun proceedWithMapDownload() {
        rwandaRegion?.let { region ->
            if (!isMapDataReady && !isDownloadInProgress) {
                Log.d(TAG, "Proceeding with map download as fallback")
                downloadRwandaMapWithRetry(region)
            }
        } ?: run {
            Log.w(TAG, "No Rwanda region available for download")
            proceedWithNavigation()
        }
    }
    
    /**
     * Check for map updates when network becomes available
     */
    private fun checkForMapUpdatesOnNetworkAvailable() {
        if (!isNetworkConnected) {
            Log.d(TAG, "Network not available, skipping map update check")
            return
        }
        
        Log.d(TAG, "=== NETWORK AVAILABLE - CHECKING MAP UPDATES ===")
        
        // If we don't have map data, try to download
        if (!isMapDataReady && rwandaRegion != null && !isDownloadInProgress) {
            Log.d(TAG, "Network available and no map data, starting download")
            downloadRwandaMapWithRetry(rwandaRegion!!)
            return
        }
        
        // If we have map data, check for updates
        mapDownloader?.let { downloader ->
            try {
                val installedRegions = downloader.installedRegions
                val rwandaInstalled = installedRegions.find { region ->
                    region.regionId.id.toString().contains("25726922", ignoreCase = true)
                }
                
                if (rwandaInstalled != null) {
                    Log.d(TAG, "Found installed Rwanda region, checking for updates")
                    checkForMapUpdates(rwandaInstalled)
                } else {
                    Log.d(TAG, "No installed Rwanda region found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking installed regions on network available: ${e.message}", e)
            }
        }
        
        Log.d(TAG, "=============================================")
    }

    /**
     * Handle camera toggle button press from XML layout
     */
    fun onCameraTogglePressed() {
        if (showMap && visualNavigator != null) {
            isCameraBehaviorEnabled = !isCameraBehaviorEnabled

            if (isCameraBehaviorEnabled) {
                visualNavigator?.cameraBehavior = DynamicCameraBehavior()
                Log.d(TAG, "Camera behavior enabled - Dynamic camera behavior active")
            } else {
                visualNavigator?.cameraBehavior = null
                Log.d(TAG, "Camera behavior disabled - Free camera mode")
            }

            // Update button text to reflect current state
            val button = findViewById<android.widget.Button>(R.id.cameraToggleButton)
            button.text = if (isCameraBehaviorEnabled) "Free Camera" else "Auto Camera"
        }
    }

    /**
     * Handle back button press from XML layout
     */
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        super.onBackPressed()
        Log.d(TAG, "Back button pressed, finishing Navigator activity")
        finish()
    }



    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            handleLowMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        handleLowMemory()
    }

    fun handleLowMemory() {
        SDKNativeEngine.getSharedInstance()?.purgeMemoryCaches(SDKNativeEngine.PurgeMemoryStrategy.FULL)
        initializeHERESDK(true)
    }


    override fun onDestroy() {
        super.onDestroy()

        isDestroyed = true

        Log.d(TAG, "Stopping network monitoring...")
        networkMonitor?.stopMonitoring()
        offlineSwitchRunnable?.let {
            handler.removeCallbacks(it)
        }

        handler.removeCallbacksAndMessages(null)
        stopCurrentNavigation()

        // Unregister booking bundle receiver
        try {
            bookingBundleReceiver?.let { unregisterReceiver(it) }
            bookingBundleReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering booking bundle receiver: ${e.message}")
        }
        
        // Unregister MQTT callback
        try {
            mqttService?.setBookingBundleCallback(null)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering MQTT booking bundle callback: ${e.message}")
        }

        // Reset trip progress tracker
        tripProgressTracker?.reset()

        // Clean up speed display
        resetSpeedDisplay()

        // Clean up booking dialog
        currentBookingDialog?.dismiss()
        currentBookingDialog = null
        
        // Clean up map download dialog
        dismissMapDownloadDialog()

        // Clean up NFC reader safely
        try {
            nfcReaderHelper?.disableNfcReader(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error disabling NFC reader during destroy: ${e.message}")
        }

        locationSimulator?.stop()
        dynamicRoutingEngine?.stop()
        cancelMapDownloads()

        // Reset download management variables
        isDownloadInProgress = false
        downloadRetryCount = 0
        downloadProgressCallback = null

        if (showMap) {
            visualNavigator?.stopRendering()
            mapView?.onDestroy()
        }

        visualNavigator?.routeProgressListener = null
        navigator?.routeProgressListener = null
        disposeHERESDK()
    }

    private fun disposeHERESDK() {
        SDKNativeEngine.getSharedInstance()?.dispose()
        SDKNativeEngine.setSharedInstance(null)
    }


    override fun onResume() {
        super.onResume()
        
        // Notify MQTT service that app is in foreground
        mqttService?.onAppForeground()
        
        // Resume map downloads when app comes to foreground
        resumeMapDownloads()
        
        if (showMap) {
            mapView?.onResume()
        } else if (!isDestroyed) {
            // Re-enable NFC reader in headless mode (only if not destroyed)
            nfcReaderHelper?.let { helper ->
                if (helper.isNfcSupported() && helper.isNfcEnabled()) {
                    helper.enableNfcReader(this)
                }
            }
        }
        // Only refresh network status if we don't have initial state from MainActivity
        if (!isNetworkConnected && currentConnectionType == "UNKNOWN") {
            Log.d(TAG, "No initial network state from MainActivity, refreshing...")
            refreshNetworkStatus()
        } else {
            Log.d(TAG, "Using network state from MainActivity, no refresh needed")
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // Notify MQTT service that app is in background
        mqttService?.onAppBackground()
        
        // Pause map downloads when app goes to background
        pauseMapDownloads()
        
        if (showMap) {
            mapView?.onPause()
        } else if (!isDestroyed) {
            // Disable NFC reader in headless mode when paused (only if not destroyed)
            try {
                nfcReaderHelper?.disableNfcReader(this)
            } catch (e: Exception) {
                Log.w(TAG, "Error disabling NFC reader during pause: ${e.message}")
            }
        }
    }

    // Import methods remain the same but now work with trip data context
    fun importRouteFromHandle(routeHandle: String, routeOptions: CarOptions = CarOptions()) {
        Log.d(TAG, "Importing route from handle: $routeHandle")

        onlineRoutingEngine?.importRoute(
            RouteHandle(routeHandle),
            RefreshRouteOptions(TransportMode.CAR)
        ) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                val importedRoute = routes[0]
                currentRoute = importedRoute

                Log.d(TAG, "Route imported successfully from handle")
                logRouteInfo(importedRoute)

                if (showMap) {
                    showRouteOnMap(importedRoute)
                }

                startGuidance(importedRoute)
            } else {
                Log.e(TAG, "Route import failed: ${routingError?.name}")
            }
        }
    }

    fun importRouteFromCoordinates(
        coordinates: List<GeoCoordinates>,
        routeOptions: CarOptions = CarOptions()
    ) {
        Log.d(TAG, "Importing route from ${coordinates.size} coordinates")

        val locations = coordinates.map { Location(it) }

        onlineRoutingEngine?.importRoute(locations, routeOptions) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                val importedRoute = routes[0]
                currentRoute = importedRoute

                // Set route in progress tracker
                tripProgressTracker?.setCurrentRoute(importedRoute)

                Log.d(TAG, "Route imported successfully from coordinates")
                logRouteInfo(importedRoute)

                if (showMap) {
                    showRouteOnMap(importedRoute)
                }

                startGuidance(importedRoute)
            } else {
                Log.e(TAG, "Route import from coordinates failed: ${routingError?.name}")
            }
        }
    }

    // Trip progress tracking methods
    fun getCurrentTripProgress(): Map<Int, com.gocavgo.validator.dataclass.WaypointProgress>? {
        return try {
            tripProgressTracker?.getCurrentProgress()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current trip progress: ${e.message}", e)
            null
        }
    }

    fun getReachedWaypoints(): Set<Int>? {
        return try {
            tripProgressTracker?.getReachedWaypoints()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reached waypoints: ${e.message}", e)
            null
        }
    }

    @SuppressLint("DefaultLocale")
    fun getTripProgressSummary(): String {
        return try {
            val progress = tripProgressTracker?.getCurrentProgress()
            val reached = tripProgressTracker?.getReachedWaypoints()

            buildString {
                appendLine("=== TRIP PROGRESS SUMMARY ===")
                appendLine("Trip ID: ${tripResponse?.id}")
                appendLine("Total waypoints: ${tripResponse?.waypoints?.size}")
                appendLine("Reached waypoints: ${reached?.size ?: 0}")
                appendLine("Current progress: ${progress?.size ?: 0} waypoints tracked")
                appendLine("Deviation events: $deviationCounter")
                appendLine("Rerouting in progress: $isReturningToRoute")

                progress?.forEach { (waypointId, waypointProgress) ->
                    appendLine("  - ${waypointProgress.locationName}: ${String.format("%.1f", waypointProgress.remainingDistanceInMeters)}m remaining")
                    waypointProgress.currentSpeedInMetersPerSecond?.let { speed ->
                        appendLine("    Current speed: ${String.format("%.2f", speed)} m/s")
                    }
                    waypointProgress.currentLatitude?.let { lat ->
                        waypointProgress.currentLongitude?.let { lng ->
                            appendLine("    Position: ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}")
                        }
                    }
                }
                appendLine("=============================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting trip progress summary: ${e.message}", e)
            "Error: Unable to get trip progress summary"
        }
    }

    private var lastMqttUpdateTime = 0L
    private val MQTT_UPDATE_INTERVAL = 30000L // 30 seconds
    private var lastMqttHealthCheckTime = 0L
    private val MQTT_HEALTH_CHECK_INTERVAL = 120000L // 2 minutes
    private var lastMqttReconnectAttempt = 0L

    /**
     * Send periodic MQTT updates to keep the backend informed of trip progress
     */
    private fun sendPeriodicMqttUpdate() {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastMqttUpdateTime > MQTT_UPDATE_INTERVAL) {
                // Check MQTT health before sending
                if (mqttService?.isHealthy() == true) {
                    tripProgressTracker?.sendPeriodicProgressUpdate()
                    lastMqttUpdateTime = currentTime
                    Log.d(TAG, "Periodic MQTT update sent")
                } else {
                    // Log detailed MQTT status for debugging
                    val status = mqttService?.getServiceStatus()
                    Log.w(TAG, "MQTT service not healthy, skipping periodic update")
                    Log.d(TAG, "MQTT Status: $status")
                    
                    // Try to fix inconsistent state first
                    mqttService?.checkAndFixInconsistentState()
                    
                    // Only try to reconnect if we haven't tried recently
                    val lastReconnectAttempt = lastMqttReconnectAttempt
                    if (currentTime - lastReconnectAttempt > 30000) { // Wait 30 seconds between reconnect attempts
                        Log.d(TAG, "Attempting MQTT reconnection...")
                        mqttService?.forceReconnect()
                        lastMqttReconnectAttempt = currentTime
                    } else {
                        Log.d(TAG, "MQTT reconnection already attempted recently, waiting...")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending periodic MQTT update: ${e.message}", e)
        }
    }
    
    /**
     * Check MQTT service health and log status
     */
    private fun checkMqttHealth() {
        try {
            mqttService?.let { mqtt ->
                val status = mqtt.getServiceStatus()
                val isHealthy = mqtt.isHealthy()
                
                Log.d(TAG, "=== MQTT SERVICE HEALTH CHECK ===")
                Log.d(TAG, "Healthy: $isHealthy")
                Log.d(TAG, "Active: ${status["isActive"]}")
                Log.d(TAG, "Connected: ${status["isConnected"]}")
                Log.d(TAG, "Network Available: ${status["isNetworkAvailable"]}")
                Log.d(TAG, "App in Foreground: ${status["isAppInForeground"]}")
                Log.d(TAG, "Reconnect Attempts: ${status["reconnectAttempts"]}")
                Log.d(TAG, "Queued Messages: ${status["queuedMessages"]}")
                Log.d(TAG, "Client State: ${status["clientState"]}")
                Log.d(TAG, "===============================")
                
                if (!isHealthy) {
                    Log.w(TAG, "MQTT service is not healthy, attempting recovery...")
                    mqtt.forceReconnect()
                }
            } ?: run {
                Log.w(TAG, "MQTT service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking MQTT health: ${e.message}", e)
        }
    }
    
    /**
     * Check MQTT health periodically
     */
    private fun checkMqttHealthPeriodically() {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastMqttHealthCheckTime > MQTT_HEALTH_CHECK_INTERVAL) {
                checkMqttHealth()
                lastMqttHealthCheckTime = currentTime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic MQTT health check: ${e.message}", e)
        }
    }

    /**
     * Update trip status to in_progress as soon as guidance starts
     */
    private fun updateTripStatusToInProgress(callback: (Boolean) -> Unit = {}) {
        try {
            tripResponse?.let { trip ->
                val currentStatus = trip.status
                val normalizedCurrentStatus = TripStatus.normalizeStatus(currentStatus)
                val newStatus = when (normalizedCurrentStatus) {
                    TripStatus.SCHEDULED.value -> TripStatus.IN_PROGRESS.value
                    TripStatus.PENDING.value -> TripStatus.IN_PROGRESS.value
                    else -> normalizedCurrentStatus
                }

                if (newStatus != normalizedCurrentStatus) {
                    lifecycleScope.launch {
                        try {
                            databaseManager.updateTripStatus(trip.id, newStatus)
                            Log.d(TAG, "Trip ${trip.id} status updated from $currentStatus to $newStatus")

                            // Update local trip status
                            tripResponse = trip.copy(status = newStatus)

                            // Call callback with success
                            callback(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update trip status: ${e.message}", e)
                            callback(false)
                        }
                    }
                } else {
                    Log.d(TAG, "Trip ${trip.id} already has status: $currentStatus")
                    callback(true) // Already has correct status
                }
            } ?: run {
                Log.e(TAG, "No trip response available for status update")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating trip status to in_progress: ${e.message}", e)
            callback(false)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBookingBundleReceiver() {
        try {
            if (bookingBundleReceiver != null) return
            bookingBundleReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(TAG, "Broadcast received: ${intent?.action}")
                    if (intent == null) return
                    
                    if (intent.action == MqttService.ACTION_BOOKING_BUNDLE_SAVED) {
                        val tripId = intent.getStringExtra("trip_id") ?: "unknown"
                        val passengerName = intent.getStringExtra("passenger_name") ?: "Passenger"
                        val pickup = intent.getStringExtra("pickup") ?: "Unknown"
                        val dropoff = intent.getStringExtra("dropoff") ?: "Unknown"
                        val numTickets = intent.getIntExtra("num_tickets", 1)
                        val isPaid = intent.getBooleanExtra("is_paid", false)
                        
                        Log.d(TAG, "Booking bundle broadcast received for trip $tripId: $passengerName, $pickup -> $dropoff, $numTickets tickets, paid: $isPaid")
                        
                        runOnUiThread {
                            try {
                                showBookingBundleOverlay(passengerName, pickup, dropoff, numTickets, isPaid)
                                playBundleNotificationSound()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling booking bundle broadcast: ${e.message}", e)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(com.gocavgo.validator.service.MqttService.ACTION_BOOKING_BUNDLE_SAVED)
            registerReceiver(bookingBundleReceiver, filter)
            Log.d(TAG, "Registered booking bundle receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register booking bundle receiver: ${e.message}", e)
        }
    }

    private fun showBookingBundleOverlay(
        passengerName: String,
        pickup: String,
        dropoff: String,
        numTickets: Int,
        isPaid: Boolean
    ) {
        try {
            Log.d(TAG, "Showing booking bundle overlay for: $passengerName, $pickup -> $dropoff, tickets: $numTickets, paid: $isPaid")
            
            // Dismiss any existing booking bundle dialog
            val existingDialog = supportFragmentManager.findFragmentByTag("BookingBundleDialog")
            if (existingDialog is BookingBundleDialog) {
                existingDialog.dismiss()
            }
            
            // Create and show new dialog
            val dialog = BookingBundleDialog.newInstance(
                passengerName = passengerName,
                pickupLocation = pickup,
                dropoffLocation = dropoff,
                numTickets = numTickets,
                isPaid = isPaid
            )
            
            dialog.show(supportFragmentManager, "BookingBundleDialog")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing booking bundle overlay: ${e.message}", e)
            
            // Fallback to simple toast notification
            try {
                val status = if (isPaid) "PAID" else "UNPAID"
                val message = "$passengerName: $pickup → $dropoff ($numTickets tickets, $status)"
                android.widget.Toast.makeText(this, "New Booking: $message", android.widget.Toast.LENGTH_LONG).show()
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Even fallback toast failed: ${fallbackError.message}")
            }
        }
    }
    
    private fun registerMqttBookingBundleCallback() {
        try {
            mqttService?.setBookingBundleCallback { tripId, passengerName, pickup, dropoff, numTickets, isPaid ->
                Log.d(TAG, "MQTT callback received for booking bundle: trip=$tripId, passenger=$passengerName")
                
                runOnUiThread {
                    try {
                        showBookingBundleOverlay(passengerName, pickup, dropoff, numTickets, isPaid)
                        playBundleNotificationSound()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling MQTT booking bundle callback: ${e.message}", e)
                    }
                }
            }
            Log.d(TAG, "MQTT booking bundle callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MQTT booking bundle callback: ${e.message}", e)
        }
    }



    private fun playBundleNotificationSound() {
        try {
            Log.d(TAG, "Playing booking bundle notification sound")
            
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
                        Log.w(TAG, "Error stopping ringtone: ${e.message}")
                    }
                }, 2000)
                
            } else {
                Log.w(TAG, "Notification ringtone is null, trying alarm sound")
                
                // Fallback to alarm sound if notification is not available
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val alarmRingtone = RingtoneManager.getRingtone(this, alarmUri)
                alarmRingtone?.play()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error playing notification sound: ${e.message}")
            
            // Last resort: Try system beep
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
            } catch (beepError: Exception) {
                Log.e(TAG, "Even system beep failed: ${beepError.message}")
            }
        }
    }
}
