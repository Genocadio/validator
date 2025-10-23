package com.gocavgo.validator.navigator

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
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
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.ui.components.NetworkStatusIndicator
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
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.Route
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import com.here.sdk.core.GeoCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HeadlessNavigActivity : ComponentActivity() {
    companion object {
        private const val TAG = "HeadlessNavigActivity"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_IS_SIMULATED = "is_simulated"
    }

    private lateinit var databaseManager: DatabaseManager
    private var tripResponse: TripResponse? = null
    private var isSimulated: Boolean = true
    private var messageViewText by mutableStateOf("Initializing headless navigation...")

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
    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyed = false
    
    // Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    private var isConnected by mutableStateOf(true)
    private var connectionType by mutableStateOf("UNKNOWN")
    private var isMetered by mutableStateOf(true)
    
    // HERE SDK offline mode state
    private var pendingOfflineMode: Boolean? = null
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d(TAG, "=== HEADLESS NAVIGATION ACTIVITY STARTED ===")

        // Initialize database manager
        databaseManager = DatabaseManager.getInstance(this)

        // Get trip ID and simulation mode from intent
        val tripId = intent.getIntExtra(EXTRA_TRIP_ID, -1)
        isSimulated = intent.getBooleanExtra(EXTRA_IS_SIMULATED, true)

        Log.d(TAG, "Trip ID: $tripId")
        Log.d(TAG, "Is Simulated: $isSimulated")

        if (tripId == -1) {
            Log.e(TAG, "No trip ID provided. Cannot start headless navigation.")
            messageViewText = "Error: No trip ID provided"
            finish()
            return
        }

        // Initialize UI first
        setContent {
            ValidatorTheme {
                HeadlessNavigationScreen(
                    messageText = messageViewText,
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
                    isConnected = isConnected,
                    connectionType = connectionType,
                    isMetered = isMetered,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Load trip data and initialize navigation
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Initialize HERE SDK first
                initializeHERESDK()

                // Fetch trip from database
                tripResponse = databaseManager.getTripById(tripId)
                if (tripResponse == null) {
                    Log.e(TAG, "Trip with ID $tripId not found in database. Cannot start headless navigation.")
                    withContext(Dispatchers.Main) {
                        messageViewText = "Error: Trip not found"
                        finish()
                    }
                    return@launch
                }

                Log.d(TAG, "Trip loaded successfully: ${tripResponse?.id}")
                Log.d(TAG, "Origin: ${tripResponse?.route?.origin?.google_place_name}")
                Log.d(TAG, "Destination: ${tripResponse?.route?.destination?.google_place_name}")

                // Get MQTT service instance
                mqttService = MqttService.getInstance()

                // Switch to main thread for UI operations
                withContext(Dispatchers.Main) {
                    initializeHeadlessNavigation()
                    
                    // Initialize network monitoring
                    initializeNetworkMonitoring()
                    
                    // Initialize NFC reader after trip data is loaded
                    initializeNFCReader()
                    
                    // Register MQTT booking bundle receiver
                    registerBookingBundleReceiver()
                    registerMqttBookingBundleCallback()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    messageViewText = "Error: ${e.message}"
                }
            }
        }
    }

    private fun initializeHERESDK(lowMEm: Boolean = false) {
        try {
            // Check if SDK is already initialized to avoid duplicate initialization
            if (SDKNativeEngine.getSharedInstance() != null) {
                Log.d(TAG, "HERE SDK already initialized, skipping")
                return
            }
            
            val accessKeyID = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_ID
            val accessKeySecret = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_SECRET
            val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
            val options = SDKOptions(authenticationMode)
            if(lowMEm) {
                options.lowMemoryMode = true
                Log.d(TAG, "Initialised in Low memory mode")
            }
            
            // Initialize SDK
            SDKNativeEngine.makeSharedInstance(this, options)
            Log.d(TAG, "HERE SDK initialized successfully")
            
            // Apply pending offline mode if any
            pendingOfflineMode?.let { offlineMode ->
                try {
                    SDKNativeEngine.getSharedInstance()?.setOfflineMode(offlineMode)
                    Log.d(TAG, "Applied pending HERE SDK offline mode: $offlineMode")
                    pendingOfflineMode = null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply pending offline mode: ${e.message}", e)
                }
            }
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of HERE SDK failed: ${e.error.name}", e)
            throw RuntimeException("Initialization of HERE SDK failed: " + e.error.name)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during HERE SDK initialization: ${e.message}", e)
            throw RuntimeException("Unexpected error during HERE SDK initialization: ${e.message}")
        }
    }

    private fun initializeHeadlessNavigation() {
        try {
            Log.d(TAG, "Initializing headless navigation components...")

            // Initialize trip section validator
            tripSectionValidator = TripSectionValidator(this)

            // Initialize routing engines
            initializeRoutingEngines()

            // Initialize navigator
            initializeNavigator()

            // Initialize location engine
            initializeLocationEngine()

            Log.d(TAG, "Headless navigation initialized successfully")
//            messageViewText = "Headless navigation is now active"

            // Update passenger counts
            updatePassengerCounts()

            // Start navigation
            startNavigation()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize headless navigation: ${e.message}", e)
            messageViewText = "Error initializing navigation: ${e.message}"
        }
    }

    private fun initializeNetworkMonitoring() {
        Log.d(TAG, "Initializing network monitoring...")

        // Check if we have network permissions
        if (!NetworkUtils.hasNetworkPermissions(this)) {
            Log.w(TAG, "Network permissions not available, using basic monitoring")
            return
        }

        networkMonitor = NetworkMonitor(this) { connected, type, metered ->
            Log.d(TAG, "Network state changed: connected=$connected, type=$type, metered=$metered")
            
            // Update UI state
            isConnected = connected
            connectionType = type
            isMetered = metered
            
            // Update HERE SDK offline mode
            updateHERESDKOfflineMode(connected)
        }

        networkMonitor?.startMonitoring()
        Log.d(TAG, "Network monitoring started")
    }

    private fun initializeRoutingEngines() {
        try {
            onlineRoutingEngine = RoutingEngine()
            offlineRoutingEngine = OfflineRoutingEngine()
            Log.d(TAG, "Routing engines initialized successfully")
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of routing engines failed: ${e.error.name}", e)
            throw RuntimeException("Initialization of routing engines failed: " + e.error.name)
        }
    }

    private fun initializeNavigator() {
        try {
            navigator = Navigator()
            Log.d(TAG, "Headless Navigator initialized successfully")
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of Navigator failed: ${e.error.name}", e)
            throw RuntimeException("Initialization of Navigator failed: " + e.error.name)
        }
    }

    private fun initializeLocationEngine() {
        try {
            locationEngine = LocationEngine()
            Log.d(TAG, "LocationEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of LocationEngine failed: ${e.error.name}", e)
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
            Log.d(TAG, "Starting location engine for real-time navigation...")

            val locationStatusListener: LocationStatusListener = object : LocationStatusListener {
                override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
                    Log.d(TAG, "Location engine status: ${locationEngineStatus.name}")
                }

                override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
                    for (feature in features) {
                        Log.w(TAG, "Location feature not available: ${feature.name}")
                    }
                }
            }

            val locationListener = LocationListener { location ->
                Log.d(TAG, "Received location: ${location.coordinates.latitude}, ${location.coordinates.longitude}")
                currentUserLocation = location

                // Start navigation once we have a location (only once)
                if (!isNavigationStarted) {
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
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
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
                }
            }
            val waypoints = createWaypointsFromTrip(trip, origin)

            val carOptions = CarOptions().apply {
                routeOptions.optimizeWaypointsOrder = false
                routeOptions.enableTolls = true
                routeOptions.alternatives = 1
            }

            val routingEngine = onlineRoutingEngine ?: offlineRoutingEngine!!

            Log.d(TAG, "Calculating route for headless navigation")
            Log.d(TAG, "Trip ID: ${trip.id}")
            Log.d(TAG, "Waypoints: ${waypoints.size}")

            routingEngine.calculateRoute(waypoints, carOptions) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val route = routes[0]
                    currentRoute = route

                    Log.d(TAG, "Route calculated successfully for headless navigation")
                    startHeadlessGuidance(route)
                } else {
                    Log.e(TAG, "Route calculation failed: ${routingError?.name}")
                }
            }
        } ?: run {
            Log.e(TAG, "No trip data available for route calculation")
        }
    }

    private fun createWaypointsFromTrip(trip: TripResponse, origin: Waypoint? = null): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        
        if (origin != null) {
            waypoints.add(origin)
        }

        // Add origin if not using device location
        if (origin == null) {
            val originWaypoint = Waypoint(
                GeoCoordinates(
                    trip.route.origin.latitude,
                    trip.route.origin.longitude
                )
            ).apply {
                type = WaypointType.STOPOVER
            }
            waypoints.add(originWaypoint)
        }

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

        Log.d(TAG, "Created ${waypoints.size} waypoints from trip data")
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
                val isVerified = tripSectionValidator.verifyRouteSections(
                    tripResponse = trip,
                    route = route,
                    isSimulated = isSimulated,
                    deviceLocation = currentUserLocation?.coordinates
                )
                Log.d(TAG, "Route verification result: $isVerified")
            }
            
            // Set up location source
            setupLocationSource(nav, route, isSimulated)
            
            messageViewText = "Headless navigation started"
            Log.d(TAG, "Headless navigation started")
        }
    }

    private fun setupHeadlessListeners(nav: Navigator) {
        nav.routeProgressListener = RouteProgressListener { routeProgress ->
            try {
                Log.d(TAG, "Route progress update received")
                
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
                            val totalRemainingTime = lastSection.remainingDuration.seconds / 60

                            Log.d(TAG, "Next maneuver: ${String.format("%.2f", remainingDistance)} km")
                            Log.d(TAG, "Total remaining: ${String.format("%.2f", totalRemainingDistance)} km")
                            Log.d(TAG, "Speed: ${String.format("%.1f", currentSpeedInMetersPerSecond * 3.6)} km/h")

                            val speedInKmh = currentSpeedInMetersPerSecond * 3.6
                            "Next: ${String.format("%.1f", remainingDistance)} km | Speed: ${String.format("%.1f", speedInKmh)} km/h".also { messageViewText = it }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in route progress listener: ${e.message}", e)
            }
        }

        nav.navigableLocationListener = NavigableLocationListener { currentNavigableLocation ->
            try {
                val lastMapMatchedLocation = currentNavigableLocation.mapMatchedLocation
                if (lastMapMatchedLocation == null) {
                    Log.w(TAG, "No map-matched location available, You are Off-road")
                    return@NavigableLocationListener
                }

                val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond ?: 0.0
                val accuracy = currentNavigableLocation.originalLocation.speedAccuracyInMetersPerSecond ?: 0.0
                currentSpeedInMetersPerSecond = speed
                Log.d(TAG, "Driving speed (m/s): $speed plus/minus an accuracy of: $accuracy")
            } catch (e: Exception) {
                Log.e(TAG, "Error in navigable location listener: ${e.message}", e)
            }
        }

        // Notifies on route deviation events
        nav.routeDeviationListener = RouteDeviationListener { routeDeviation ->
            try {
                handleRouteDeviation(routeDeviation)
            } catch (e: Exception) {
                Log.e(TAG, "Error in route deviation listener: ${e.message}", e)
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
            Log.d(TAG, "Rerouting already in progress")
            return
        }
        
        if (distanceInMeters > DEVIATION_THRESHOLD_METERS && deviationCounter >= MIN_DEVIATION_EVENTS) {
            Log.d(TAG, "=== ROUTE DEVIATION DETECTED ===")
            Log.d(TAG, "Distance: ${distanceInMeters}m - Starting reroute")
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
                    Log.d(TAG, "Headless rerouting successful")
                    runOnUiThread {
                        messageViewText = "Route recalculated - back on track"
                    }
                } else {
                    Log.e(TAG, "Rerouting failed: ${routingError?.name}")
                }
                isReturningToRoute = false
                deviationCounter = 0
            }
        }
    }

    private fun setupLocationSource(locationListener: LocationListener, route: Route, isSimulator: Boolean) {
        if (!isSimulator) {
            locationEngine?.let { engine ->
                Log.d(TAG, "Setting up location source for real-time navigation")
                try {
                    engine.addLocationListener(locationListener)
                    Log.d(TAG, "Added navigation location listener to existing location engine")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add location listener: ${e.message}")
                }
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

    // NFC and Booking Methods
    
    private fun initializeNFCReader() {
        try {
            nfcReaderHelper = NFCReaderHelper(
                context = this,
                onTagRead = { tag -> handleNFCTagRead(tag) },
                onError = { error -> Log.e(TAG, "NFC Error: $error") }
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

    private fun handleNFCTagRead(tag: Tag) {
        try {
            val nfcId = tag.id.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "=== NFC TAG READ ===")
            Log.d(TAG, "NFC ID: $nfcId")
            Log.d(TAG, "Activity destroyed: $isDestroyed")

            if (isDestroyed) {
                Log.w(TAG, "Activity is destroyed, ignoring NFC tag read")
                return
            }

            if (tripResponse == null) {
                Log.e(TAG, "Trip response is null! Cannot process booking.")
                return
            }

            Log.d(TAG, "Trip response available: ${tripResponse?.id}")
            Log.d(TAG, "Waypoints count: ${tripResponse?.waypoints?.size}")

            // Check for existing booking first
            Log.d(TAG, "Checking for existing booking...")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val existingBookingResult = databaseManager.getExistingBookingByNfcTag(nfcId)

                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) {
                            when (existingBookingResult) {
                                is com.gocavgo.validator.service.ExistingBookingResult.Found -> {
                                    Log.d(TAG, "Found existing booking, showing ticket display")
                                    showExistingTicketDialog(existingBookingResult.booking, existingBookingResult.payment, existingBookingResult.ticket)
                                }
                                is com.gocavgo.validator.service.ExistingBookingResult.NotFound -> {
                                    Log.d(TAG, "No existing booking found, showing destination selection")
                                    showDestinationSelectionDialog(nfcId)
                                }
                                is com.gocavgo.validator.service.ExistingBookingResult.Error -> {
                                    Log.e(TAG, "Error checking existing booking: ${existingBookingResult.message}")
                                    showBookingError("Error checking existing booking: ${existingBookingResult.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking existing booking: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) {
                            showBookingError("Error checking existing booking: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling NFC tag: ${e.message}", e)
        }
    }

    private fun showDestinationSelectionDialog(nfcId: String) {
        Log.d(TAG, "=== SHOWING DESTINATION SELECTION DIALOG ===")
        Log.d(TAG, "NFC ID: $nfcId")

        val availableDestinations = getAvailableWaypoints()
        val currentLocation = getCurrentLocationName()

        Log.d(TAG, "Available destinations count: ${availableDestinations.size}")
        availableDestinations.forEach { dest ->
            val displayName = getLocationDisplayName(dest.location)
            Log.d(TAG, "  - $displayName (final: ${dest.isFinalDestination})")
        }

        if (availableDestinations.isEmpty()) {
            Log.w(TAG, "No available destinations found!")
            showBookingError("All waypoints have been passed. Trip is complete.")
            return
        }

        // For now, automatically select the first available destination
        // In a full implementation, you would show a dialog
        val selectedDestination = availableDestinations.first()
        Log.d(TAG, "Auto-selecting first destination: ${getLocationDisplayName(selectedDestination.location)}")
        processBooking(nfcId, selectedDestination)
    }

    private fun processBooking(nfcId: String, destination: AvailableDestination) {
        try {
            val destinationDisplayName = getLocationDisplayName(destination.location)
            val currentLocation = getCurrentLocationName()
            val priceText = calculateDestinationPrice(destination)

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
                                Log.d(TAG, "=== COMPLETE BOOKING CREATED ===")
                                Log.d(TAG, "Booking ID: ${result.bookingId}")
                                Log.d(TAG, "Payment ID: ${result.paymentId}")
                                Log.d(TAG, "Ticket ID: ${result.ticketId}")
                                Log.d(TAG, "Ticket Number: ${result.ticketNumber}")
                                Log.d(TAG, "QR Code: ${result.qrCode}")
                                Log.d(TAG, "===============================")

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
                                Log.e(TAG, "Failed to create complete booking: ${result.message}")
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
                    Log.e(TAG, "Error creating complete booking: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        bookingFailureMessage = "Error creating booking: ${e.message}"
                        showBookingFailure = true

                        // Auto-dismiss after 3 seconds
                        handler.postDelayed({
                            showBookingFailure = false
                        }, 3000)
                    }
                }
            }
        } ?: run {
            Log.e(TAG, "No trip response available for booking creation")
            showBookingError("No trip data available")
        }
    }

    // Ticket Verification Methods

    private fun addDigit(digit: String) {
        if (isValidationInProgress) {
            Log.d(TAG, "Validation in progress, ignoring input: $digit")
            return
        }
        
        if (currentInput.length < 6) {
            currentInput += digit
            Log.d(TAG, "Added digit: $digit, Current input: $currentInput")
            
            // Check if we have 6 digits
            if (currentInput.length == 6) {
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
            Log.d(TAG, "Deleted last digit, Current input: $currentInput")
        } else {
            Log.d(TAG, "No digits to delete")
        }
    }

    private fun forceClearInput() {
        currentInput = ""
        isValidationInProgress = false
        Log.d(TAG, "Input force cleared")
    }

    private fun validateTicketByNumber(ticketNumber: String) {
        Log.d(TAG, "=== VALIDATING TICKET ===")
        Log.d(TAG, "Ticket Number: $ticketNumber")
        
        // Set validation flag
        isValidationInProgress = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ticket = databaseManager.getTicketByNumber(ticketNumber)
                
                withContext(Dispatchers.Main) {
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
                        
                        // Fetch booking data to get user name
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val booking = databaseManager.getBookingById(ticket.booking_id)
                                withContext(Dispatchers.Main) {
                                    if (booking != null) {
                                        // Show enhanced success prompt with user and location info
                                        validationSuccessTicket = "${booking.user_name} - ${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
                                    } else {
                                        // Fallback to basic ticket info
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
                                Log.e(TAG, "Error fetching booking data: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    // Fallback to basic ticket info
                                    validationSuccessTicket = "${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
                                    showValidationSuccess = true
                                    
                                    // Auto-dismiss after 3 seconds
                                    handler.postDelayed({
                                        showValidationSuccess = false
                                        currentInput = ""
                                        isValidationInProgress = false
                                    }, 3000)
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "=== TICKET NOT FOUND ===")
                        Log.d(TAG, "No ticket found with number: $ticketNumber")
                        Log.d(TAG, "=========================")
                        
                        // Show error prompt
                        validationFailureMessage = "Invalid ticket number"
                        showValidationFailure = true
                        
                        // Auto-dismiss after 3 seconds
                        handler.postDelayed({
                            showValidationFailure = false
                            currentInput = ""
                            isValidationInProgress = false
                        }, 3000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error validating ticket: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    validationFailureMessage = "Error validating ticket: ${e.message}"
                    showValidationFailure = true
                    
                    // Auto-dismiss after 3 seconds
                    handler.postDelayed({
                        showValidationFailure = false
                        currentInput = ""
                        isValidationInProgress = false
                    }, 3000)
                }
            }
        }
    }

    private fun updatePassengerCounts() {
        tripResponse?.let { trip ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (pickups, dropoffs) = if (trip.status == "SCHEDULED") {
                        // Show origin pickups for scheduled trips
                        val originId = trip.route.origin.id
                        databaseManager.getPassengerCountsForLocation(trip.id, originId)
                    } else {
                        // Show next waypoint pickups/dropoffs for in-progress trips
                        val nextWaypoint = trip.waypoints.firstOrNull { it.is_next }
                        if (nextWaypoint != null) {
                            databaseManager.getPassengerCountsForLocation(trip.id, nextWaypoint.location_id)
                        } else {
                            Pair(0, 0)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        pickupCount = pickups
                        dropoffCount = dropoffs
                        
                        // Update waypoint name
                        nextWaypointName = if (trip.status == "SCHEDULED") {
                            trip.route.origin.custom_name ?: trip.route.origin.google_place_name
                        } else {
                            val nextWaypoint = trip.waypoints.firstOrNull { it.is_next }
                            nextWaypoint?.location?.custom_name ?: nextWaypoint?.location?.google_place_name ?: "No upcoming waypoint"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating passenger counts: ${e.message}", e)
                }
            }
        }
    }

    // Helper Methods

    private fun getAvailableWaypoints(): List<AvailableDestination> {
        tripResponse?.let { trip ->
            val allWaypoints = trip.waypoints
            val availableWaypoints = allWaypoints.filter { !it.is_passed }.sortedBy { it.order }

            Log.d(TAG, "=== DEBUGGING AVAILABLE DESTINATIONS ===")
            Log.d(TAG, "Total waypoints: ${allWaypoints.size}")
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
                    id = -1, // Special ID for final destination
                    locationId = trip.route.destination.id,
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

    private fun getCurrentLocationId(): Int {
        tripResponse?.let { trip ->
            // Find the last passed waypoint to determine current location ID
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

    private fun showBookingError(errorMessage: String) {
        bookingFailureMessage = errorMessage
        showBookingFailure = true

        // Auto-dismiss after 3 seconds
        handler.postDelayed({
            showBookingFailure = false
        }, 3000)
    }

    private fun showExistingTicketDialog(
        booking: com.gocavgo.validator.database.BookingEntity,
        payment: com.gocavgo.validator.database.PaymentEntity,
        ticket: com.gocavgo.validator.database.TicketEntity
    ) {
        Log.d(TAG, "=== EXISTING TICKET FOUND ===")
        Log.d(TAG, "Ticket Number: ${ticket.ticket_number}")
        Log.d(TAG, "From: ${ticket.pickup_location_name}")
        Log.d(TAG, "To: ${ticket.dropoff_location_name}")
        Log.d(TAG, "Status: ${if (ticket.is_used) "USED" else "VALID"}")
        Log.d(TAG, "=============================")

        // Show validation success for existing ticket
        validationSuccessTicket = "${booking.user_name} - ${ticket.pickup_location_name} → ${ticket.dropoff_location_name}"
        showValidationSuccess = true

        // Auto-dismiss after 3 seconds
        handler.postDelayed({
            showValidationSuccess = false
        }, 3000)
    }

    // MQTT Notification Methods

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
                                showMqttBookingNotification(passengerName, pickup, dropoff, numTickets, isPaid)
                                playBundleNotificationSound()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling booking bundle broadcast: ${e.message}", e)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(MqttService.ACTION_BOOKING_BUNDLE_SAVED)
            registerReceiver(bookingBundleReceiver, filter)
            Log.d(TAG, "Registered booking bundle receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register booking bundle receiver: ${e.message}", e)
        }
    }

    private fun registerMqttBookingBundleCallback() {
        try {
            mqttService?.setBookingBundleCallback { tripId, passengerName, pickup, dropoff, numTickets, isPaid ->
                Log.d(TAG, "MQTT callback received for booking bundle: trip=$tripId, passenger=$passengerName")
                
                runOnUiThread {
                    try {
                        showMqttBookingNotification(passengerName, pickup, dropoff, numTickets, isPaid)
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

    private fun showMqttBookingNotification(
        passengerName: String,
        pickup: String,
        dropoff: String,
        numTickets: Int,
        isPaid: Boolean
    ) {
        try {
            Log.d(TAG, "Showing MQTT booking notification for: $passengerName, $pickup -> $dropoff, tickets: $numTickets, paid: $isPaid")
            
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
            Log.e(TAG, "Error showing MQTT booking notification: ${e.message}", e)
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

    override fun onResume() {
        super.onResume()
        
        // Notify MQTT service that app is in foreground
        mqttService?.onAppForeground()
        
        if (!isDestroyed) {
            // Re-enable NFC reader (only if not destroyed)
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
            // Disable NFC reader when paused (only if not destroyed)
            try {
                nfcReaderHelper?.disableNfcReader(this)
            } catch (e: Exception) {
                Log.w(TAG, "Error disabling NFC reader during pause: ${e.message}")
            }
        }
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
        Log.d(TAG, "HeadlessNavigActivity destroyed")
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        
        // Stop navigation
        navigator?.route = null
        navigator?.routeProgressListener = null
        navigator?.navigableLocationListener = null
        
        // Reset trip section validator
        if (::tripSectionValidator.isInitialized) {
            tripSectionValidator.reset()
        }
        
        // Stop location engine
        locationEngine?.let { engine ->
            try {
                engine.stop()
                Log.d(TAG, "Location engine stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location engine: ${e.message}")
            }
        }
        
        // Stop location simulator
        locationSimulator?.let { simulator ->
            simulator.stop()
            simulator.listener = null
            Log.d(TAG, "Location simulator stopped")
        }
        
        // Clean up NFC reader safely
        try {
            nfcReaderHelper?.disableNfcReader(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error disabling NFC reader during destroy: ${e.message}")
        }

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

        handler.removeCallbacksAndMessages(null)
        
        // Dispose HERE SDK
        disposeHERESDK()
    }
    
    // Passenger list handler methods
    private fun showPickupPassengerList() {
        tripResponse?.let { trip ->
            lifecycleScope.launch(Dispatchers.IO) {
                val locationId = if (trip.status == "SCHEDULED") {
                    trip.route.origin.id
                } else {
                    trip.waypoints.firstOrNull { it.is_next }?.location_id ?: return@launch
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
            if (trip.status == "SCHEDULED") return
            
            lifecycleScope.launch(Dispatchers.IO) {
                val locationId = trip.waypoints.firstOrNull { it.is_next }?.location_id ?: return@launch
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
        // TODO: Show full booking/ticket details dialog
        Log.d(TAG, "Show passenger details for booking: $bookingId")
    }

    private fun updateHERESDKOfflineMode(isConnected: Boolean) {
        try {
            // Check if HERE SDK is initialized before accessing it
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                sdkNativeEngine.setOfflineMode(!isConnected)
                Log.d(TAG, "HERE SDK offline mode set to: ${!isConnected}")
            } else {
                Log.d(TAG, "HERE SDK not yet initialized, will set offline mode when ready")
                // Store the desired offline mode state for when SDK is ready
                pendingOfflineMode = !isConnected
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.d(TAG, "HERE SDK native library not loaded yet, will set offline mode when ready")
            // Store the desired offline mode state for when SDK is ready
            pendingOfflineMode = !isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update HERE SDK offline mode: ${e.message}", e)
        }
    }

    private fun disposeHERESDK() {
        SDKNativeEngine.getSharedInstance()?.dispose()
        SDKNativeEngine.setSharedInstance(null)
        Log.d(TAG, "HERE SDK disposed")
    }
}

@Composable
fun HeadlessNavigationScreen(
    messageText: String,
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
    bookingSuccessData: HeadlessNavigActivity.BookingSuccessData,
    bookingFailureMessage: String,
    validationSuccessTicket: String,
    validationFailureMessage: String,
    mqttNotificationData: HeadlessNavigActivity.MqttNotificationData,
    showPassengerListDialog: Boolean,
    passengerListType: HeadlessNavigActivity.PassengerListType,
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
            // Passenger count display at top
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
        
        // Success/Failure prompts
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
            listType = passengerListType,
            isVisible = showPassengerListDialog,
            onPassengerClick = onPassengerClick,
            onDismiss = onPassengerListDismiss
        )
    }
}
