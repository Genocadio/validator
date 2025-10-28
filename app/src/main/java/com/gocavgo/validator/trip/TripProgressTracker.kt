package com.gocavgo.validator.trip

import android.annotation.SuppressLint
import android.util.Log
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.WaypointProgress
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.dataclass.SavePlaceResponse
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.ui.PassengerNotificationDialog
import android.content.Context
import com.here.sdk.navigation.RouteProgress
import com.here.sdk.navigation.SectionProgress
import com.here.sdk.routing.Route
import com.here.sdk.core.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Enhanced Trip Progress Tracker with comprehensive MQTT messaging
 * 
 * MQTT Event Schedule:
 * 1. TRIP_STARTED - Sent when navigation begins
 * 2. WAYPOINT_REACHED - Sent when each waypoint is reached + trip progress update
 * 3. WAYPOINT_MISSED - Sent when a waypoint is missed + trip progress update
 * 4. TRIP_PROGRESS_UPDATE - Sent every 3 minutes during navigation
 * 5. TRIP_COMPLETED - Sent when final waypoint reached + final progress update
 */
class TripProgressTracker(
    private val tripId: Int,
    private val databaseManager: DatabaseManager,
    private val coroutineScope: CoroutineScope,
    private val context: Context,
    private val mqttService: MqttService? = null
) {
    companion object {
        private const val TAG = "TripProgressTracker"
        private const val WAYPOINT_REACHED_THRESHOLD_METERS = 10.0
        private const val WAYPOINT_APPROACHING_THRESHOLD_METERS = 100.0
        private const val WAYPOINT_APPROACHING_TIME_SECONDS = 300L // 5 minutes
    }

    private var currentTrip: TripResponse? = null
    private var currentRoute: Route? = null
    private var waypointProgressMap = mutableMapOf<Int, WaypointProgress>()
    private var lastLoggedProgress = mutableMapOf<Int, Long>()
    private var reachedWaypoints = mutableSetOf<Int>()
    private var isInitialized = false
    private var currentLocation: Location? = null
    private var currentSpeed: Double? = null
    private var speedAccuracy: Double? = null
    
    // Store the most recent route progress data for MQTT calculations
    private var lastRouteProgressData: Pair<Double, Long>? = null // (distance, time)
    
    // Passenger notification management
    private val passengerNotificationDialog = PassengerNotificationDialog(context)
    private val approachingNotificationsShown = mutableSetOf<Int>()
    private val reachedNotificationsShown = mutableSetOf<Int>()
    private val lastPassengerCheckTime = mutableMapOf<Int, Long>() // Track last check time per waypoint
    private val waypointPassedTime = mutableMapOf<Int, Long>() // Track when each waypoint was passed

    init {
        try {
            loadTripData()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TripProgressTracker: ${e.message}", e)
        }
    }

    private fun loadTripData() {
        coroutineScope.launch {
            try {
                currentTrip = databaseManager.getTripById(tripId)
                if (currentTrip != null) {
                    val trip = currentTrip!!
                    Log.d(TAG, "=== TRIP PROGRESS TRACKER INITIALIZED ===")
                    Log.d(TAG, "Trip ID: ${trip.id}")
                    Log.d(TAG, "Vehicle: ${trip.vehicle.license_plate}")
                    Log.d(TAG, "Route Type: ${if (trip.waypoints.isEmpty()) "Single Destination" else "Multi-Waypoint"}")
                    Log.d(TAG, "Origin: ${trip.route.origin.google_place_name}")
                    Log.d(TAG, "Destination: ${trip.route.destination.google_place_name}")
                    Log.d(TAG, "Total waypoints: ${trip.waypoints.size}")
                    if (trip.waypoints.isNotEmpty()) {
                        Log.d(TAG, "Waypoints:")
                        trip.waypoints.sortedBy { it.order }.forEach { waypoint ->
                            Log.d(TAG, "  ${waypoint.order}. ${waypoint.location.google_place_name}")
                        }
                    }
                    Log.d(TAG, "Status: ${trip.status}")
                    Log.d(TAG, "================================")
                    isInitialized = true
                } else {
                    Log.e(TAG, "Failed to load trip data for ID: $tripId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading trip data: ${e.message}", e)
            }
        }
    }

    fun setCurrentRoute(route: Route?) {
        try {
            if (route == null) {
                Log.w(TAG, "Attempted to set null route")
                return
            }

            currentRoute = route
            Log.d(TAG, "=== ROUTE SET FOR PROGRESS TRACKING ===")
            Log.d(TAG, "Route sections: ${route.sections.size}")
            Log.d(TAG, "Total distance: ${String.format("%.1f", route.lengthInMeters / 1000.0)}km")
            Log.d(TAG, "Estimated duration: ${formatDuration(route.duration.seconds)}")

            currentTrip?.let { trip ->
                if (trip.waypoints.isEmpty()) {
                    Log.d(TAG, "Route Type: Single Destination")
                    Log.d(TAG, "From: ${trip.route.origin.google_place_name}")
                    Log.d(TAG, "To: ${trip.route.destination.google_place_name}")
                } else {
                    Log.d(TAG, "Route Type: Multi-Waypoint")
                    Log.d(TAG, "From: ${trip.route.origin.google_place_name}")
                    Log.d(TAG, "To: ${trip.route.destination.google_place_name}")
                    Log.d(TAG, "Waypoints: ${trip.waypoints.size}")
                }
                
                // Initialize remaining data for waypoints to prevent null values
                initializeWaypointRemainingData(route.lengthInMeters.toDouble(), route.duration.seconds)
            }
            Log.d(TAG, "================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting current route: ${e.message}", e)
        }
    }

    /**
     * Initialize remaining time/distance for all waypoints when route is first set
     * This prevents null values from being sent in MQTT messages
     * Ensures proper ordering where higher order waypoints have more remaining time/distance
     */
    private fun initializeWaypointRemainingData(totalDistanceMeters: Double, totalTimeSeconds: Long) {
        try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== INITIALIZING WAYPOINT REMAINING DATA ===")
                Log.d(TAG, "Total route distance: ${String.format("%.1f", totalDistanceMeters)}m")
                Log.d(TAG, "Total route time: ${formatDuration(totalTimeSeconds)}")
                
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }
                
                if (unpassedWaypoints.isNotEmpty()) {
                    Log.d(TAG, "Initializing ${unpassedWaypoints.size} unpassed waypoints with baseline remaining data")
                    
                    // Calculate proper initial values for each waypoint based on order
                    // Higher order waypoints should have more remaining time/distance
                    unpassedWaypoints.forEachIndexed { index, waypoint ->
                        // Only initialize if values are currently null
                        if (waypoint.remaining_time == null || waypoint.remaining_distance == null) {
                            val waypointOrder = waypoint.order
                            val totalUnpassedWaypoints = unpassedWaypoints.size
                            
                            // Calculate proportional remaining time/distance based on waypoint order
                            // Each waypoint gets a share proportional to its order
                            val orderRatio = waypointOrder.toDouble() / totalUnpassedWaypoints.toDouble()
                            val baseTime = (totalTimeSeconds * 0.8 * orderRatio).toLong() // 80% of total time distributed by order
                            val baseDistance = totalDistanceMeters * 0.8 * orderRatio // 80% of total distance distributed by order
                            
                            // Add minimum buffer to ensure higher order waypoints have more time/distance
                            val timeBuffer = (totalTimeSeconds * 0.1 * orderRatio).toLong() // 10% buffer per order
                            val distanceBuffer = totalDistanceMeters * 0.1 * orderRatio // 10% buffer per order
                            
                            val initialTime = waypoint.remaining_time ?: (baseTime + timeBuffer)
                            val initialDistance = waypoint.remaining_distance ?: (baseDistance + distanceBuffer)
                            
                            Log.d(TAG, "Initializing waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                            Log.d(TAG, "  - Order ratio: ${String.format("%.2f", orderRatio)}")
                            Log.d(TAG, "  - Base time: ${formatDuration(baseTime)}, Base distance: ${String.format("%.1f", baseDistance)}m")
                            Log.d(TAG, "  - Final time: ${formatDuration(initialTime)}, Final distance: ${String.format("%.1f", initialDistance)}m")
                            
                            coroutineScope.launch {
                                try {
                                    databaseManager.updateWaypointRemaining(
                                        tripId = tripId,
                                        waypointId = waypoint.id,
                                        remainingTimeSeconds = initialTime,
                                        remainingDistanceMeters = initialDistance
                                    )
                                    
                                    // Update in-memory data
                                    currentTrip?.let { trip ->
                                        val updatedWaypoints = trip.waypoints.map { wp ->
                                            if (wp.id == waypoint.id) {
                                                wp.copy(
                                                    remaining_time = initialTime,
                                                    remaining_distance = initialDistance
                                                )
                                            } else wp
                                        }
                                        currentTrip = trip.copy(waypoints = updatedWaypoints)
                                    }
                                    
                                    Log.d(TAG, "Initialized waypoint ${waypoint.id} (Order: ${waypoint.order}) with baseline remaining data")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to initialize waypoint ${waypoint.id}: ${e.message}", e)
                                }
                            }
                        } else {
                            Log.d(TAG, "Waypoint ${waypoint.order} already has remaining data: time=${waypoint.remaining_time?.let { formatDuration(it) } ?: "null"}, distance=${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
                        }
                    }
                } else {
                    Log.d(TAG, "No unpassed waypoints to initialize")
                }
                
                Log.d(TAG, "=============================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing waypoint remaining data: ${e.message}", e)
        }
    }

    /**
     * Update current vehicle speed and position data
     */
    fun updateCurrentSpeedAndPosition(
        speedInMetersPerSecond: Double?,
        speedAccuracyInMetersPerSecond: Double?,
        location: Location?
    ) {
        try {
            currentSpeed = speedInMetersPerSecond
            speedAccuracy = speedAccuracyInMetersPerSecond
            currentLocation = location

            Log.d(TAG, "=== SPEED & POSITION UPDATE ===")
            Log.d(TAG, "Speed: ${speedInMetersPerSecond?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
            Log.d(TAG, "Speed accuracy: ${speedAccuracyInMetersPerSecond?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
            Log.d(TAG, "Location: ${location?.coordinates?.let { "${it.latitude}, ${it.longitude}" } ?: "Unknown"}")
            Log.d(TAG, "==============================")

            // Update vehicle location in database
            currentTrip?.let { trip ->
                coroutineScope.launch {
                    try {
                        location?.let { loc ->
                            databaseManager.updateVehicleCurrentLocation(
                                vehicleId = trip.vehicle_id,
                                latitude = loc.coordinates.latitude,
                                longitude = loc.coordinates.longitude,
                                speed = speedInMetersPerSecond ?: 0.0,
                                accuracy = speedAccuracy ?: 0.0,
                                bearing = null // Location object doesn't provide bearing
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update vehicle location in database: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating speed and position: ${e.message}", e)
        }
    }

    @SuppressLint("SuspiciousIndentation")
    fun onRouteProgressUpdate(routeProgress: RouteProgress?) {
        try {
            if (routeProgress == null) {
                Log.w(TAG, "Received null route progress update")
                return
            }

            if (!isInitialized) {
                Log.w(TAG, "TripProgressTracker not yet initialized, skipping progress update")
                return
            }

            val sectionProgressList = routeProgress.sectionProgress

                if (sectionProgressList.isNotEmpty()) {
                val currentSectionProgress = sectionProgressList[0]

                // Safe access to section progress properties
                val remainingDistance = try {
                    currentSectionProgress.remainingDistanceInMeters.toDouble()
                } catch (e: Exception) {
                    Log.w(TAG, "Error getting remaining distance: ${e.message}")
                    0.0
                }

                val remainingDuration = try {
                    currentSectionProgress.remainingDuration.seconds
                } catch (e: Exception) {
                    Log.w(TAG, "Error getting remaining duration: ${e.message}")
                    0L
                }

                val trafficDelay = try {
                    currentSectionProgress.trafficDelay.seconds
                } catch (e: Exception) {
                    Log.w(TAG, "Error getting traffic delay: ${e.message}")
                    0L
                }

                // Store the most recent route progress data for MQTT calculations
                lastRouteProgressData = Pair(remainingDistance, remainingDuration)

                // Also persist destination-level remaining metrics for convenience
                coroutineScope.launch {
                    try {
                        // If trip has no intermediate waypoints, persist to a synthetic destination placeholder (-1)
                        if (currentTrip?.waypoints?.isEmpty() == true) {
                            // No waypoint to write; skip
                        } else {
                            // Persist on the next waypoint entry for durability/fallback in MQTT
                            val nextIndex = getNextWaypointIndex()
                            val wp = getWaypointByIndex(nextIndex)
                            if (wp != null) {
                                databaseManager.updateWaypointRemaining(
                                    tripId = tripId,
                                    waypointId = wp.id,
                                    remainingTimeSeconds = remainingDuration,
                                    remainingDistanceMeters = remainingDistance
                                )
                                
                                // Update the in-memory currentTrip with the updated waypoint data
                                currentTrip?.let { trip ->
                                    val updatedWaypoints = trip.waypoints.map { waypoint ->
                                        if (waypoint.id == wp.id) {
                                            waypoint.copy(
                                                remaining_time = remainingDuration,
                                                remaining_distance = remainingDistance
                                            )
                                        } else waypoint
                                    }
                                    currentTrip = trip.copy(waypoints = updatedWaypoints)
                                    Log.d(TAG, "Updated in-memory currentTrip with destination remaining data for waypoint ${wp.id}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist destination remaining metrics: ${e.message}", e)
                    }
                }

                // Get current waypoint information
                val currentWaypointIndex = getCurrentWaypointIndex(currentSectionProgress)
                val currentWaypoint = currentWaypointIndex?.let { getWaypointByIndex(it) }
                val nextWaypointIndex = getNextWaypointIndex()
                val nextWaypoint = getWaypointByIndex(nextWaypointIndex)

                Log.d(TAG, "=== ROUTE PROGRESS UPDATE ===")

                if (currentTrip?.waypoints?.isEmpty() == true) {
                    // Single destination route (no intermediate waypoints)
                    Log.d(TAG, "🚗 SINGLE DESTINATION ROUTE")
                    Log.d(TAG, "Destination: ${currentTrip?.route?.destination?.google_place_name ?: "Unknown"}")
                    Log.d(TAG, "Remaining distance: ${String.format("%.1f", remainingDistance)}m")
                    Log.d(TAG, "Remaining time: ${formatDuration(remainingDuration)}")
                    Log.d(TAG, "Traffic delay: ${formatDuration(trafficDelay)}")
                    
                    // Store progress for single destination route
                    currentTrip?.let { trip ->
                        val destinationProgress = WaypointProgress(
                            waypointId = -1, // Special ID for destination
                            tripId = tripId,
                            order = 0,
                            locationName = trip.route.destination.google_place_name,
                            remainingDistanceInMeters = remainingDistance,
                            remainingTimeInSeconds = remainingDuration,
                            trafficDelayInSeconds = trafficDelay,
                            isReached = false,
                            isNext = true, // Always next for single destination
                            timestamp = System.currentTimeMillis(),
                            currentLatitude = currentLocation?.coordinates?.latitude,
                            currentLongitude = currentLocation?.coordinates?.longitude,
                            currentSpeedInMetersPerSecond = currentSpeed,
                            speedAccuracyInMetersPerSecond = speedAccuracy
                        )
                        waypointProgressMap[-1] = destinationProgress
                        Log.d(TAG, "Stored destination progress for single destination route")
                    }
                } else {
                    // Multi-waypoint route
                    Log.d(TAG, "🗺️ MULTI-WAYPOINT ROUTE")
                    Log.d(TAG, "Current waypoint: ${currentWaypoint?.location?.google_place_name ?: "Origin"}")
                    Log.d(TAG, "Next waypoint: ${nextWaypoint?.location?.google_place_name ?: "Destination"}")
                    Log.d(TAG, "Remaining distance to next: ${String.format("%.1f", remainingDistance)}m")
                    Log.d(TAG, "ETA to next: ${formatDuration(remainingDuration)}")
                    Log.d(TAG, "Traffic delay: ${formatDuration(trafficDelay)}")

                    // Store progress for the next waypoint
                    nextWaypoint?.let { waypoint ->
                        val waypointProgress = WaypointProgress(
                            waypointId = waypoint.id,
                            tripId = tripId,
                            order = waypoint.order,
                            locationName = waypoint.location.google_place_name,
                            remainingDistanceInMeters = remainingDistance,
                            remainingTimeInSeconds = remainingDuration,
                            trafficDelayInSeconds = trafficDelay,
                            isReached = false,
                            isNext = true,
                            timestamp = System.currentTimeMillis(),
                            currentLatitude = currentLocation?.coordinates?.latitude,
                            currentLongitude = currentLocation?.coordinates?.longitude,
                            currentSpeedInMetersPerSecond = currentSpeed,
                            speedAccuracyInMetersPerSecond = speedAccuracy
                        )
                        waypointProgressMap[waypoint.id] = waypointProgress
                        Log.d(TAG, "Stored progress for waypoint: ${waypoint.location.google_place_name} (Order: ${waypoint.order})")

                        // Persist remaining progress for resilience
                        coroutineScope.launch {
                            try {
                                databaseManager.updateWaypointRemaining(
                                    tripId = tripId,
                                    waypointId = waypoint.id,
                                    remainingTimeSeconds = waypointProgress.remainingTimeInSeconds,
                                    remainingDistanceMeters = waypointProgress.remainingDistanceInMeters
                                )
                                
                                // Update the in-memory currentTrip with the updated waypoint data
                                currentTrip?.let { trip ->
                                    val updatedWaypoints = trip.waypoints.map { wp ->
                                        if (wp.id == waypoint.id) {
                                            wp.copy(
                                                remaining_time = waypointProgress.remainingTimeInSeconds,
                                                remaining_distance = waypointProgress.remainingDistanceInMeters
                                            )
                                        } else wp
                                    }
                                    currentTrip = trip.copy(waypoints = updatedWaypoints)
                                    Log.d(TAG, "Updated in-memory currentTrip with fresh remaining data for waypoint ${waypoint.id} (Order: ${waypoint.order})")
                                }
                                
                                Log.d(TAG, "DB persist success for waypoint ${waypoint.id} (Order: ${waypoint.order}): time=${waypointProgress.remainingTimeInSeconds}, dist=${String.format("%.1f", waypointProgress.remainingDistanceInMeters)}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to persist waypoint remaining progress: ${e.message}", e)
                            }
                        }
                    }

                    // DISABLED: Let RouteProgressTracker handle waypoint updates with correct section-based logic
                    // updateAllUnpassedWaypoints(remainingDistance, remainingDuration)
                    Log.d(TAG, "Skipping waypoint updates - RouteProgressTracker handles this with correct section logic")

                // Check all waypoints for passenger notifications (3 minutes away or just passed)
                checkAllWaypointsForPassengerNotifications()
                checkPassedWaypointsForPassengerNotifications()
                checkFinalDestinationForPassengerNotifications()

                    // Log progress for all unpassed waypoints
                    logUnpassedWaypointsProgress()
                }

                // Check if we're approaching or reaching a waypoint
                when {
                    remainingDistance < WAYPOINT_REACHED_THRESHOLD_METERS -> {
                        handleWaypointReached(currentSectionProgress)
                    }
                    remainingDistance < WAYPOINT_APPROACHING_THRESHOLD_METERS -> {
                        handleWaypointApproaching(currentSectionProgress)
                    }
                    else -> {
                        handleWaypointProgress(currentSectionProgress)
                    }
                }

                Log.d(TAG, "=============================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in route progress update: ${e.message}", e)
        }
    }

    private fun handleWaypointReached(sectionProgress: SectionProgress) {
        try {
            val waypointIndex = getCurrentWaypointIndex(sectionProgress)
            if (waypointIndex != null && !reachedWaypoints.contains(waypointIndex)) {
                val waypoint = getWaypointByIndex(waypointIndex)
                if (waypoint != null) {
                    Log.d(TAG, "🎉 WAYPOINT REACHED!")
                    Log.d(TAG, "Waypoint: ${waypoint.location.google_place_name}")
                    Log.d(TAG, "Order: ${waypoint.order}")
                    Log.d(TAG, "Index: $waypointIndex")

                    val remainingDistance = try {
                        String.format("%.1f", sectionProgress.remainingDistanceInMeters)
                    } catch (e: Exception) {
                        "Unknown"
                    }
                    Log.d(TAG, "Remaining distance: ${remainingDistance}m")

                    reachedWaypoints.add(waypointIndex)
                    
                    // Track when this waypoint was passed
                    val waypoint = getWaypointByIndex(waypointIndex)
                    if (waypoint != null) {
                        waypointPassedTime[waypoint.id] = System.currentTimeMillis()
                    }
                    
                    updateWaypointStatus(waypointIndex, true)
                    logWaypointReached(waypointIndex, sectionProgress)

                    // Show passenger notification when waypoint is reached
                    if (!reachedNotificationsShown.contains(waypointIndex) && waypoint != null) {
                        reachedNotificationsShown.add(waypointIndex)
                        showPassengerReachedNotification(waypoint)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling waypoint reached: ${e.message}", e)
        }
    }

    private fun handleWaypointApproaching(sectionProgress: SectionProgress) {
        try {
            val waypointIndex = getCurrentWaypointIndex(sectionProgress)
            if (waypointIndex != null) {
                val waypoint = getWaypointByIndex(waypointIndex)
                if (waypoint != null) {
                    Log.d(TAG, "📍 APPROACHING WAYPOINT!")
                    Log.d(TAG, "Waypoint: ${waypoint.location.google_place_name}")
                    Log.d(TAG, "Order: ${waypoint.order}")

                    val remainingDistance = try {
                        String.format("%.1f", sectionProgress.remainingDistanceInMeters)
                    } catch (e: Exception) {
                        "Unknown"
                    }
                    Log.d(TAG, "Distance remaining: ${remainingDistance}m")

                    val remainingTimeSeconds = sectionProgress.remainingDuration.seconds
                    val eta = try {
                        formatDuration(remainingTimeSeconds)
                    } catch (e: Exception) {
                        "Unknown"
                    }
                    Log.d(TAG, "ETA: $eta")

                    // Check if we should show 5-minute notification
                    if (remainingTimeSeconds <= WAYPOINT_APPROACHING_TIME_SECONDS && 
                        !approachingNotificationsShown.contains(waypointIndex)) {
                        
                        approachingNotificationsShown.add(waypointIndex)
                        showPassengerApproachingNotification(waypoint)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling waypoint approaching: ${e.message}", e)
        }
    }

    private fun handleWaypointProgress(sectionProgress: SectionProgress) {
        try {
            val waypointIndex = getCurrentWaypointIndex(sectionProgress)
            if (waypointIndex != null) {
                val waypoint = getWaypointByIndex(waypointIndex)
                if (waypoint != null) {
                    val progress = WaypointProgress(
                        waypointId = waypoint.id,
                        tripId = tripId,
                        order = waypoint.order,
                        locationName = waypoint.location.google_place_name,
                        remainingDistanceInMeters = try {
                            sectionProgress.remainingDistanceInMeters.toDouble()
                        } catch (e: Exception) {
                            0.0
                        },
                        remainingTimeInSeconds = try {
                            sectionProgress.remainingDuration.seconds
                        } catch (e: Exception) {
                            0L
                        },
                        trafficDelayInSeconds = try {
                            sectionProgress.trafficDelay.seconds
                        } catch (e: Exception) {
                            0L
                        },
                        isReached = false,
                        isNext = waypointIndex == getNextWaypointIndex(),
                        timestamp = System.currentTimeMillis(),
                        currentLatitude = currentLocation?.coordinates?.latitude,
                        currentLongitude = currentLocation?.coordinates?.longitude,
                        currentSpeedInMetersPerSecond = currentSpeed,
                        speedAccuracyInMetersPerSecond = speedAccuracy
                    )

                    waypointProgressMap[waypoint.id] = progress

                    // Log progress updates (throttled to avoid spam)
                    val lastLogTime = lastLoggedProgress[waypoint.id] ?: 0L
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 30000) { // Log every 30 seconds
                        Log.d(TAG, "📊 TRACKING WAYPOINT: ${waypoint.location.google_place_name}")
                        logWaypointProgress(progress)
                        lastLoggedProgress[waypoint.id] = currentTime
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling waypoint progress: ${e.message}", e)
        }
    }

    private fun getCurrentWaypointIndex(sectionProgress: SectionProgress): Int? {
        return try {
            // The section progress index corresponds to the waypoint index
            // Note: sectionProgressList[0] always provides information for the next waypoint
            if (sectionProgress.remainingDistanceInMeters > 0) {
                // We're still approaching the next waypoint
                reachedWaypoints.size
            } else {
                // We've reached the waypoint, so this is the current one
                reachedWaypoints.size - 1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current waypoint index: ${e.message}", e)
            null
        }
    }

    private fun getNextWaypointIndex(): Int {
        return try {
            reachedWaypoints.size
        } catch (e: Exception) {
            Log.e(TAG, "Error getting next waypoint index: ${e.message}", e)
            0
        }
    }

    private fun getWaypointByIndex(index: Int): TripWaypoint? {
        return try {
            currentTrip?.waypoints?.find { it.order == index + 1 }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting waypoint by index $index: ${e.message}", e)
            null
        }
    }

    /**
     * Get waypoint by order (1-based order number)
     */
    private fun getWaypointByOrder(order: Int): TripWaypoint? {
        return try {
            currentTrip?.waypoints?.find { it.order == order }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting waypoint by order $order: ${e.message}", e)
            null
        }
    }

    /**
     * Get the next unpassed waypoint in order
     */
    private fun getNextUnpassedWaypoint(): TripWaypoint? {
        return try {
            currentTrip?.waypoints
                ?.filter { !it.is_passed }
                ?.minByOrNull { it.order }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting next unpassed waypoint: ${e.message}", e)
            null
        }
    }

    private fun updateWaypointStatus(waypointIndex: Int, reached: Boolean) {
        try {
            val waypoint = getWaypointByIndex(waypointIndex)
            if (waypoint != null) {
                coroutineScope.launch {
                    try {
                        // Update waypoint status in database
                        updateWaypointInDatabase(waypoint.id, reached)

                        // Send MQTT notifications for waypoint events
                        if (reached) {
                            sendWaypointReachedNotification(waypoint)
                            Log.d(TAG, "✅ MQTT: Waypoint reached notification sent for ${waypoint.location.google_place_name}")
                        } else {
                            sendWaypointMissedNotification(waypoint)
                            Log.w(TAG, "⚠️ MQTT: Waypoint missed notification sent for ${waypoint.location.google_place_name}")
                        }
                        
                        // Always send trip progress update when waypoint status changes
                        sendPeriodicProgressUpdate()
                        Log.d(TAG, "📊 MQTT: Trip progress update sent after waypoint ${if (reached) "reach" else "miss"}")

                        // Check if this was the final waypoint
                        if (reached && waypointIndex == currentTrip?.waypoints?.size) {
                            handleFinalWaypointReached()
                        }

                        Log.d(TAG, "Waypoint ${waypoint.location.google_place_name} status updated: ${if (reached) "REACHED" else "MISSED"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update waypoint status: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating waypoint status: ${e.message}", e)
        }
    }

    private suspend fun updateWaypointInDatabase(waypointId: Int, reached: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                // Update waypoint status in database
                databaseManager.updateWaypointStatus(tripId, waypointId, reached)
                Log.d(TAG, "Database update: Waypoint $waypointId marked as ${if (reached) "reached" else "missed"}")
            } catch (e: Exception) {
                Log.e(TAG, "Database update failed: ${e.message}", e)
            }
        }
    }

    private fun handleFinalWaypointReached() {
        try {
            Log.d(TAG, "🎯 FINAL WAYPOINT REACHED!")
            Log.d(TAG, "All waypoints completed for trip $tripId")

            coroutineScope.launch {
                try {
                    // Update trip status to completed
                    updateTripStatus("completed")
                    
                    // Send final trip progress update with completion status
                    sendPeriodicProgressUpdate()
                    
                    Log.d(TAG, "🏁 MQTT: Trip $tripId marked as completed and final status sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update trip status: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling final waypoint reached: ${e.message}", e)
        }
    }

    private suspend fun updateTripStatus(newStatus: String) {
        withContext(Dispatchers.IO) {
            try {
                databaseManager.updateTripStatus(tripId, newStatus)
                Log.d(TAG, "Trip $tripId status updated to: $newStatus")

                // Send MQTT trip status update
                sendTripStatusUpdate(newStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update trip status: ${e.message}", e)
            }
        }
    }

    private fun logWaypointReached(waypointIndex: Int, sectionProgress: SectionProgress) {
        try {
            val waypoint = getWaypointByIndex(waypointIndex)
            if (waypoint != null) {
                Log.d(TAG, "=== WAYPOINT REACHED LOG ===")
                Log.d(TAG, "Waypoint ID: ${waypoint.id}")
                Log.d(TAG, "Location: ${waypoint.location.google_place_name}")
                Log.d(TAG, "Order: ${waypoint.order}")
                Log.d(TAG, "Coordinates: ${waypoint.location.latitude}, ${waypoint.location.longitude}")

                val remainingDistance = try {
                    sectionProgress.remainingDistanceInMeters
                } catch (e: Exception) {
                    0.0
                }
                Log.d(TAG, "Remaining distance: ${remainingDistance}m")

                val remainingTime = try {
                    sectionProgress.remainingDuration.seconds
                } catch (e: Exception) {
                    0L
                }
                Log.d(TAG, "Remaining time: ${formatDuration(remainingTime)}")

                val trafficDelay = try {
                    sectionProgress.trafficDelay.seconds
                } catch (e: Exception) {
                    0L
                }
                Log.d(TAG, "Traffic delay: ${formatDuration(trafficDelay)}")
                Log.d(TAG, "=============================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging waypoint reached: ${e.message}", e)
        }
    }

    private fun logWaypointProgress(progress: WaypointProgress) {
        try {
            Log.d(TAG, "=== WAYPOINT PROGRESS UPDATE ===")
            Log.d(TAG, "Waypoint: ${progress.locationName}")
            Log.d(TAG, "Order: ${progress.order}")
            Log.d(TAG, "Remaining distance: ${String.format("%.1f", progress.remainingDistanceInMeters)}m")
            Log.d(TAG, "Remaining time: ${formatDuration(progress.remainingTimeInSeconds)}")
            Log.d(TAG, "Traffic delay: ${formatDuration(progress.trafficDelayInSeconds)}")
            Log.d(TAG, "Is next: ${progress.isNext}")
            Log.d(TAG, "Current position: ${progress.currentLatitude?.let { String.format("%.6f", it) } ?: "Unknown"}, ${progress.currentLongitude?.let { String.format("%.6f", it) } ?: "Unknown"}")
            Log.d(TAG, "Current speed: ${progress.currentSpeedInMetersPerSecond?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
            Log.d(TAG, "Speed accuracy: ${progress.speedAccuracyInMetersPerSecond?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
            Log.d(TAG, "===============================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging waypoint progress: ${e.message}", e)
        }
    }

    /**
     * Log progress for all unpassed waypoints in the route
     */
    private fun logUnpassedWaypointsProgress() {
        try {
            currentTrip?.let { trip ->
                val unpassedWaypoints = trip.waypoints.filter { !it.is_passed }

                if (unpassedWaypoints.isNotEmpty()) {
                    Log.d(TAG, "📍 UNPASSED WAYPOINTS PROGRESS:")
                    unpassedWaypoints.forEach { waypoint ->
                        val progress = waypointProgressMap[waypoint.id]
                        if (progress != null) {
                            Log.d(TAG, "  • ${waypoint.location.google_place_name}")
                            Log.d(TAG, "    - Order: ${waypoint.order}")
                            Log.d(TAG, "    - Distance: ${String.format("%.1f", progress.remainingDistanceInMeters)}m")
                            Log.d(TAG, "    - ETA: ${formatDuration(progress.remainingTimeInSeconds)}")
                            Log.d(TAG, "    - Traffic delay: ${formatDuration(progress.trafficDelayInSeconds)}")
                        } else {
                            Log.d(TAG, "  • ${waypoint.location.google_place_name} (Order: ${waypoint.order}) - No progress data yet")
                        }
                    }
                } else {
                    Log.d(TAG, "✅ All waypoints have been passed!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging unpassed waypoints progress: ${e.message}", e)
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
            Log.e(TAG, "Error formatting duration: ${e.message}", e)
            "Unknown"
        }
    }

    fun getCurrentProgress(): Map<Int, WaypointProgress> {
        return try {
            waypointProgressMap.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current progress: ${e.message}", e)
            emptyMap()
        }
    }

    fun getReachedWaypoints(): Set<Int> {
        return try {
            reachedWaypoints.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting reached waypoints: ${e.message}", e)
            emptySet()
        }
    }

    fun reset() {
        try {
            waypointProgressMap.clear()
            lastLoggedProgress.clear()
            reachedWaypoints.clear()
            approachingNotificationsShown.clear()
            reachedNotificationsShown.clear()
            lastPassengerCheckTime.clear()
            waypointPassedTime.clear()
            isInitialized = false
            passengerNotificationDialog.dismissCurrentNotification()
            Log.d(TAG, "Trip progress tracker reset for trip $tripId")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting progress tracker: ${e.message}", e)
        }
    }

    /**
     * Refresh the current trip data from database
     */
    private suspend fun refreshTripData() {
        try {
            val freshTrip = databaseManager.getTripById(tripId)
            if (freshTrip != null) {
                currentTrip = freshTrip
                Log.d(TAG, "Refreshed currentTrip data from database")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing trip data: ${e.message}", e)
        }
    }

    /**
     * Update remaining time/distance for all unpassed waypoints
     * This ensures no waypoint has null remaining_time or remaining_distance values
     * Uses simple progressive calculation since reversal is handled by backend
     */
    private fun updateAllUnpassedWaypoints(currentRemainingDistance: Double, currentRemainingTime: Long) {
        try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== UPDATING ALL UNPASSED WAYPOINTS ===")
                Log.d(TAG, "Trip is_reversed: ${trip.is_reversed} (handled by backend)")
                
                // Get all unpassed waypoints sorted by order
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }
                
                Log.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                Log.d(TAG, "Current remaining distance: ${String.format("%.1f", currentRemainingDistance)}m")
                Log.d(TAG, "Current remaining time: ${formatDuration(currentRemainingTime)}")
                
                if (unpassedWaypoints.isNotEmpty()) {
                    // Calculate proper remaining values for each waypoint
                    // Simple progressive calculation: each waypoint gets more time/distance than the previous
                    unpassedWaypoints.forEachIndexed { index, waypoint ->
                        val waypointOrder = waypoint.order
                        
                        // Simple progressive calculation: each waypoint gets more time/distance than the previous
                        // The first waypoint gets the current remaining time/distance
                        // Each subsequent waypoint gets additional time/distance
                        val additionalTimePerWaypoint = 30L // 30 seconds per waypoint
                        val additionalDistancePerWaypoint = 200.0 // 200 meters per waypoint
                        
                        val calculatedTime = currentRemainingTime + (additionalTimePerWaypoint * (waypointOrder - 1))
                        val calculatedDistance = currentRemainingDistance + (additionalDistancePerWaypoint * (waypointOrder - 1))
                        
                        Log.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Log.d(TAG, "  - Base time: ${formatDuration(currentRemainingTime)}, Base distance: ${String.format("%.1f", currentRemainingDistance)}m")
                        Log.d(TAG, "  - Additional time: ${formatDuration(additionalTimePerWaypoint * (waypointOrder - 1))}, Additional distance: ${String.format("%.1f", additionalDistancePerWaypoint * (waypointOrder - 1))}m")
                        Log.d(TAG, "  - Final time: ${formatDuration(calculatedTime)}, Final distance: ${String.format("%.1f", calculatedDistance)}m")
                        
                        // Only update if current values are null or incorrect
                        val needsUpdate = waypoint.remaining_time == null || 
                                        waypoint.remaining_distance == null ||
                                        (waypoint.remaining_time != null && waypoint.remaining_time!! < currentRemainingTime) ||
                                        (waypoint.remaining_distance != null && waypoint.remaining_distance!! < currentRemainingDistance)
                        
                        if (needsUpdate) {
                            Log.d(TAG, "  - Current values: time=${waypoint.remaining_time?.let { formatDuration(it) } ?: "null"}, distance=${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
                            
                            coroutineScope.launch {
                                try {
                                    databaseManager.updateWaypointRemaining(
                                        tripId = tripId,
                                        waypointId = waypoint.id,
                                        remainingTimeSeconds = calculatedTime,
                                        remainingDistanceMeters = calculatedDistance
                                    )
                                    
                                    // Update in-memory data
                                    currentTrip?.let { trip ->
                                        val updatedWaypoints = trip.waypoints.map { wp ->
                                            if (wp.id == waypoint.id) {
                                                wp.copy(
                                                    remaining_time = calculatedTime,
                                                    remaining_distance = calculatedDistance
                                                )
                                            } else wp
                                        }
                                        currentTrip = trip.copy(waypoints = updatedWaypoints)
                                    }
                                    
                                    Log.d(TAG, "Successfully updated waypoint ${waypoint.id} with calculated remaining data")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to update waypoint ${waypoint.id}: ${e.message}", e)
                                }
                            }
                        } else {
                            Log.d(TAG, "  - Already has correct remaining data: time=${waypoint.remaining_time?.let { formatDuration(it) } ?: "null"}, distance=${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
                        }
                    }
                } else {
                    Log.d(TAG, "No unpassed waypoints to update")
                }
                
                Log.d(TAG, "=====================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating all unpassed waypoints: ${e.message}", e)
        }
    }

    /**
     * Send trip status update via MQTT using TripEventMessage
     */
    private fun sendTripStatusUpdate(status: String) {
        try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    currentTrip?.let { trip ->
                        val location = MqttService.Location(
                            latitude = currentLocation?.coordinates?.latitude ?: 0.0,
                            longitude = currentLocation?.coordinates?.longitude ?: 0.0
                        )

                        val tripData = mqtt.convertTripResponseToTripData(
                            tripResponse = trip,
                            currentSpeed = currentSpeed,
                            currentLocation = location,
                            speedAccuracy = speedAccuracy
                        )

                        mqtt.sendTripEventMessage(
                            event = "TRIP_STATUS_UPDATE",
                            tripData = tripData
                        ).whenComplete { result, throwable ->
                            if (throwable != null) {
                                Log.e(TAG, "Failed to send trip status update via MQTT", throwable)
                            } else {
                                Log.d(TAG, "Trip status update sent via MQTT: $status")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "MQTT not connected, cannot send trip status update")
                }
            } ?: run {
                Log.w(TAG, "MQTT service not available, cannot send trip status update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending trip status update via MQTT: ${e.message}", e)
        }
    }

    /**
     * Send waypoint reached notification via MQTT
     */
    private fun sendWaypointReachedNotification(waypoint: TripWaypoint) {
        try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    val location = MqttService.Location(
                        latitude = waypoint.location.latitude,
                        longitude = waypoint.location.longitude
                    )

                    mqtt.notifyWaypointReached(
                        tripId = tripId.toString(),
                        waypointId = waypoint.id,
                        location = location
                    ).whenComplete { result, throwable ->
                        if (throwable != null) {
                            Log.e(TAG, "Failed to send waypoint reached notification via MQTT", throwable)
                        } else {
                            Log.d(TAG, "Waypoint reached notification sent via MQTT: ${waypoint.location.google_place_name}")
                        }
                    }
                } else {
                    Log.w(TAG, "MQTT not connected, cannot send waypoint reached notification")
                }
            } ?: run {
                Log.w(TAG, "MQTT service not available, cannot send waypoint reached notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending waypoint reached notification via MQTT: ${e.message}", e)
        }
    }

    /**
     * Send waypoint missed notification via MQTT
     */
    private fun sendWaypointMissedNotification(waypoint: TripWaypoint) {
        try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    val location = MqttService.Location(
                        latitude = waypoint.location.latitude,
                        longitude = waypoint.location.longitude
                    )

                    // For now, we'll send this as a trip progress update with missed waypoint info
                    // You could create a specific waypoint missed event if needed
                    currentTrip?.let { trip ->
                        val tripData = mqtt.convertTripResponseToTripData(
                            tripResponse = trip,
                            currentSpeed = currentSpeed,
                            currentLocation = MqttService.Location(
                                latitude = currentLocation?.coordinates?.latitude ?: 0.0,
                                longitude = currentLocation?.coordinates?.longitude ?: 0.0
                            ),
                            speedAccuracy = speedAccuracy
                        )

                        mqtt.sendTripEventMessage(
                            event = "WAYPOINT_MISSED",
                            tripData = tripData
                        ).whenComplete { result, throwable ->
                            if (throwable != null) {
                                Log.e(TAG, "❌ MQTT: Failed to send waypoint missed notification", throwable)
                            } else {
                                Log.d(TAG, "⚠️ MQTT: Waypoint missed notification sent for ${waypoint.location.google_place_name}")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "MQTT not connected, cannot send waypoint missed notification")
                }
            } ?: run {
                Log.w(TAG, "MQTT service not available, cannot send waypoint missed notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending waypoint missed notification via MQTT: ${e.message}", e)
        }
    }

    /**
     * Send trip start notification via MQTT using TripEventMessage
     */
    fun sendTripStartNotification() {
        try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    // Reload trip data to get the latest status from database
                    coroutineScope.launch {
                        try {
                            val latestTrip = databaseManager.getTripById(tripId)
                            if (latestTrip != null) {
                                currentTrip = latestTrip // Update local trip data

                                val location = MqttService.Location(
                                    latitude = currentLocation?.coordinates?.latitude ?: 0.0,
                                    longitude = currentLocation?.coordinates?.longitude ?: 0.0
                                )

                                val tripData = mqtt.convertTripResponseToTripData(
                                    tripResponse = latestTrip,
                                    currentSpeed = currentSpeed,
                                    currentLocation = location,
                                    speedAccuracy = speedAccuracy
                                )

                                Log.d(TAG, "=== SENDING TRIP START NOTIFICATION ===")
                                Log.d(TAG, "Trip ID: ${latestTrip.id}")
                                Log.d(TAG, "Trip Status: ${latestTrip.status}")
                                Log.d(TAG, "Event: TRIP_STARTED")
                                Log.d(TAG, "================================")

                                mqtt.sendTripEventMessage(
                                    event = "TRIP_STARTED",
                                    tripData = tripData
                                ).whenComplete { result, throwable ->
                                    if (throwable != null) {
                                        Log.e(TAG, "❌ MQTT: Failed to send trip start notification", throwable)
                                    } else {
                                        Log.d(TAG, "🚀 MQTT: Trip start notification sent successfully with status: ${latestTrip.status}")
                                    }
                                }
                            } else {
                                Log.e(TAG, "Failed to reload trip data for MQTT notification")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reloading trip data for MQTT notification: ${e.message}", e)
                        }
                    }
                } else {
                    Log.w(TAG, "MQTT not connected, cannot send trip start notification")
                }
            } ?: run {
                Log.w(TAG, "MQTT service not available, cannot send trip start notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending trip start notification via MQTT: ${e.message}", e)
        }
    }

    /**
     * Send periodic trip progress updates via MQTT using TripEventMessage
     * Recalculates all waypoint data before publishing to ensure accuracy
     */
    fun sendPeriodicProgressUpdate() {
        // DISABLED: Let RouteProgressTracker handle MQTT updates with correct calculations
        Log.d(TAG, "Skipping TripProgressTracker MQTT update - RouteProgressTracker handles this with correct section-based logic")
        return
        
        /* ORIGINAL CODE DISABLED
        try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    currentTrip?.let { trip ->
                        val location = MqttService.Location(
                            latitude = currentLocation?.coordinates?.latitude ?: 0.0,
                            longitude = currentLocation?.coordinates?.longitude ?: 0.0
                        )

                        Log.d(TAG, "=== RECALCULATING ALL WAYPOINT DATA BEFORE PUBLISH ===")
                        Log.d(TAG, "Trip is_reversed: ${trip.is_reversed}")
                        Log.d(TAG, "Current location: ${location.latitude}, ${location.longitude}")
                        Log.d(TAG, "Current speed: ${currentSpeed?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
                        
                        // Recalculate all waypoint data before publishing
                        recalculateAllWaypointData()
                        
                        // Calculate remaining time and distance to destination
                        val remainingTime = calculateRemainingTimeToDestination()
                        val remainingDistance = calculateRemainingDistanceToDestination()

                        Log.d(TAG, "=== FINAL PUBLISH DATA ===")
                        Log.d(TAG, "Remaining time to destination: ${remainingTime?.let { formatDuration(it) } ?: "null"}")
                        Log.d(TAG, "Remaining distance to destination: ${remainingDistance?.let { String.format("%.1f", it) } ?: "null"}m")
                        Log.d(TAG, "Waypoint progress map size: ${waypointProgressMap.size}")
                        Log.d(TAG, "Current trip waypoints: ${trip.waypoints.size}")
                        
                        // Log all waypoint progress entries for debugging
                        waypointProgressMap.forEach { (waypointId, progress) ->
                            Log.d(TAG, "Waypoint $waypointId (Order: ${progress.order}): ${progress.locationName} - ${String.format("%.1f", progress.remainingDistanceInMeters)}m, ${formatDuration(progress.remainingTimeInSeconds)}")
                        }
                        Log.d(TAG, "=============================================")

                        // Get fresh waypoint data for the next waypoint
                        val nextWaypointData = getNextWaypointWithFreshData()
                        
                        val tripData = mqtt.convertTripResponseToTripData(
                            tripResponse = trip,
                            currentSpeed = currentSpeed,
                            currentLocation = location,
                            speedAccuracy = speedAccuracy,
                            remainingTimeToDestination = remainingTime,
                            remainingDistanceToDestination = remainingDistance,
                            nextWaypointData = nextWaypointData
                        )

                        mqtt.sendTripEventMessage(
                            event = "TRIP_PROGRESS_UPDATE",
                            tripData = tripData
                        ).whenComplete { result, throwable ->
                            if (throwable != null) {
                                Log.e(TAG, "❌ MQTT: Failed to send periodic progress update", throwable)
                            } else {
                                Log.d(TAG, "📡 MQTT: Periodic progress update sent successfully")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "MQTT not connected, cannot send periodic progress update")
                }
            } ?: run {
                Log.w(TAG, "MQTT service not available, cannot send periodic progress update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending periodic progress update via MQTT: ${e.message}", e)
        }
        */
    }

    /**
     * Calculate remaining time to destination in seconds
     * Ensures destination always has the longest remaining time
     */
    private fun calculateRemainingTimeToDestination(): Long? {
        return try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== CALCULATING REMAINING TIME TO DESTINATION ===")
                Log.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                Log.d(TAG, "Waypoint progress map has ${waypointProgressMap.size} entries")
                
                // Log current waypoint remaining_time values for debugging
                trip.waypoints.forEach { waypoint ->
                    Log.d(TAG, "Waypoint ${waypoint.id} (${waypoint.location.google_place_name}): remaining_time=${waypoint.remaining_time?.let { formatDuration(it) } ?: "null"}")
                }
                
                val result = if (trip.waypoints.isEmpty()) {
                    // Single destination route - get remaining time from destination progress
                    Log.d(TAG, "Single destination route - looking for destination progress")
                    val destinationProgress = waypointProgressMap[-1] // Special ID for destination
                    Log.d(TAG, "Found destination progress: ${destinationProgress?.remainingTimeInSeconds?.let { formatDuration(it) } ?: "null"}")
                    destinationProgress?.remainingTimeInSeconds
                } else {
                    // Multi-waypoint route - calculate destination time as the maximum waypoint time + buffer
                    Log.d(TAG, "Multi-waypoint route - calculating destination time as max waypoint time + buffer")
                    
                    // Get the maximum remaining time from all waypoints
                    val maxWaypointTime = trip.waypoints
                        .filter { !it.is_passed }
                        .mapNotNull { it.remaining_time }
                        .maxOrNull() ?: 0L
                    
                    // Add buffer to ensure destination has the longest time
                    val destinationTime = maxWaypointTime + 60L // Add 1 minute buffer
                    
                    Log.d(TAG, "Max waypoint time: ${formatDuration(maxWaypointTime)}")
                    Log.d(TAG, "Destination time (with buffer): ${formatDuration(destinationTime)}")
                    
                    destinationTime
                }
                Log.d(TAG, "Final remaining time result: ${result?.let { formatDuration(it) } ?: "null"}")
                Log.d(TAG, "=================================================")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating remaining time to destination: ${e.message}", e)
            null
        }
    }

    /**
     * Get fresh waypoint data for the next waypoint using current route progress
     */
    private fun getNextWaypointWithFreshData(): Pair<Long?, Double?>? {
        return try {
            currentTrip?.let { trip ->
                if (trip.waypoints.isNotEmpty()) {
                    // Use fresh route progress data if available
                    lastRouteProgressData?.let { (freshDistance, freshTime) ->
                        Log.d(TAG, "Using fresh waypoint data: ${String.format("%.1f", freshDistance)}m, ${formatDuration(freshTime)}")
                        Pair(freshTime, freshDistance)
                    } ?: run {
                        // Fallback to cached waypoint progress
                        val nextWaypointProgress = waypointProgressMap.values
                            .filter { it.isNext }
                            .minByOrNull { it.order }
                        nextWaypointProgress?.let { progress ->
                            Log.d(TAG, "Using cached waypoint data: ${String.format("%.1f", progress.remainingDistanceInMeters)}m, ${formatDuration(progress.remainingTimeInSeconds)}")
                            Pair(progress.remainingTimeInSeconds, progress.remainingDistanceInMeters)
                        }
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting fresh waypoint data: ${e.message}", e)
            null
        }
    }

    /**
     * Calculate remaining distance to destination in meters
     * Ensures destination always has the longest remaining distance
     */
    private fun calculateRemainingDistanceToDestination(): Double? {
        return try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== CALCULATING REMAINING DISTANCE TO DESTINATION ===")
                Log.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                Log.d(TAG, "Waypoint progress map has ${waypointProgressMap.size} entries")
                
                // Log current waypoint remaining_distance values for debugging
                trip.waypoints.forEach { waypoint ->
                    Log.d(TAG, "Waypoint ${waypoint.id} (${waypoint.location.google_place_name}): remaining_distance=${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
                }
                
                val result = if (trip.waypoints.isEmpty()) {
                    // Single destination route - get remaining distance from destination progress
                    Log.d(TAG, "Single destination route - looking for destination progress")
                    val destinationProgress = waypointProgressMap[-1] // Special ID for destination
                    Log.d(TAG, "Found destination progress: ${destinationProgress?.remainingDistanceInMeters?.let { String.format("%.1f", it) } ?: "null"}m")
                    destinationProgress?.remainingDistanceInMeters
                } else {
                    // Multi-waypoint route - calculate destination distance as the maximum waypoint distance + buffer
                    Log.d(TAG, "Multi-waypoint route - calculating destination distance as max waypoint distance + buffer")
                    
                    // Get the maximum remaining distance from all waypoints
                    val maxWaypointDistance = trip.waypoints
                        .filter { !it.is_passed }
                        .mapNotNull { it.remaining_distance }
                        .maxOrNull() ?: 0.0
                    
                    // Add buffer to ensure destination has the longest distance
                    val destinationDistance = maxWaypointDistance + 500.0 // Add 500 meters buffer
                    
                    Log.d(TAG, "Max waypoint distance: ${String.format("%.1f", maxWaypointDistance)}m")
                    Log.d(TAG, "Destination distance (with buffer): ${String.format("%.1f", destinationDistance)}m")
                    
                    destinationDistance
                }
                Log.d(TAG, "Final remaining distance result: ${result?.let { String.format("%.1f", it) } ?: "null"}m")
                Log.d(TAG, "=====================================================")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating remaining distance to destination: ${e.message}", e)
            null
        }
    }

    /**
     * Check all waypoints for passenger notifications (3 minutes away or just passed)
     * This is called every route progress update to ensure we don't miss any waypoints
     */
    private fun checkAllWaypointsForPassengerNotifications() {
        try {
            currentTrip?.let { trip ->
                val currentTime = System.currentTimeMillis()
                val unpassedWaypoints = trip.waypoints.filter { !it.is_passed }.sortedBy { it.order }
                
                Log.d(TAG, "=== CHECKING ALL WAYPOINTS FOR PASSENGER NOTIFICATIONS ===")
                Log.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                
                unpassedWaypoints.forEach { waypoint ->
                    val waypointId = waypoint.id
                    val lastCheckTime = lastPassengerCheckTime[waypointId] ?: 0L
                    
                    // Check every 30 seconds to avoid spam
                    if (currentTime - lastCheckTime > 30000) {
                        lastPassengerCheckTime[waypointId] = currentTime
                        
                        // Check if this waypoint has passengers and is within 3 minutes
                        val remainingTime = waypoint.remaining_time ?: Long.MAX_VALUE
                        val remainingDistance = waypoint.remaining_distance ?: Double.MAX_VALUE
                        
                        Log.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Log.d(TAG, "  - Remaining time: ${formatDuration(remainingTime)}")
                        Log.d(TAG, "  - Remaining distance: ${String.format("%.1f", remainingDistance)}m")
                        
                        // Show notification if within 3 minutes (180 seconds) and not already shown
                        if (remainingTime <= 180 && !approachingNotificationsShown.contains(waypointId)) {
                            Log.d(TAG, "  - Within 3 minutes, checking for passengers...")
                            approachingNotificationsShown.add(waypointId)
                            showPassengerApproachingNotification(waypoint)
                        } else if (remainingTime <= 180) {
                            Log.d(TAG, "  - Within 3 minutes but notification already shown")
                        } else {
                            Log.d(TAG, "  - More than 3 minutes away")
                        }
                    }
                }
                
                Log.d(TAG, "=========================================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking waypoints for passenger notifications: ${e.message}", e)
        }
    }

    /**
     * Show passenger notification when approaching a waypoint (3 minutes away)
     */
    private fun showPassengerApproachingNotification(waypoint: TripWaypoint) {
        coroutineScope.launch {
            try {
                Log.d(TAG, "=== PASSENGER APPROACHING NOTIFICATION ===")
                Log.d(TAG, "Counting passengers for waypoint: ${waypoint.location.google_place_name}")
                
                val locationName = getWaypointLocationName(waypoint)
                val passengerCount = databaseManager.countPaidPassengersForWaypoint(tripId, locationName)
                
                Log.d(TAG, "Found $passengerCount paid passengers for '$locationName'")
                
                if (passengerCount > 0) {
                    passengerNotificationDialog.showApproachingNotification(
                        passengerCount = passengerCount,
                        waypointName = waypoint.location.google_place_name
                    )
                    Log.d(TAG, "Showed approaching notification for $passengerCount passengers")
                } else {
                    Log.d(TAG, "No passengers to board at this waypoint")
                }
                
                Log.d(TAG, "==========================================")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing passenger approaching notification: ${e.message}", e)
            }
        }
    }

    /**
     * Check for waypoints that were just passed and show passenger notifications
     */
    private fun checkPassedWaypointsForPassengerNotifications() {
        try {
            currentTrip?.let { trip ->
                val currentTime = System.currentTimeMillis()
                val passedWaypoints = trip.waypoints.filter { it.is_passed }.sortedBy { it.order }
                
                Log.d(TAG, "=== CHECKING PASSED WAYPOINTS FOR PASSENGER NOTIFICATIONS ===")
                Log.d(TAG, "Found ${passedWaypoints.size} passed waypoints")
                
                passedWaypoints.forEach { waypoint ->
                    val waypointId = waypoint.id
                    val lastCheckTime = lastPassengerCheckTime[waypointId] ?: 0L
                    
                    // Check every 30 seconds to avoid spam
                    if (currentTime - lastCheckTime > 30000) {
                        lastPassengerCheckTime[waypointId] = currentTime
                        
                        // Check if this waypoint has passengers and was just passed (within last 2 minutes)
                        val timeSincePassed = currentTime - (waypointPassedTime[waypointId] ?: 0L)
                        
                        Log.d(TAG, "Passed waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Log.d(TAG, "  - Time since passed: ${timeSincePassed / 1000} seconds")
                        
                        // Show notification if passed within last 2 minutes and not already shown
                        if (timeSincePassed <= 120000 && !reachedNotificationsShown.contains(waypointId)) {
                            Log.d(TAG, "  - Just passed, checking for passengers...")
                            reachedNotificationsShown.add(waypointId)
                            showPassengerReachedNotification(waypoint)
                        } else if (timeSincePassed <= 120000) {
                            Log.d(TAG, "  - Just passed but notification already shown")
                        } else {
                            Log.d(TAG, "  - Passed more than 2 minutes ago")
                        }
                    }
                }
                
                Log.d(TAG, "=============================================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking passed waypoints for passenger notifications: ${e.message}", e)
        }
    }

    /**
     * Show passenger notification when waypoint is reached/passed
     */
    private fun showPassengerReachedNotification(waypoint: TripWaypoint) {
        coroutineScope.launch {
            try {
                Log.d(TAG, "=== PASSENGER REACHED NOTIFICATION ===")
                Log.d(TAG, "Counting passengers for waypoint: ${waypoint.location.google_place_name}")
                
                val locationName = getWaypointLocationName(waypoint)
                val passengerCount = databaseManager.countPaidPassengersForWaypoint(tripId, locationName)
                
                Log.d(TAG, "Found $passengerCount paid passengers for '$locationName'")
                
                if (passengerCount > 0) {
                    passengerNotificationDialog.showWaypointReachedNotification(
                        passengerCount = passengerCount,
                        waypointName = waypoint.location.google_place_name
                    )
                    Log.d(TAG, "Showed reached notification for $passengerCount passengers")
                } else {
                    Log.d(TAG, "No passengers to board at this waypoint")
                }
                
                Log.d(TAG, "======================================")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing passenger reached notification: ${e.message}", e)
            }
        }
    }

    /**
     * Check final destination for passenger notifications
     */
    private fun checkFinalDestinationForPassengerNotifications() {
        try {
            currentTrip?.let { trip ->
                val currentTime = System.currentTimeMillis()
                val destination = trip.route.destination
                val destinationId = -1 // Special ID for final destination
                val lastCheckTime = lastPassengerCheckTime[destinationId] ?: 0L
                
                // Check every 30 seconds to avoid spam
                if (currentTime - lastCheckTime > 30000) {
                    lastPassengerCheckTime[destinationId] = currentTime
                    
                    // Get remaining time to destination
                    val remainingTimeToDestination = calculateRemainingTimeToDestination() ?: Long.MAX_VALUE
                    
                    Log.d(TAG, "=== CHECKING FINAL DESTINATION FOR PASSENGER NOTIFICATIONS ===")
                    Log.d(TAG, "Destination: ${destination.google_place_name}")
                    Log.d(TAG, "Remaining time: ${formatDuration(remainingTimeToDestination)}")
                    
                    // Show notification if within 3 minutes and not already shown
                    if (remainingTimeToDestination <= 180 && !approachingNotificationsShown.contains(destinationId)) {
                        Log.d(TAG, "Within 3 minutes of destination, checking for passengers...")
                        approachingNotificationsShown.add(destinationId)
                        
                        coroutineScope.launch {
                            try {
                                val locationName = destination.custom_name?.takeIf { it.isNotBlank() } 
                                    ?: destination.google_place_name
                                val passengerCount = databaseManager.countPaidPassengersForWaypoint(tripId, locationName)
                                
                                Log.d(TAG, "Found $passengerCount paid passengers for destination '$locationName'")
                                
                                if (passengerCount > 0) {
                                    passengerNotificationDialog.showApproachingNotification(
                                        passengerCount = passengerCount,
                                        waypointName = destination.google_place_name
                                    )
                                    Log.d(TAG, "Showed destination approaching notification for $passengerCount passengers")
                                } else {
                                    Log.d(TAG, "No passengers to board at destination")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error checking destination passengers: ${e.message}", e)
                            }
                        }
                    } else if (remainingTimeToDestination <= 180) {
                        Log.d(TAG, "Within 3 minutes of destination but notification already shown")
                    } else {
                        Log.d(TAG, "More than 3 minutes from destination")
                    }
                    
                    Log.d(TAG, "=============================================================")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking final destination for passenger notifications: ${e.message}", e)
        }
    }

    /**
     * Get location name for passenger counting - matches both custom_name and google_place_name
     */
    private fun getWaypointLocationName(waypoint: TripWaypoint): String {
        // Try custom name first, then fall back to google place name
        return waypoint.location.custom_name?.takeIf { it.isNotBlank() } 
            ?: waypoint.location.google_place_name
    }

    /**
     * Recalculate all waypoint data before publishing to ensure accuracy
     * Since reversal is handled in backend, we use a simple progressive calculation
     */
    private fun recalculateAllWaypointData() {
        try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== RECALCULATING ALL WAYPOINT DATA ===")
                Log.d(TAG, "Trip is_reversed: ${trip.is_reversed} (handled by backend)")
                
                // Get current route progress data (this is the distance/time to the NEXT waypoint)
                val (currentRemainingDistance, currentRemainingTime) = lastRouteProgressData ?: Pair(0.0, 0L)
                
                // Get all unpassed waypoints sorted by order
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }
                
                Log.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                Log.d(TAG, "Current remaining distance to next waypoint: ${String.format("%.1f", currentRemainingDistance)}m")
                Log.d(TAG, "Current remaining time to next waypoint: ${formatDuration(currentRemainingTime)}")
                
                if (unpassedWaypoints.isNotEmpty()) {
                    // Calculate proper remaining values for each waypoint
                    // Each waypoint should have progressively more remaining time/distance
                    unpassedWaypoints.forEachIndexed { index, waypoint ->
                        val waypointOrder = waypoint.order
                        val totalUnpassedWaypoints = unpassedWaypoints.size
                        
                        // Simple progressive calculation: each waypoint gets more time/distance than the previous
                        // The first waypoint gets the current remaining time/distance
                        // Each subsequent waypoint gets additional time/distance
                        val additionalTimePerWaypoint = 30L // 30 seconds per waypoint
                        val additionalDistancePerWaypoint = 200.0 // 200 meters per waypoint
                        
                        val calculatedTime = currentRemainingTime + (additionalTimePerWaypoint * (waypointOrder - 1))
                        val calculatedDistance = currentRemainingDistance + (additionalDistancePerWaypoint * (waypointOrder - 1))
                        
                        Log.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Log.d(TAG, "  - Base time: ${formatDuration(currentRemainingTime)}, Base distance: ${String.format("%.1f", currentRemainingDistance)}m")
                        Log.d(TAG, "  - Additional time: ${formatDuration(additionalTimePerWaypoint * (waypointOrder - 1))}, Additional distance: ${String.format("%.1f", additionalDistancePerWaypoint * (waypointOrder - 1))}m")
                        Log.d(TAG, "  - Final time: ${formatDuration(calculatedTime)}, Final distance: ${String.format("%.1f", calculatedDistance)}m")
                        
                        // Update waypoint progress map
                        val waypointProgress = WaypointProgress(
                            waypointId = waypoint.id,
                            tripId = tripId,
                            order = waypoint.order,
                            locationName = waypoint.location.google_place_name,
                            remainingDistanceInMeters = calculatedDistance,
                            remainingTimeInSeconds = calculatedTime,
                            trafficDelayInSeconds = 0L,
                            isReached = false,
                            isNext = waypointOrder == unpassedWaypoints.first().order,
                            timestamp = System.currentTimeMillis(),
                            currentLatitude = currentLocation?.coordinates?.latitude,
                            currentLongitude = currentLocation?.coordinates?.longitude,
                            currentSpeedInMetersPerSecond = currentSpeed,
                            speedAccuracyInMetersPerSecond = speedAccuracy
                        )
                        
                        waypointProgressMap[waypoint.id] = waypointProgress
                        
                        // Update in-memory trip data
                        val updatedWaypoints = trip.waypoints.map { wp ->
                            if (wp.id == waypoint.id) {
                                wp.copy(
                                    remaining_time = calculatedTime,
                                    remaining_distance = calculatedDistance
                                )
                            } else wp
                        }
                        currentTrip = trip.copy(waypoints = updatedWaypoints)
                        
                        // Persist to database
                        coroutineScope.launch {
                            try {
                                databaseManager.updateWaypointRemaining(
                                    tripId = tripId,
                                    waypointId = waypoint.id,
                                    remainingTimeSeconds = calculatedTime,
                                    remainingDistanceMeters = calculatedDistance
                                )
                                Log.d(TAG, "Updated waypoint ${waypoint.id} (Order: ${waypoint.order}) in database")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update waypoint ${waypoint.id} in database: ${e.message}", e)
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "No unpassed waypoints to recalculate")
                }
                
                Log.d(TAG, "=====================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recalculating all waypoint data: ${e.message}", e)
        }
    }
}


