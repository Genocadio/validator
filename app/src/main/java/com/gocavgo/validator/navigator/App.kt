/*
 * Copyright (C) 2019-2025 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.gocavgo.validator.navigator

import android.content.Context
import android.util.Log
import com.gocavgo.validator.R
import com.gocavgo.validator.dataclass.TripResponse
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.mapview.LineCap
import com.here.sdk.mapview.MapImageFactory
import com.here.sdk.mapview.MapMarker
import com.here.sdk.mapview.MapMeasure
import com.here.sdk.mapview.MapMeasureDependentRenderSize
import com.here.sdk.mapview.MapPolyline
import com.here.sdk.mapview.MapPolyline.SolidRepresentation
import com.here.sdk.mapview.MapView
import com.here.sdk.mapview.RenderSize
import com.here.sdk.routing.Route
import com.here.sdk.routing.RoutingError
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


// An app that allows to calculate a route and start navigation, using either platform positioning or
// simulated locations.
class App(
    private val context: Context,
    private val mapView: MapView?,
    private val messageView: MessageViewUpdater,
    private var tripResponse: TripResponse? = null
) {
    private val mapMarkerList: MutableList<MapMarker> = ArrayList()
    private val mapPolylines: MutableList<MapPolyline?> = ArrayList()
    private var startWaypoint: Waypoint? = null
    private val routeCalculator: RouteCalculator
    private val navigationExample: NavigationExample
    private var isCameraTrackingEnabled = true
    private val timeUtils: TimeUtils
    
    // Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    private var isNetworkConnected = true

    // Waypoint markers for trip navigation
    private val waypointMarkers: MutableList<MapMarker> = ArrayList()
    
    // Trip section validator for route validation and waypoint tracking (now includes MQTT functionality)
    private val tripSectionValidator: TripSectionValidator = TripSectionValidator(context)
    
    // Database manager for trip status updates
    private val databaseManager: DatabaseManager = DatabaseManager.getInstance(context)
    
    // Coroutine scope for database operations
    private val dbScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        mapView?.let {
            val mapMeasureZoom =
                MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, DEFAULT_DISTANCE_IN_METERS.toDouble())
            it.camera.lookAt(DEFAULT_MAP_CENTER, mapMeasureZoom)
        }

        routeCalculator = RouteCalculator()
        initializeNetworkMonitoring()

        navigationExample = NavigationExample(context, mapView, messageView, tripSectionValidator)
        navigationExample.startLocationProvider()

        timeUtils = TimeUtils()

        if (tripResponse != null) {
            messageView.updateText("Trip data loaded. Starting navigation...")
        } else {
            messageView.updateText("Loading trip data...")
        }
    }

    /**
     * Initialize network monitoring for routing engine selection
     */
    private fun initializeNetworkMonitoring() {
        try {
            // Get initial network state
            isNetworkConnected = NetworkUtils.isConnectedToInternet(context)
            Log.d("App", "Initial network state: $isNetworkConnected")
            
            // Set initial network state in route calculator
            routeCalculator.setNetworkState(isNetworkConnected)
            
            // Initialize network monitor for future changes
            networkMonitor = NetworkMonitor(context) { connected, type, metered ->
                handleNetworkChange(connected, type, metered)
            }
            networkMonitor?.startMonitoring()
            Log.d("App", "Network monitoring initialized successfully")
        } catch (e: Exception) {
            Log.e("App", "Failed to initialize network monitoring: ${e.message}", e)
        }
    }

    /**
     * Handle network state changes
     */
    private fun handleNetworkChange(connected: Boolean, type: String, metered: Boolean) {
        val previousState = isNetworkConnected
        isNetworkConnected = connected
        
        Log.d("App", "=== NETWORK STATE CHANGED ===")
        Log.d("App", "Previous: $previousState, New: $connected")
        Log.d("App", "Type: $type, Metered: $metered")
        
        if (previousState != connected) {
            // Update route calculator with new network state
            routeCalculator.setNetworkState(connected)
            
            // Update navigation handler if available
            navigationExample.getNavigationHandler()?.setNetworkState(connected)
            
            Log.d("App", "Network state updated in all components")
        }
        
        Log.d("App", "==============================")
    }

    // Update trip data when it becomes available
    fun updateTripData(newTripResponse: TripResponse?, isSimulated: Boolean = true) {
        Log.d("App", "=== updateTripData called ===")
        Log.d("App", "newTripResponse: $newTripResponse")
        Log.d("App", "isSimulated: $isSimulated")

        tripResponse = newTripResponse
        if (tripResponse != null) {
            Log.d("App", "Trip data updated: ${tripResponse?.id}")
            
            // Initialize MQTT service in TripSectionValidator
            initializeMqttServiceInValidator()
            
            if (!isSimulated) {
                // For device location mode, ensure GPS is active
                if (!navigationExample.isLocationProviderActive()) {
                    Log.d("App", "Starting location provider for device location mode...")
                    navigationExample.startLocationProvider()
                }
                
                if (navigationExample.hasValidLocation()) {
                    messageView.updateText("Trip data loaded. GPS ready. Calculating route...")
                    calculateRouteFromTrip(isSimulated)
                } else {
                    messageView.updateText("Trip data loaded. Waiting for GPS location...")
                    // Wait for GPS and then calculate route
                    waitForLocationAndCalculateRoute(isSimulated)
                }
            } else {
                messageView.updateText("Trip data loaded. Calculating route...")
                calculateRouteFromTrip(isSimulated)
            }
        } else {
            Log.e("App", "Trip response is null in updateTripData")
            messageView.updateText("Error: No trip data available")
        }
    }
    
    private fun waitForLocationAndCalculateRoute(isSimulated: Boolean) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val maxAttempts = 10 // 10 seconds total
        
        val checkLocation = object : Runnable {
            override fun run() {
                attempts++
                if (navigationExample.hasValidLocation()) {
                    Log.d("App", "GPS location acquired after $attempts attempts")
                    messageView.updateText("GPS location acquired. Calculating route...")
                    calculateRouteFromTrip(isSimulated)
                } else if (attempts < maxAttempts) {
                    messageView.updateText("Waiting for GPS location... (${attempts}/${maxAttempts})")
                    handler.postDelayed(this, 1000) // Check every second
                } else {
                    Log.w("App", "GPS location not acquired after $maxAttempts attempts, proceeding anyway")
                    messageView.updateText("GPS timeout. Calculating route with trip waypoints...")
                    calculateRouteFromTrip(isSimulated)
                }
            }
        }
        handler.post(checkLocation)
    }


    fun toggleTrackingButtonOnClicked() {
        // By default, this is enabled.
        navigationExample.startCameraTracking()
        isCameraTrackingEnabled = true
    }

    fun toggleTrackingButtonOffClicked() {
        navigationExample.stopCameraTracking()
        isCameraTrackingEnabled = false
    }


    private fun calculateRouteFromTrip(isSimulated: Boolean) {
        Log.d("App", "Starting calculateRouteFromTrip with isSimulated: $isSimulated")
        clearMap()

        if (!determineRouteWaypointsFromTrip(isSimulated)) {
            Log.e("App", "Failed to determine route waypoints from trip")
            return
        }

        // Create waypoints list from trip data
        val waypoints = createWaypointsFromTrip(isSimulated)

        if (waypoints.isEmpty()) {
            Log.e("App", "No valid waypoints found in trip data")
            showDialog("Error", "No valid waypoints found in trip data")
            return
        }

        Log.d("App", "Calculating route with ${waypoints.size} waypoints")
        waypoints.forEachIndexed { index, waypoint ->
            Log.d("App", "Waypoint $index: ${waypoint.coordinates.latitude}, ${waypoint.coordinates.longitude}")
        }

        messageView.updateText("Calculating route with ${waypoints.size} waypoints...")

        // Calculate route with multiple waypoints
        routeCalculator.calculateRouteWithWaypoints(
            waypoints
        ) { routingError: RoutingError?, routes: List<Route>? ->
            if (routingError == null) {
                val route: Route = routes!![0]
                Log.d(
                    "App",
                    "Route calculated successfully. Length: ${route.lengthInMeters}m, Duration: ${route.duration.seconds}s"
                )
                showRouteOnMap(route)

                // Add waypoint markers after route is calculated
                Log.d("App", "About to add waypoint markers...")
                addWaypointMarkersToMap(isSimulated)

                showRouteDetails(route, isSimulated)
            } else {
                Log.e("App", "Route calculation failed: $routingError")
                showDialog("Error while calculating a route:", routingError.toString())
            }
        }
    }

    private fun determineRouteWaypointsFromTrip(isSimulated: Boolean): Boolean {
        Log.d("App", "=== determineRouteWaypointsFromTrip called ===")
        Log.d("App", "isSimulated: $isSimulated")

        if (!isSimulated) {
            val location: Location? = navigationExample.getLastKnownLocation()
            Log.d("App", "Device location: $location")
            
            if (location == null) {
                Log.w("App", "No GPS location available yet, but continuing with trip waypoints")
                // Don't fail immediately - we can still calculate route with trip waypoints
                // The location will be used when available during navigation
            } else {
                startWaypoint = Waypoint(location.coordinates)
                // If a driver is moving, the bearing value can help to improve the route calculation.
                startWaypoint!!.headingInDegrees = location.bearingInDegrees
                mapView?.camera?.lookAt(location.coordinates)
                Log.d("App", "Set start waypoint from device location")
            }
        } else {
            Log.d("App", "Using simulated location mode")
        }

        // For trip-based navigation, we don't need to set startWaypoint and destinationWaypoint
        // as they will be determined from the trip data in createWaypointsFromTrip()
        Log.d("App", "determineRouteWaypointsFromTrip returning true")
        return true
    }

    private fun createWaypointsFromTrip(isSimulated: Boolean): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()

        tripResponse?.let { trip ->
            Log.d("App", "Creating waypoints from trip: ${trip.id}")
            Log.d("App", "Simulation mode: $isSimulated")
            Log.d("App", "Origin: ${trip.route.origin.google_place_name} (${trip.route.origin.latitude}, ${trip.route.origin.longitude})")
            Log.d("App", "Destination: ${trip.route.destination.google_place_name} (${trip.route.destination.latitude}, ${trip.route.destination.longitude})")
            Log.d("App", "Intermediate waypoints: ${trip.waypoints.size}")

            // Add device location or origin based on simulation mode
            if (!isSimulated) {
                // Device location mode: use device location, skip trip origin
                val deviceLocation = navigationExample.getLastKnownLocation()
                if (deviceLocation != null) {
                    val deviceWaypoint = Waypoint(deviceLocation.coordinates).apply {
                        type = WaypointType.STOPOVER
                        headingInDegrees = deviceLocation.bearingInDegrees
                    }
                    waypoints.add(deviceWaypoint)
                    Log.d("App", "Added device location waypoint (replacing trip origin): ${deviceLocation.coordinates.latitude}, ${deviceLocation.coordinates.longitude}")
                } else {
                    Log.w("App", "Device location not yet available, starting with trip origin as fallback")
                    // Fallback to trip origin only if device location unavailable
                    val origin = Waypoint(
                        GeoCoordinates(
                            trip.route.origin.latitude,
                            trip.route.origin.longitude
                        )
                    ).apply {
                        type = WaypointType.STOPOVER
                    }
                    waypoints.add(origin)
                    Log.d("App", "Added origin waypoint (fallback)")
                }
            } else {
                // Simulated mode: use saved location for IN_PROGRESS or trip origin
                val useSavedLocation = trip.status.equals("IN_PROGRESS", ignoreCase = true) && 
                                       trip.vehicle.current_latitude != null && 
                                       trip.vehicle.current_longitude != null
                
                val origin = if (useSavedLocation) {
                    Log.d("App", "Using saved vehicle location for IN_PROGRESS trip (simulated mode)")
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
                
                origin.apply {
                    type = WaypointType.STOPOVER
                }
                waypoints.add(origin)
                Log.d("App", if (useSavedLocation) "Added saved location waypoint" else "Added origin waypoint")
            }

            // Filter out passed waypoints - only include unpassed waypoints
            val sortedWaypoints = trip.waypoints.sortedBy { it.order }
            val unpassedWaypoints = sortedWaypoints.filter { !it.is_passed }
            val skippedCount = sortedWaypoints.size - unpassedWaypoints.size
            
            Log.d("App", "=== WAYPOINT FILTERING ===")
            Log.d("App", "Total waypoints: ${sortedWaypoints.size}")
            Log.d("App", "Passed waypoints: $skippedCount")
            Log.d("App", "Unpassed waypoints: ${unpassedWaypoints.size}")
            
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
                Log.d("App", "Added waypoint: ${tripWaypoint.location.google_place_name} (order: ${tripWaypoint.order})")
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
            Log.d("App", "Added destination waypoint")
            
            Log.d("App", "Total waypoints created: ${waypoints.size} (skipped $skippedCount passed waypoints)")
            Log.d("App", "=========================")
        } ?: run {
            Log.e("App", "Trip response is null, cannot create waypoints")
        }

        Log.d("App", "Waypoint structure:")
        waypoints.forEachIndexed { index, waypoint ->
            Log.d("App", "  Waypoint $index: ${waypoint.coordinates.latitude}, ${waypoint.coordinates.longitude}")
        }
        return waypoints
    }

    private fun addWaypointMarkersToMap(isSimulated: Boolean) {
        mapView?.let { mapView ->
            Log.d("App", "=== STARTING addWaypointMarkersToMap ===")
            Log.d("App", "Simulation mode: $isSimulated")

            // Clear existing waypoint markers
            clearWaypointMarkers()

            tripResponse?.let { trip ->
                Log.d("App", "Trip data available, adding waypoint markers")
                Log.d("App", "Trip ID: ${trip.id}")
                Log.d("App", "Origin: ${trip.route.origin.google_place_name}")
                Log.d("App", "Destination: ${trip.route.destination.google_place_name}")
                Log.d("App", "Intermediate waypoints: ${trip.waypoints.size}")

                // Note: Device location is not marked on map when isSimulated = false
                // The camera will automatically look at the device location
                if (!isSimulated) {
                    Log.d("App", "Device location mode: Camera will look at device location, no marker needed")
                }

                // Add origin marker only in simulated mode
                if (isSimulated) {
                    Log.d("App", "Adding origin marker...")
                    val originMarker = createWaypointMarker(
                        GeoCoordinates(trip.route.origin.latitude, trip.route.origin.longitude),
                        R.drawable.green_dot,
                        "Origin: ${trip.route.origin.google_place_name}"
                    )
                    if (originMarker != null) {
                        waypointMarkers.add(originMarker)
                        mapView.mapScene.addMapMarker(originMarker)
                        Log.d("App", "Origin marker added to map")
                    } else {
                        Log.e("App", "Failed to create origin marker")
                    }
                } else {
                    Log.d("App", "Device location mode: Skipping origin marker (device location replaces origin)")
                }

                // Add intermediate waypoint markers
                val sortedWaypoints = trip.waypoints.sortedBy { it.order }
                Log.d("App", "Adding ${sortedWaypoints.size} intermediate waypoint markers...")
                sortedWaypoints.forEachIndexed { index, tripWaypoint ->
                    Log.d("App", "Adding waypoint $index: ${tripWaypoint.location.google_place_name}")
                    val marker = createWaypointMarker(
                        GeoCoordinates(tripWaypoint.location.latitude, tripWaypoint.location.longitude),
                        R.drawable.green_dot,
                        "Waypoint: ${tripWaypoint.location.google_place_name}"
                    )
                    if (marker != null) {
                        waypointMarkers.add(marker)
                        mapView.mapScene.addMapMarker(marker)
                        Log.d("App", "Waypoint $index marker added to map")
                    } else {
                        Log.e("App", "Failed to create waypoint $index marker")
                    }
                }

                // Add destination marker
                Log.d("App", "Adding destination marker...")
                val destinationMarker = createWaypointMarker(
                    GeoCoordinates(trip.route.destination.latitude, trip.route.destination.longitude),
                    R.drawable.green_dot,
                    "Destination: ${trip.route.destination.google_place_name}"
                )
                if (destinationMarker != null) {
                    waypointMarkers.add(destinationMarker)
                    mapView.mapScene.addMapMarker(destinationMarker)
                    Log.d("App", "Destination marker added to map")
                } else {
                    Log.e("App", "Failed to create destination marker")
                }

                Log.d("App", "=== COMPLETED addWaypointMarkersToMap ===")
                Log.d("App", "Total waypoint markers added: ${waypointMarkers.size}")
                Log.d("App", "Marker structure:")
                waypointMarkers.forEachIndexed { index, marker ->
                    Log.d("App", "  Marker $index: ${marker.coordinates.latitude}, ${marker.coordinates.longitude}")
                }
                val markerText = if (!isSimulated) {
                    "Added ${waypointMarkers.size} trip waypoint markers to map (device location not marked)"
                } else {
                    "Added ${waypointMarkers.size} waypoint markers to map"
                }
                messageView.updateText(markerText)
            } ?: run {
                Log.e("App", "Trip response is null, cannot add waypoint markers")
                messageView.updateText("No trip data available for waypoint markers")
            }
        }
    }

    private fun createWaypointMarker(geoCoordinates: GeoCoordinates, resourceId: Int, title: String): MapMarker? {
        Log.d("App", "Creating waypoint marker at: ${geoCoordinates.latitude}, ${geoCoordinates.longitude}")
        Log.d("App", "Using resource ID: $resourceId $title")

        return try {
            val mapImage = MapImageFactory.fromResource(context.resources, resourceId)
            if (mapImage == null) {
                Log.e("App", "Failed to create map image from resource ID: $resourceId")
                return null
            }
            val mapMarker = MapMarker(geoCoordinates, mapImage)
            Log.d("App", "Waypoint marker created successfully")
            mapMarker
        } catch (e: Exception) {
            Log.e("App", "Error creating waypoint marker: ${e.message}")
            null
        }
    }

    private fun clearWaypointMarkers() {
        mapView?.let { mapView ->
            for (marker in waypointMarkers) {
                mapView.mapScene.removeMapMarker(marker)
            }
        }
        waypointMarkers.clear()
        Log.d("App", "Cleared waypoint markers")
    }



    private fun showRouteDetails(route: Route, isSimulated: Boolean) {
        val estimatedTravelTimeInSeconds = route.duration.seconds
        val lengthInMeters = route.lengthInMeters

        // Verify route sections against trip data if trip is available
        if (tripResponse != null) {
            // Get device location if in device location mode
            val deviceLocation = if (!isSimulated) {
                navigationExample.getLastKnownLocation()?.coordinates
            } else {
                null
            }
            
            // Calculate skipped waypoint count
            val skippedCount = tripResponse?.waypoints?.count { it.is_passed } ?: 0
            
            Log.d("App", "=== ROUTE VERIFICATION WITH SKIPPED WAYPOINTS ===")
            Log.d("App", "Skipped waypoints: $skippedCount")
            Log.d("App", "Total waypoints: ${tripResponse?.waypoints?.size ?: 0}")
            
            val verificationPassed = tripSectionValidator.verifyRouteSections(
                tripResponse!!, 
                route, 
                isSimulated, 
                deviceLocation,
                skippedWaypointCount = skippedCount
            )
            if (verificationPassed) {
                Log.d("App", "Route Verified well")
            } else {
                Log.e("App", "❌ Route verification failed - continuing with navigation anyway")
            }
            Log.d("App", "================================================")
        } else {
            Log.d("App", "No trip data available for route verification")
        }

        // Route sections are now mapped to trip waypoints within TripSectionValidator
        if (tripResponse != null && tripSectionValidator.isVerified()) {
            Log.d("App", "✅ Route validation completed - waypoint mapping handled by TripSectionValidator")
        }

//        for (i in route.sections.indices) {
//            val dateFormat: DateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
//            val section: Section = route.sections[i]
//            val  length = section.lengthInMeters
//            val duration = section.duration.seconds
//
//            val formattedDepartureLocationTime = section.departureLocationTime
//                ?.localTime
//                ?.let(dateFormat::format)
//                ?: "Unknown"
//
//            val formattedArrivalLocationTime = section.arrivalLocationTime
//                ?.localTime
//                ?.let(dateFormat::format)
//                ?: "Unknown"
//
//        }


        // Update trip status to IN_PROGRESS when navigation starts
        tripResponse?.let { trip ->
            dbScope.launch {
                try {
                    val currentStatus = trip.status
                    if (!currentStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                        databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                        Log.d("App", "Trip ${trip.id} status updated from $currentStatus to IN_PROGRESS")
                    } else {
                        Log.d("App", "Trip ${trip.id} already IN_PROGRESS, no update needed")
                    }
                } catch (e: Exception) {
                    Log.e("App", "Failed to update trip status to IN_PROGRESS: ${e.message}", e)
                }
            }
        }
        
        // Automatically start navigation without confirmation
//        Log.d("App", "Route Details: $routeDetails")
        messageView.updateText("Route: ${timeUtils.formatTime(estimatedTravelTimeInSeconds)}, ${timeUtils.formatLength(lengthInMeters)}")

//        Log.d("App", "Starting navigation automatically...")
        messageView.updateText("Starting navigation...")
        navigationExample.startNavigation(route, isSimulated, isCameraTrackingEnabled)
        
        // Notify that route is calculated
        onRouteCalculatedCallback?.invoke()
    }

    private fun showRouteOnMap(route: Route) {
        mapView?.let { mapView ->
            // Show route as polyline.
            val routeGeoPolyline = route.geometry
            val widthInPixels = 20f
            val polylineColor = Color.valueOf(0f, 0.56f, 0.54f, 0.63f)
            var routeMapPolyline: MapPolyline? = null
            try {
                routeMapPolyline = MapPolyline(
                    routeGeoPolyline, SolidRepresentation(
                        MapMeasureDependentRenderSize(RenderSize.Unit.PIXELS, widthInPixels.toDouble()),
                        polylineColor,
                        LineCap.ROUND
                    )
                )
            } catch (e: MapPolyline.Representation.InstantiationException) {
                Log.e("MapPolyline Representation Exception:", e.error.name)
            } catch (e: MapMeasureDependentRenderSize.InstantiationException) {
                Log.e("MapMeasureDependentRenderSize Exception:", e.error.name)
            }

            mapView.mapScene.addMapPolyline(routeMapPolyline!!)
            mapPolylines.add(routeMapPolyline)
        }
    }

    private fun clearMap() {
        clearWaypointMapMarker()
        clearWaypointMarkers() // Clear trip waypoint markers
        clearRoute()
        navigationExample.stopNavigation(isCameraTrackingEnabled)
    }

    private fun clearWaypointMapMarker() {
        mapView?.let { mapView ->
            for (mapMarker in mapMarkerList) {
                mapView.mapScene.removeMapMarker(mapMarker)
            }
        }
        mapMarkerList.clear()
    }

    private fun clearRoute() {
        mapView?.let { mapView ->
            for (mapPolyline in mapPolylines) {
                mapView.mapScene.removeMapPolyline(mapPolyline!!)
            }
        }
        mapPolylines.clear()
    }



    private fun showDialog(title: String, text: String) {
        DialogManager.show(title, text, buttonText = "Ok") {}
    }

    fun clearMapAndExit() {
        Log.d("App", "Clearing map and exiting navigation")
        clearMap()
        messageView.updateText("Navigation stopped. Returning to main screen...")

        // Exit back to MainActivity
        // Note: This will be handled by the NavigActivity
    }

    fun detach() {
        Log.d("App", "Detaching app - setting shutdown flag")
        
        // Set shutdown flag to prevent starting services during cleanup
        navigationExample.setShuttingDown(true)
        
        // Disables TBT guidance (if running) and enters tracking mode.
        navigationExample.stopNavigation(isCameraTrackingEnabled)
        // Disables positioning.
        navigationExample.stopLocating()
        // Disables rendering.
        navigationExample.stopRendering()
        
        // Cleanup network monitoring
        cleanupNetworkMonitoring()
        
        // Cleanup database coroutine scope
        dbScope.cancel()
        
        Log.d("App", "App detached successfully")
    }

    /**
     * Cleanup network monitoring resources
     */
    private fun cleanupNetworkMonitoring() {
        try {
            networkMonitor?.stopMonitoring()
            networkMonitor = null
            Log.d("App", "Network monitoring cleaned up successfully")
        } catch (e: Exception) {
            Log.e("App", "Error cleaning up network monitoring: ${e.message}", e)
        }
    }
    
    /**
     * Gets the trip section validator for UI access
     */
    fun getTripSectionValidator(): TripSectionValidator = tripSectionValidator
    
    /**
     * Gets the navigation example for cleanup operations
     */
    fun getNavigationExample(): NavigationExample = navigationExample
    
    /**
     * Callback to notify when route is calculated
     */
    private var onRouteCalculatedCallback: (() -> Unit)? = null
    
    /**
     * Sets callback for route calculation notification
     */
    fun setOnRouteCalculatedCallback(callback: () -> Unit) {
        onRouteCalculatedCallback = callback
    }
    
    /**
     * Initialize MQTT service in TripSectionValidator
     */
    private fun initializeMqttServiceInValidator() {
        try {
            tripResponse?.let { trip ->
                Log.d("App", "Initializing MQTT service in TripSectionValidator for trip: ${trip.id}")
                
                // Get MQTT service instance
                val mqttService = com.gocavgo.validator.service.MqttService.getInstance()
                
                // Initialize MQTT service in TripSectionValidator if available
                if (mqttService != null) {
                    tripSectionValidator.initializeMqttService(mqttService)
                    Log.d("App", "MQTT service initialized successfully in TripSectionValidator")
                } else {
                    Log.e("App", "MQTT service is null - cannot initialize in TripSectionValidator")
                }
            }
        } catch (e: Exception) {
            Log.e("App", "Failed to initialize MQTT service in TripSectionValidator: ${e.message}", e)
        }
    }

    companion object {
        val DEFAULT_MAP_CENTER: GeoCoordinates = GeoCoordinates(-1.95, 30.06)
        const val DEFAULT_DISTANCE_IN_METERS: Int = 1000 * 2
    }
}