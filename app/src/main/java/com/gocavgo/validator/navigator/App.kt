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
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.service.MqttService
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.core.Point2D
import com.here.sdk.gestures.GestureState
import com.here.sdk.gestures.LongPressListener
import com.here.sdk.mapview.LineCap
import com.here.sdk.mapview.MapImageFactory
import com.here.sdk.mapview.MapMarker
import com.here.sdk.mapview.MapMeasure
import com.here.sdk.mapview.MapMeasureDependentRenderSize
import com.here.sdk.mapview.MapPolyline
import com.here.sdk.mapview.MapPolyline.SolidRepresentation
import com.here.sdk.mapview.MapView
import com.here.sdk.mapview.RenderSize
import com.here.sdk.routing.CalculateRouteCallback
import com.here.sdk.routing.Route
import com.here.sdk.routing.RoutingError
import com.here.sdk.routing.Section
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.WaypointType
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


// An app that allows to calculate a route and start navigation, using either platform positioning or
// simulated locations.
class App(
    private val context: Context,
    private val mapView: MapView,
    private val messageView: MessageViewUpdater,
    private var tripResponse: TripResponse? = null
) {
    private val mapMarkerList: MutableList<MapMarker> = ArrayList()
    private val mapPolylines: MutableList<MapPolyline?> = ArrayList()
    private var startWaypoint: Waypoint? = null
    private var destinationWaypoint: Waypoint? = null
    private var setLongpressDestination = false
    private val routeCalculator: RouteCalculator
    private val navigationExample: NavigationExample
    private var isCameraTrackingEnabled = true
    private val timeUtils: TimeUtils
    private val databaseManager: DatabaseManager
    private val mqttService: MqttService?
    
    // Waypoint markers for trip navigation
    private val waypointMarkers: MutableList<MapMarker> = ArrayList()
    private var currentWaypointIndex = 0
    
    // Route progress tracking
    private var routeProgressTracker: RouteProgressTracker? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    init {
        val mapMeasureZoom =
            MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, DEFAULT_DISTANCE_IN_METERS.toDouble())
        mapView.camera.lookAt(DEFAULT_MAP_CENTER, mapMeasureZoom)

        routeCalculator = RouteCalculator()

        navigationExample = NavigationExample(context, mapView, messageView)
        navigationExample.startLocationProvider()

        timeUtils = TimeUtils()
        databaseManager = DatabaseManager.getInstance(context)
        mqttService = MqttService.getInstance()

        setLongPressGestureHandler()

        if (tripResponse != null) {
            messageView.updateText("Trip data loaded. Starting navigation...")
        } else {
            messageView.updateText("Loading trip data...")
        }
    }

    // Calculate a route and start navigation using a location simulator.
    // Start is map center and destination location is set random within viewport,
    // unless a destination is set via long press.
    fun addRouteSimulatedLocation() {
        calculateRoute(true)
    }

    // Calculate a route and start navigation using locations from device.
    // Start is current location and destination is set random within viewport,
    // unless a destination is set via long press.
    fun addRouteDeviceLocation() {
        calculateRoute(false)
    }


    // Update trip data when it becomes available
    fun updateTripData(newTripResponse: TripResponse?, isSimulated: Boolean = true) {
        Logging.d("App", "=== updateTripData called ===")
        Logging.d("App", "newTripResponse: $newTripResponse")
        Logging.d("App", "isSimulated: $isSimulated")
        
        tripResponse = newTripResponse
        if (tripResponse != null) {
            Logging.d("App", "Trip data updated: ${tripResponse?.id}")
            messageView.updateText("Trip data loaded. Calculating route...")
            
            // Initialize route progress tracker for this trip
            initializeRouteProgressTracker()
            
            // Automatically start route calculation when trip data is available
            Logging.d("App", "About to call calculateRouteFromTrip...")
            calculateRouteFromTrip(isSimulated)
        } else {
            Logging.e("App", "Trip response is null in updateTripData")
            messageView.updateText("Error: No trip data available")
        }
    }

    /**
     * Initialize route progress tracker for the current trip
     */
    private fun initializeRouteProgressTracker() {
        try {
            tripResponse?.let { trip ->
                Logging.d("App", "Initializing RouteProgressTracker for trip ${trip.id}")
                routeProgressTracker = RouteProgressTracker(
                    tripId = trip.id,
                    context = context,
                    coroutineScope = coroutineScope,
                    mqttService = mqttService
                )
                
                // Set the route progress tracker in the navigation example
                navigationExample.setRouteProgressTracker(routeProgressTracker)
                
                Logging.d("App", "RouteProgressTracker initialized and set in NavigationExample")
            }
        } catch (e: Exception) {
            Logging.e("App", "Failed to initialize RouteProgressTracker: ${e.message}", e)
        }
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

    private fun calculateRoute(isSimulated: Boolean) {
        clearMap()

        if (!determineRouteWaypoints(isSimulated)) {
            return
        }

        // Calculates a car route.
        startWaypoint?.let { startWaypoint ->
            destinationWaypoint?.let { destinationWaypoint ->
                routeCalculator.calculateRoute(
                    startWaypoint,
                    destinationWaypoint,
                    CalculateRouteCallback { routingError: RoutingError?, routes: List<Route>? ->
                        if (routingError == null) {
                            val route: Route = routes!![0]
                            showRouteOnMap(route)
                            showRouteDetails(route, isSimulated)
                        } else {
                            showDialog("Error while calculating a route:", routingError.toString())
                        }
                    }
                )
            }
        }
    }

    private fun calculateRouteFromTrip(isSimulated: Boolean) {
        Logging.d("App", "Starting calculateRouteFromTrip with isSimulated: $isSimulated")
        clearMap()

        if (!determineRouteWaypointsFromTrip(isSimulated)) {
            Logging.e("App", "Failed to determine route waypoints from trip")
            return
        }

        // Create waypoints list from trip data
        val waypoints = createWaypointsFromTrip()
        
        if (waypoints.isEmpty()) {
            Logging.e("App", "No valid waypoints found in trip data")
            showDialog("Error", "No valid waypoints found in trip data")
            return
        }

        Logging.d("App", "Calculating route with ${waypoints.size} waypoints")
        waypoints.forEachIndexed { index, waypoint ->
            Logging.d("App", "Waypoint $index: ${waypoint.coordinates.latitude}, ${waypoint.coordinates.longitude}")
        }

        messageView.updateText("Calculating route with ${waypoints.size} waypoints...")

        // Calculate route with multiple waypoints
        routeCalculator.calculateRouteWithWaypoints(
            waypoints,
            CalculateRouteCallback { routingError: RoutingError?, routes: List<Route>? ->
                if (routingError == null) {
                    val route: Route = routes!![0]
                    Logging.d("App", "Route calculated successfully. Length: ${route.lengthInMeters}m, Duration: ${route.duration.seconds}s")
                    showRouteOnMap(route)
                    
                    // Set route in progress tracker
                    routeProgressTracker?.setCurrentRoute(route)
                    
                    // Add waypoint markers after route is calculated
                    Logging.d("App", "About to add waypoint markers...")
                    addWaypointMarkersToMap()
                    
                    showRouteDetails(route, isSimulated)
                } else {
                    Logging.e("App", "Route calculation failed: $routingError")
                    showDialog("Error while calculating a route:", routingError.toString())
                }
            }
        )
    }

    private fun determineRouteWaypoints(isSimulated: Boolean): Boolean {
        if (!isSimulated && navigationExample.getLastKnownLocation() == null) {
            showDialog("Error", "No GPS location found.")
            return false
        }

        // When using real GPS locations, we always start from the current location of user.
        if (!isSimulated) {
            val location: Location? = navigationExample.getLastKnownLocation()
            location?.let {
                startWaypoint = Waypoint(it.coordinates)
                // If a driver is moving, the bearing value can help to improve the route calculation.
                startWaypoint!!.headingInDegrees = it.bearingInDegrees
                mapView.camera.lookAt(it.coordinates)
            }
        }

        if (startWaypoint == null) {
            startWaypoint = Waypoint(createRandomGeoCoordinatesAroundMapCenter())
        }

        if (destinationWaypoint == null) {
            destinationWaypoint = Waypoint(createRandomGeoCoordinatesAroundMapCenter())
        }

        return true
    }

    private fun determineRouteWaypointsFromTrip(isSimulated: Boolean): Boolean {
        Logging.d("App", "=== determineRouteWaypointsFromTrip called ===")
        Logging.d("App", "isSimulated: $isSimulated")
        
        if (!isSimulated && navigationExample.getLastKnownLocation() == null) {
            Logging.e("App", "No GPS location found for device location mode")
            showDialog("Error", "No GPS location found.")
            return false
        }

        // When using real GPS locations, we always start from the current location of user.
        if (!isSimulated) {
            val location: Location? = navigationExample.getLastKnownLocation()
            Logging.d("App", "Device location: $location")
            location?.let {
                startWaypoint = Waypoint(it.coordinates)
                // If a driver is moving, the bearing value can help to improve the route calculation.
                startWaypoint!!.headingInDegrees = it.bearingInDegrees
                mapView.camera.lookAt(it.coordinates)
                Logging.d("App", "Set start waypoint from device location")
            }
        } else {
            Logging.d("App", "Using simulated location mode")
        }

        // For trip-based navigation, we don't need to set startWaypoint and destinationWaypoint
        // as they will be determined from the trip data in createWaypointsFromTrip()
        Logging.d("App", "determineRouteWaypointsFromTrip returning true")
        return true
    }

    private fun createWaypointsFromTrip(): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        
        tripResponse?.let { trip ->
            Logging.d("App", "Creating waypoints from trip: ${trip.id}")
            Logging.d("App", "Origin: ${trip.route.origin.google_place_name} (${trip.route.origin.latitude}, ${trip.route.origin.longitude})")
            Logging.d("App", "Destination: ${trip.route.destination.google_place_name} (${trip.route.destination.latitude}, ${trip.route.destination.longitude})")
            Logging.d("App", "Intermediate waypoints: ${trip.waypoints.size}")
            
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
            Logging.d("App", "Added origin waypoint")

            // Add intermediate waypoints sorted by order, but only unpassed ones
            val sortedWaypoints = trip.waypoints
                .filter { !it.is_passed } // Only include unpassed waypoints
                .sortedBy { it.order }
            
            Logging.d("App", "Found ${trip.waypoints.size} total waypoints, ${sortedWaypoints.size} unpassed waypoints")
            
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
                Logging.d("App", "Added unpassed waypoint: ${tripWaypoint.location.google_place_name} (order: ${tripWaypoint.order})")
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
            Logging.d("App", "Added destination waypoint")
        } ?: run {
            Logging.e("App", "Trip response is null, cannot create waypoints")
        }

        Logging.d("App", "Total waypoints created: ${waypoints.size}")
        return waypoints
    }

    private fun addWaypointMarkersToMap() {
        Logging.d("App", "=== STARTING addWaypointMarkersToMap ===")
        
        // Clear existing waypoint markers
        clearWaypointMarkers()
        
        tripResponse?.let { trip ->
            Logging.d("App", "Trip data available, adding waypoint markers")
            Logging.d("App", "Trip ID: ${trip.id}")
            Logging.d("App", "Origin: ${trip.route.origin.google_place_name}")
            Logging.d("App", "Destination: ${trip.route.destination.google_place_name}")
            Logging.d("App", "Intermediate waypoints: ${trip.waypoints.size}")
            
            // Add origin marker
            Logging.d("App", "Adding origin marker...")
            val originMarker = createWaypointMarker(
                GeoCoordinates(trip.route.origin.latitude, trip.route.origin.longitude),
                R.drawable.green_dot,
                "Origin: ${trip.route.origin.google_place_name}"
            )
            waypointMarkers.add(originMarker)
            mapView.mapScene.addMapMarker(originMarker)
            Logging.d("App", "Origin marker added to map")
            
            // Add intermediate waypoint markers (only unpassed ones)
            val sortedWaypoints = trip.waypoints
                .filter { !it.is_passed } // Only include unpassed waypoints
                .sortedBy { it.order }
            Logging.d("App", "Adding ${sortedWaypoints.size} unpassed intermediate waypoint markers...")
            sortedWaypoints.forEachIndexed { index, tripWaypoint ->
                Logging.d("App", "Adding unpassed waypoint $index: ${tripWaypoint.location.google_place_name}")
                val marker = createWaypointMarker(
                    GeoCoordinates(tripWaypoint.location.latitude, tripWaypoint.location.longitude),
                    R.drawable.green_dot,
                    "Waypoint: ${tripWaypoint.location.google_place_name}"
                )
                waypointMarkers.add(marker)
                mapView.mapScene.addMapMarker(marker)
                Logging.d("App", "Unpassed waypoint $index marker added to map")
            }
            
            // Add destination marker
            Logging.d("App", "Adding destination marker...")
            val destinationMarker = createWaypointMarker(
                GeoCoordinates(trip.route.destination.latitude, trip.route.destination.longitude),
                R.drawable.green_dot,
                "Destination: ${trip.route.destination.google_place_name}"
            )
            waypointMarkers.add(destinationMarker)
            mapView.mapScene.addMapMarker(destinationMarker)
            Logging.d("App", "Destination marker added to map")
            
            Logging.d("App", "=== COMPLETED addWaypointMarkersToMap ===")
            Logging.d("App", "Total waypoint markers added: ${waypointMarkers.size}")
            messageView.updateText("Added ${waypointMarkers.size} waypoint markers to map")
        } ?: run {
            Logging.e("App", "Trip response is null, cannot add waypoint markers")
            messageView.updateText("No trip data available for waypoint markers")
        }
    }

    private fun createWaypointMarker(geoCoordinates: GeoCoordinates, resourceId: Int, title: String): MapMarker {
        Logging.d("App", "Creating waypoint marker at: ${geoCoordinates.latitude}, ${geoCoordinates.longitude}")
        Logging.d("App", "Using resource ID: $resourceId")
        
        val mapImage = MapImageFactory.fromResource(context.resources, resourceId)
        val mapMarker = MapMarker(geoCoordinates, mapImage)
        
        Logging.d("App", "Waypoint marker created successfully")
        return mapMarker
    }

    private fun clearWaypointMarkers() {
        for (marker in waypointMarkers) {
            mapView.mapScene.removeMapMarker(marker)
        }
        waypointMarkers.clear()
        Logging.d("App", "Cleared waypoint markers")
    }

    fun markWaypointAsPassed(waypointIndex: Int) {
        if (waypointIndex >= 0 && waypointIndex < waypointMarkers.size) {
            // Remove the passed waypoint marker
            val marker = waypointMarkers[waypointIndex]
            mapView.mapScene.removeMapMarker(marker)
            waypointMarkers.removeAt(waypointIndex)
            
            Logging.d("App", "Marked waypoint $waypointIndex as passed and removed from map")
            messageView.updateText("Waypoint $waypointIndex passed")
        }
    }


    private fun showRouteDetails(route: Route, isSimulated: Boolean) {
        val estimatedTravelTimeInSeconds = route.duration.seconds
        val lengthInMeters = route.lengthInMeters
        val routeSections = route.sections.size

        // Log overall route details
        val routeDetails =
            (("Travel Time: " + timeUtils.formatTime(estimatedTravelTimeInSeconds)
                    ) + ", Length: " + timeUtils.formatLength(lengthInMeters))

        Logging.d("App", "=== ROUTE DETAILS ===")
        Logging.d("App", "Overall Route Details: $routeDetails")
        Logging.d("App", "Total sections: $routeSections")
        
        // Log ETA for overall route
        val overallETA = timeUtils.getETAinDeviceTimeZone(estimatedTravelTimeInSeconds.toInt())
        Logging.d("App", "Overall ETA: $overallETA")
        
        // Log details for each section and update waypoint progress
        Logging.d("App", "=== SECTION DETAILS ===")
        updateWaypointProgressFromRouteSections(route)
        
        for (i in route.sections.indices) {
            val dateFormat: DateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val section: Section = route.sections[i]

            val formattedDepartureLocationTime = section.departureLocationTime
                ?.localTime
                ?.let(dateFormat::format)
                ?: "Unknown"

            val formattedArrivalLocationTime = section.arrivalLocationTime
                ?.localTime
                ?.let(dateFormat::format)
                ?: "Unknown"

            Logging.d("APP", "Route Section : " + (i + 1))
            Logging.d("APP", "Route Section Departure Time : $formattedDepartureLocationTime")
            Logging.d("APP", "Route Section Arrival Time : $formattedArrivalLocationTime")
            Logging.d("APP", "Route Section length : " + section.lengthInMeters + " m")
            Logging.d("APP", "Route Section duration : " + section.duration.seconds + " s")
        }

        // Automatically start navigation without confirmation
        messageView.updateText("Route: ${timeUtils.formatTime(estimatedTravelTimeInSeconds)}, ${timeUtils.formatLength(lengthInMeters)}")
        
        Logging.d("App", "Starting navigation automatically...")
        messageView.updateText("Starting navigation...")
        navigationExample.startNavigation(route, isSimulated, isCameraTrackingEnabled)
    }

    private fun showRouteOnMap(route: Route) {
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
            Logging.e("App", e.error.name)
        } catch (e: MapMeasureDependentRenderSize.InstantiationException) {
            Logging.e("App", e.error.name)
        }

        mapView.mapScene.addMapPolyline(routeMapPolyline!!)
        mapPolylines.add(routeMapPolyline)
    }

    private fun clearMap() {
        clearWaypointMapMarker()
        clearWaypointMarkers() // Clear trip waypoint markers
        clearRoute()
        navigationExample.stopNavigation(isCameraTrackingEnabled)
    }

    private fun clearWaypointMapMarker() {
        for (mapMarker in mapMarkerList) {
            mapView.mapScene.removeMapMarker(mapMarker)
        }
        mapMarkerList.clear()
    }

    private fun clearRoute() {
        for (mapPolyline in mapPolylines) {
            mapView.mapScene.removeMapPolyline(mapPolyline!!)
        }
        mapPolylines.clear()
    }

    private fun setLongPressGestureHandler() {
        mapView.gestures.longPressListener =
            LongPressListener { gestureState: GestureState, touchPoint: Point2D? ->
                val geoCoordinates = mapView.viewToGeoCoordinates(
                    touchPoint!!
                )
                if (geoCoordinates == null) {
                    return@LongPressListener
                }
                if (gestureState == GestureState.BEGIN) {
                    if (setLongpressDestination) {
                        destinationWaypoint = Waypoint(geoCoordinates)
                        addCircleMapMarker(geoCoordinates, R.drawable.green_dot)
                        messageView.updateText("New long press destination set.")
                    } else {
                        startWaypoint = Waypoint(geoCoordinates)
                        addCircleMapMarker(geoCoordinates, R.drawable.green_dot)
                        messageView.updateText("New long press starting point set.")
                    }
                    setLongpressDestination = !setLongpressDestination
                }
            }
    }

    private fun createRandomGeoCoordinatesAroundMapCenter(): GeoCoordinates {
        val centerGeoCoordinates = mapViewCenter
        val lat = centerGeoCoordinates.latitude
        val lon = centerGeoCoordinates.longitude
        return GeoCoordinates(
            getRandom(lat - 0.02, lat + 0.02),
            getRandom(lon - 0.02, lon + 0.02)
        )
    }

    private fun getRandom(min: Double, max: Double): Double {
        return min + Math.random() * (max - min)
    }

    private val mapViewCenter: GeoCoordinates
        get() = mapView.camera.state.targetCoordinates

    private fun addCircleMapMarker(geoCoordinates: GeoCoordinates, resourceId: Int) {
        val mapImage = MapImageFactory.fromResource(context.resources, resourceId)
        val mapMarker = MapMarker(geoCoordinates, mapImage)

        mapView.mapScene.addMapMarker(mapMarker)
        mapMarkerList.add(mapMarker)
    }

    private fun showDialog(title: String, text: String) {
        DialogManager.show(title, text, buttonText = "Ok") {}
    }

    /**
     * Update waypoint progress from route sections after route calculation
     */
    private fun updateWaypointProgressFromRouteSections(route: Route) {
        try {
            tripResponse?.let { trip ->
                Logging.d("App", "=== UPDATING WAYPOINT PROGRESS FROM ROUTE SECTIONS ===")
                Logging.d("App", "Route has ${route.sections.size} sections")
                Logging.d("App", "Trip has ${trip.waypoints.size} waypoints")
                
                if (trip.waypoints.isEmpty()) {
                    Logging.d("App", "Single destination route - no waypoints to update")
                    return
                }
                
                // Get unpassed waypoints sorted by order
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }
                
                Logging.d("App", "Found ${unpassedWaypoints.size} unpassed waypoints")
                
                if (unpassedWaypoints.isNotEmpty()) {
                    // Calculate cumulative remaining time and distance for each waypoint
                    var cumulativeDistance = 0.0
                    var cumulativeTime = 0L
                    
                    // Start from the end and work backwards to calculate remaining values
                    val totalDistance = route.lengthInMeters.toDouble()
                    val totalTime = route.duration.seconds
                    
                    Logging.d("App", "Total route distance: ${String.format("%.1f", totalDistance)}m")
                    Logging.d("App", "Total route time: ${formatDuration(totalTime)}")
                    
                    unpassedWaypoints.forEachIndexed { index, waypoint ->
                        // Calculate remaining distance and time for this waypoint
                        // Each waypoint gets progressively less remaining time/distance
                        val waypointOrder = waypoint.order
                        val totalUnpassedWaypoints = unpassedWaypoints.size
                        
                        // Calculate proportional remaining values
                        // First waypoint gets most remaining, last waypoint gets least
                        val orderRatio = (totalUnpassedWaypoints - index).toDouble() / totalUnpassedWaypoints.toDouble()
                        val remainingTime = (totalTime * orderRatio).toLong()
                        val remainingDistance = totalDistance * orderRatio
                        
                        // Add some buffer to ensure proper ordering
                        val timeBuffer = (totalTime * 0.1 * orderRatio).toLong() // 10% buffer
                        val distanceBuffer = totalDistance * 0.1 * orderRatio // 10% buffer
                        
                        val finalTime = remainingTime + timeBuffer
                        val finalDistance = remainingDistance + distanceBuffer
                        
                        Logging.d("App", "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Logging.d("App", "  - Order ratio: ${String.format("%.2f", orderRatio)}")
                        Logging.d("App", "  - Base time: ${formatDuration(remainingTime)}, Base distance: ${String.format("%.1f", remainingDistance)}m")
                        Logging.d("App", "  - Buffer time: ${formatDuration(timeBuffer)}, Buffer distance: ${String.format("%.1f", distanceBuffer)}m")
                        Logging.d("App", "  - Final time: ${formatDuration(finalTime)}, Final distance: ${String.format("%.1f", finalDistance)}m")
                        
                        // Update waypoint in database
                        coroutineScope.launch {
                            try {
                                databaseManager.updateWaypointRemaining(
                                    tripId = trip.id,
                                    waypointId = waypoint.id,
                                    remainingTimeSeconds = finalTime,
                                    remainingDistanceMeters = finalDistance
                                )
                                Logging.d("App", "Updated waypoint ${waypoint.id} (Order: ${waypoint.order}) in database from route sections")
                            } catch (e: Exception) {
                                Logging.e("App", "Failed to update waypoint ${waypoint.id} from route sections: ${e.message}", e)
                            }
                        }
                    }
                } else {
                    Logging.d("App", "No unpassed waypoints to update")
                }
                
                Logging.d("App", "=======================================================")
            }
        } catch (e: Exception) {
            Logging.e("App", "Error updating waypoint progress from route sections: ${e.message}", e)
        }
    }

    /**
     * Format duration in seconds to human-readable format
     */
    private fun formatDuration(seconds: Long): String {
        return try {
            when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> {
                    val hours = seconds / 3600
                    val minutes = (seconds % 3600) / 60
                    "${hours}h ${minutes}m"
                }
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Get the route progress tracker for external access
     */
    fun getRouteProgressTracker(): RouteProgressTracker? = routeProgressTracker

    fun clearMapAndExit() {
        Logging.d("App", "Clearing map and exiting navigation")
        clearMap()
        messageView.updateText("Navigation stopped. Returning to main screen...")
        
        // Exit back to MainActivity
        // Note: This will be handled by the NavigActivity
    }

    fun detach() {
        // Disables TBT guidance (if running) and enters tracking mode.
        navigationExample.stopNavigation(isCameraTrackingEnabled)
        // Disables positioning.
        navigationExample.stopLocating()
        // Disables rendering.
        navigationExample.stopRendering()
        // Reset route progress tracker
        routeProgressTracker?.reset()
        routeProgressTracker = null
    }

    companion object {
        val DEFAULT_MAP_CENTER: GeoCoordinates = GeoCoordinates(52.520798, 13.409408)
        const val DEFAULT_DISTANCE_IN_METERS: Int = 1000 * 2
    }
}
