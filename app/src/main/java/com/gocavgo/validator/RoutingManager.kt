package com.gocavgo.validator

import com.gocavgo.validator.util.Logging
import android.os.Handler
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.navigation.VisualNavigator
import com.here.sdk.navigation.RouteDeviation
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.Route
import com.here.sdk.routing.RouteHandle
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.RoutingError
import com.here.sdk.routing.RoutingInterface
import com.here.sdk.routing.Waypoint
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngineOptions
import com.here.sdk.trafficawarenavigation.DynamicRoutingListener
import com.here.sdk.transport.TransportMode
import com.here.time.Duration

class RoutingManager(
    private val handler: Handler,
    private val isNetworkAvailable: () -> Boolean
) {

    companion object {
        private const val TAG = "RoutingManager"
        
        init {
            // Disable logging for this manager
            Logging.setTagEnabled(TAG, false)
        }
    }

    private var onlineRoutingEngine: RoutingEngine? = null
    private var offlineRoutingEngine: OfflineRoutingEngine? = null
    private var dynamicRoutingEngine: DynamicRoutingEngine? = null
    private var offlineSwitchRunnable: Runnable? = null
    private var isReturningToRoute: Boolean = false
    private var deviationCounter: Int = 0

    private val DEVIATION_THRESHOLD_METERS = 50
    private val MIN_DEVIATION_EVENTS = 3

    var useDynamicRouting: Boolean = false
        private set

    init {
        initializeRoutingEngines()
    }

    private fun initializeRoutingEngines() {
        try {
            onlineRoutingEngine = RoutingEngine()
            offlineRoutingEngine = OfflineRoutingEngine()
            createDynamicRoutingEngine()
            Logging.d(TAG, "All routing engines initialized successfully")
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
            Logging.d(TAG, "DynamicRoutingEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of DynamicRoutingEngine failed: " + e.error.name)
        }
    }

    fun toggleRoutingMode(useDynamic: Boolean, currentRoute: Route?, onNeedRestart: (Route) -> Unit) {
        val previous = useDynamicRouting
        useDynamicRouting = useDynamic
        Logging.d(TAG, "Routing mode changed ${if (previous) "Dynamic" else "Offline"} -> ${if (useDynamicRouting) "Dynamic" else "Offline"}")
        currentRoute?.let { onNeedRestart(it) }
    }

    fun handleNetworkStateChange(
        connected: Boolean,
        type: String,
        metered: Boolean,
        currentRoute: Route?,
        onRestartWithNewEngine: (Route) -> Unit,
        onMapUpdateRecommended: () -> Unit
    ) {
        Logging.d(TAG, "=== HANDLING NETWORK CHANGE (RoutingManager) ===")

        offlineSwitchRunnable?.let {
            handler.removeCallbacks(it)
            Logging.d(TAG, "Cancelled pending offline switch task")
        }
        offlineSwitchRunnable = null

        when {
            !connected -> {
                Logging.w(TAG, "Lost network connection - switching to offline mode")
                if (useDynamicRouting) {
                    Logging.d(TAG, "Auto-switching from dynamic to offline routing (delayed)")
                    offlineSwitchRunnable = Runnable {
                        toggleRoutingMode(false, currentRoute) { r -> onRestartWithNewEngine(r) }
                        offlineSwitchRunnable = null
                    }
                    handler.postDelayed(offlineSwitchRunnable!!, 120000)
                    Logging.d(TAG, "Scheduled offline switch in 2 minutes")
                }
                dynamicRoutingEngine?.stop()
            }

            connected && type == "CELLULAR" && metered -> {
                Logging.w(TAG, "On metered cellular connection - using offline routing to save data")
                if (useDynamicRouting) {
                    Logging.d(TAG, "Switching to offline routing to conserve data")
                    toggleRoutingMode(false, currentRoute) { r -> onRestartWithNewEngine(r) }
                }
            }

            connected && type == "WIFI" && !metered -> {
                Logging.d(TAG, "Fast WiFi connection available - dynamic routing optimal")
                if (!useDynamicRouting && currentRoute != null) {
                    Logging.d(TAG, "Switching to dynamic routing for better traffic awareness")
                    toggleRoutingMode(true, currentRoute) { r -> onRestartWithNewEngine(r) }
                }
                onMapUpdateRecommended()
            }

            connected && type.startsWith("CELLULAR") && !metered -> {
                Logging.d(TAG, "Unlimited cellular connection - dynamic routing available")
                if (!useDynamicRouting && currentRoute != null) {
                    toggleRoutingMode(true, currentRoute) { r -> onRestartWithNewEngine(r) }
                }
            }
        }

        Logging.d(TAG, "==============================")
    }

    private fun getSelectedRoutingEngine(): RoutingInterface {
        return if (useDynamicRouting && isNetworkAvailable()) {
            onlineRoutingEngine!!
        } else {
            offlineRoutingEngine!!
        }
    }

    fun calculateRoute(
        waypoints: List<Waypoint>,
        carOptions: CarOptions,
        onSuccess: (Route) -> Unit,
        onError: (RoutingError?) -> Unit
    ) {
        val engine = getSelectedRoutingEngine()
        engine.calculateRoute(waypoints, carOptions) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                onSuccess(routes[0])
            } else if (useDynamicRouting) {
                // Fallback to offline when online fails
                offlineRoutingEngine?.calculateRoute(waypoints, carOptions) { offErr, offRoutes ->
                    if (offErr == null && offRoutes != null && offRoutes.isNotEmpty()) {
                        onSuccess(offRoutes[0])
                    } else {
                        onError(offErr)
                    }
                }
            } else {
                onError(routingError)
            }
        }
    }

    fun startDynamicSearchForBetterRoutes(
        route: Route,
        onBetter: (newRoute: Route, etaDiffSeconds: Int, distanceDiffMeters: Int) -> Unit,
        onError: (RoutingError) -> Unit = {}
    ) {
        dynamicRoutingEngine?.let { engine ->
            try {
                engine.start(route, object : DynamicRoutingListener {
                    override fun onBetterRouteFound(newRoute: Route, etaDifferenceInSeconds: Int, distanceDifferenceInMeters: Int) {
                        onBetter(newRoute, etaDifferenceInSeconds, distanceDifferenceInMeters)
                    }

                    override fun onRoutingError(routingError: RoutingError) {
                        onError(routingError)
                    }
                })
            } catch (e: DynamicRoutingEngine.StartException) {
                Logging.e(TAG, "Failed to start dynamic routing: ${e.message}")
            }
        }
    }

    fun stop() {
        dynamicRoutingEngine?.stop()
    }

    fun returnToRoute(
        currentRoute: Route,
        newStartingPoint: Waypoint,
        lastTraveledSectionIndex: Int,
        traveledDistanceOnLastSectionInMeters: Int,
        onSuccess: (Route) -> Unit,
        onError: (RoutingError?) -> Unit
    ) {
        val engine = getSelectedRoutingEngine()
        engine.returnToRoute(
            currentRoute,
            newStartingPoint,
            lastTraveledSectionIndex,
            traveledDistanceOnLastSectionInMeters
        ) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                onSuccess(routes[0])
            } else {
                onError(routingError)
            }
        }
    }

    fun importRouteFromHandle(
        routeHandle: String,
        onSuccess: (Route) -> Unit,
        onError: (RoutingError?) -> Unit
    ) {
        onlineRoutingEngine?.importRoute(RouteHandle(routeHandle), com.here.sdk.routing.RefreshRouteOptions(TransportMode.CAR)) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                onSuccess(routes[0])
            } else {
                onError(routingError)
            }
        }
    }

    fun importRouteFromCoordinates(
        coordinates: List<GeoCoordinates>,
        routeOptions: CarOptions = CarOptions(),
        onSuccess: (Route) -> Unit,
        onError: (RoutingError?) -> Unit
    ) {
        val locations = coordinates.map { Location(it) }
        onlineRoutingEngine?.importRoute(locations, routeOptions) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                onSuccess(routes[0])
            } else {
                onError(routingError)
            }
        }
    }

    fun resetDeviationState() {
        isReturningToRoute = false
        deviationCounter = 0
        Logging.d(TAG, "Deviation state reset for new route")
    }

    fun handleRouteDeviation(
        routeDeviation: RouteDeviation,
        currentRoute: Route?,
        onRerouteApplied: (Route) -> Unit,
        onShowMap: (Route) -> Unit,
        onLogRouteInfo: (Route) -> Unit
    ) {
        val route = currentRoute
        if (route == null) {
            Logging.w(TAG, "No current route available for deviation handling")
            return
        }

        val currentMapMatchedLocation = routeDeviation.currentLocation.mapMatchedLocation
        val currentGeoCoordinates = (
            currentMapMatchedLocation?.coordinates
                ?: routeDeviation.currentLocation.originalLocation.coordinates
        )!!

        val lastGeoCoordinatesOnRoute: com.here.sdk.core.GeoCoordinates =
            if (routeDeviation.lastLocationOnRoute != null) {
                val lastMapMatchedLocationOnRoute = routeDeviation.lastLocationOnRoute!!.mapMatchedLocation
                (
                    lastMapMatchedLocationOnRoute?.coordinates
                        ?: routeDeviation.lastLocationOnRoute!!.originalLocation.coordinates
                )!!
            } else {
                Logging.d(TAG, "User was never following the route. Taking start of the route instead.")
                route.sections[0].departurePlace.originalCoordinates!!
            }

        val distanceInMeters = currentGeoCoordinates.distanceTo(lastGeoCoordinatesOnRoute).toInt()
        Logging.d(TAG, "Route deviation detected: ${distanceInMeters}m from route")

        deviationCounter++

        if (isReturningToRoute) {
            Logging.d(TAG, "Rerouting is already in progress, ignoring deviation event")
            return
        }

        if (distanceInMeters > DEVIATION_THRESHOLD_METERS && deviationCounter >= MIN_DEVIATION_EVENTS) {
            Logging.d(TAG, "=== ROUTE DEVIATION DETECTED ===")
            Logging.d(TAG, "Deviation distance: ${distanceInMeters}m (threshold: ${DEVIATION_THRESHOLD_METERS}m)")
            Logging.d(TAG, "Deviation events: $deviationCounter (minimum: $MIN_DEVIATION_EVENTS)")
            Logging.d(TAG, "Starting rerouting process...")

            isReturningToRoute = true

            val newStartingPoint = Waypoint(currentGeoCoordinates)
            currentMapMatchedLocation?.bearingInDegrees?.let { bearing ->
                newStartingPoint.headingInDegrees = bearing
                Logging.d(TAG, "Setting heading direction: ${bearing}°")
            }

            returnToRoute(
                route,
                newStartingPoint,
                routeDeviation.lastTraveledSectionIndex,
                routeDeviation.traveledDistanceOnLastSectionInMeters,
                onSuccess = { newRoute ->
                    Logging.d(TAG, "=== REROUTING SUCCESSFUL ===")
                    Logging.d(TAG, "New route calculated successfully")
                    onRerouteApplied(newRoute)
                    onShowMap(newRoute)
                    onLogRouteInfo(newRoute)
                    Logging.d(TAG, "==========================")
                    isReturningToRoute = false
                    deviationCounter = 0
                    Logging.d(TAG, "Rerouting process completed")
                },
                onError = { routingError ->
                    Logging.e(TAG, "Rerouting failed: ${routingError?.name}")
                    Logging.w(TAG, "Continuing with original route despite deviation")
                    isReturningToRoute = false
                    deviationCounter = 0
                    Logging.d(TAG, "Rerouting process completed")
                }
            )
        } else {
            Logging.d(TAG, "Deviation not significant enough for rerouting: ${distanceInMeters}m or not enough events: $deviationCounter")
        }
    }
}


