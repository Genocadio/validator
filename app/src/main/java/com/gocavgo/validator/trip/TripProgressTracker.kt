package com.gocavgo.validator.trip

import android.util.Log
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.WaypointProgress
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.dataclass.SavePlaceResponse
import com.gocavgo.validator.service.MqttService
import com.here.sdk.navigation.RouteProgress
import com.here.sdk.navigation.SectionProgress
import com.here.sdk.routing.Route
import com.here.sdk.core.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class TripProgressTracker(
    private val tripId: Int,
    private val databaseManager: DatabaseManager,
    private val coroutineScope: CoroutineScope,
    private val mqttService: MqttService? = null
) {
    companion object {
        private const val TAG = "TripProgressTracker"
        private const val WAYPOINT_REACHED_THRESHOLD_METERS = 10.0
        private const val WAYPOINT_APPROACHING_THRESHOLD_METERS = 100.0
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
            }
            Log.d(TAG, "================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting current route: ${e.message}", e)
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
                                trip.vehicle_id,
                                loc.coordinates.latitude,
                                loc.coordinates.longitude,
                                speedInMetersPerSecond ?: 0.0
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
                        Log.d(TAG, "Stored progress for waypoint: ${waypoint.location.google_place_name}")

                        // Persist remaining progress for resilience
                        coroutineScope.launch {
                            try {
                                databaseManager.updateWaypointRemaining(
                                    tripId = tripId,
                                    waypointId = waypoint.id,
                                    remainingTimeSeconds = waypointProgress.remainingTimeInSeconds,
                                    remainingDistanceMeters = waypointProgress.remainingDistanceInMeters
                                )
                                Log.d(TAG, "DB persist success for waypoint ${waypoint.id}: time=${waypointProgress.remainingTimeInSeconds}, dist=${String.format("%.1f", waypointProgress.remainingDistanceInMeters)}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to persist waypoint remaining progress: ${e.message}", e)
                            }
                        }
                    }

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
                    updateWaypointStatus(waypointIndex, true)
                    logWaypointReached(waypointIndex, sectionProgress)
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

                    val eta = try {
                        formatDuration(sectionProgress.remainingDuration.seconds)
                    } catch (e: Exception) {
                        "Unknown"
                    }
                    Log.d(TAG, "ETA: $eta")
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

    private fun updateWaypointStatus(waypointIndex: Int, reached: Boolean) {
        try {
            val waypoint = getWaypointByIndex(waypointIndex)
            if (waypoint != null) {
                coroutineScope.launch {
                    try {
                        // Update waypoint status in database
                        updateWaypointInDatabase(waypoint.id, reached)

                        // Send MQTT notification for waypoint reached
                        if (reached) {
                            sendWaypointReachedNotification(waypoint)
                        }

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
                    Log.d(TAG, "Trip $tripId marked as completed")
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
            isInitialized = false
            Log.d(TAG, "Trip progress tracker reset for trip $tripId")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting progress tracker: ${e.message}", e)
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
                                        Log.e(TAG, "Failed to send trip start notification via MQTT", throwable)
                                    } else {
                                        Log.d(TAG, "Trip start notification sent via MQTT with status: ${latestTrip.status}")
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
     */
    fun sendPeriodicProgressUpdate() {
        try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    currentTrip?.let { trip ->
                        val location = MqttService.Location(
                            latitude = currentLocation?.coordinates?.latitude ?: 0.0,
                            longitude = currentLocation?.coordinates?.longitude ?: 0.0
                        )

                        // Calculate remaining time and distance to destination
                        val remainingTime = calculateRemainingTimeToDestination()
                        val remainingDistance = calculateRemainingDistanceToDestination()

                        Log.d(TAG, "=== PERIODIC PROGRESS UPDATE CALCULATION ===")
                        Log.d(TAG, "Remaining time to destination: ${remainingTime?.let { formatDuration(it) } ?: "null"}")
                        Log.d(TAG, "Remaining distance to destination: ${remainingDistance?.let { String.format("%.1f", it) } ?: "null"}m")
                        Log.d(TAG, "Waypoint progress map size: ${waypointProgressMap.size}")
                        Log.d(TAG, "Current trip waypoints: ${trip.waypoints.size}")
                        
                        // Log all waypoint progress entries for debugging
                        waypointProgressMap.forEach { (waypointId, progress) ->
                            Log.d(TAG, "Waypoint $waypointId: ${progress.locationName} - ${String.format("%.1f", progress.remainingDistanceInMeters)}m, ${formatDuration(progress.remainingTimeInSeconds)}")
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
                                Log.e(TAG, "Failed to send periodic progress update via MQTT", throwable)
                            } else {
                                Log.d(TAG, "Periodic progress update sent via MQTT")
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
    }

    /**
     * Calculate remaining time to destination in seconds
     */
    private fun calculateRemainingTimeToDestination(): Long? {
        return try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== CALCULATING REMAINING TIME TO DESTINATION ===")
                Log.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                Log.d(TAG, "Waypoint progress map has ${waypointProgressMap.size} entries")
                
                val result = if (trip.waypoints.isEmpty()) {
                    // Single destination route - get remaining time from destination progress
                    Log.d(TAG, "Single destination route - looking for destination progress")
                    val destinationProgress = waypointProgressMap[-1] // Special ID for destination
                    Log.d(TAG, "Found destination progress: ${destinationProgress?.remainingTimeInSeconds?.let { formatDuration(it) } ?: "null"}")
                    destinationProgress?.remainingTimeInSeconds
                } else {
                    // Multi-waypoint route - use fresh route progress data first, then fallback to cached data
                    Log.d(TAG, "Multi-waypoint route - checking for fresh route progress data")
                    
                    // First try to use the most recent route progress data
                    lastRouteProgressData?.let { (freshDistance, freshTime) ->
                        Log.d(TAG, "Using fresh route progress data: ${String.format("%.1f", freshDistance)}m, ${formatDuration(freshTime)}")
                        return@let freshTime
                    } ?: run {
                        // Fallback to cached waypoint progress data
                        Log.d(TAG, "No fresh data available, using cached waypoint progress")
                        Log.d(TAG, "Available waypoint progress entries:")
                        waypointProgressMap.forEach { (id, progress) ->
                            Log.d(TAG, "  ID: $id, Name: ${progress.locationName}, IsNext: ${progress.isNext}, Order: ${progress.order}, Time: ${formatDuration(progress.remainingTimeInSeconds)}")
                        }
                        val nextWaypointProgress = waypointProgressMap.values
                            .filter { it.isNext }
                            .minByOrNull { it.order }
                        Log.d(TAG, "Selected next waypoint progress: ${nextWaypointProgress?.locationName} - ${nextWaypointProgress?.remainingTimeInSeconds?.let { formatDuration(it) } ?: "null"}")
                        nextWaypointProgress?.remainingTimeInSeconds
                    }
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
     */
    private fun calculateRemainingDistanceToDestination(): Double? {
        return try {
            currentTrip?.let { trip ->
                Log.d(TAG, "=== CALCULATING REMAINING DISTANCE TO DESTINATION ===")
                Log.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                Log.d(TAG, "Waypoint progress map has ${waypointProgressMap.size} entries")
                
                val result = if (trip.waypoints.isEmpty()) {
                    // Single destination route - get remaining distance from destination progress
                    Log.d(TAG, "Single destination route - looking for destination progress")
                    val destinationProgress = waypointProgressMap[-1] // Special ID for destination
                    Log.d(TAG, "Found destination progress: ${destinationProgress?.remainingDistanceInMeters?.let { String.format("%.1f", it) } ?: "null"}m")
                    destinationProgress?.remainingDistanceInMeters
                } else {
                    // Multi-waypoint route - use fresh route progress data first, then fallback to cached data
                    Log.d(TAG, "Multi-waypoint route - checking for fresh route progress data")
                    
                    // First try to use the most recent route progress data
                    lastRouteProgressData?.let { (freshDistance, freshTime) ->
                        Log.d(TAG, "Using fresh route progress data: ${String.format("%.1f", freshDistance)}m, ${formatDuration(freshTime)}")
                        return@let freshDistance
                    } ?: run {
                        // Fallback to cached waypoint progress data
                        Log.d(TAG, "No fresh data available, using cached waypoint progress")
                        Log.d(TAG, "Available waypoint progress entries:")
                        waypointProgressMap.forEach { (id, progress) ->
                            Log.d(TAG, "  ID: $id, Name: ${progress.locationName}, IsNext: ${progress.isNext}, Order: ${progress.order}, Distance: ${String.format("%.1f", progress.remainingDistanceInMeters)}m")
                        }
                        val nextWaypointProgress = waypointProgressMap.values
                            .filter { it.isNext }
                            .minByOrNull { it.order }
                        Log.d(TAG, "Selected next waypoint progress: ${nextWaypointProgress?.locationName} - ${nextWaypointProgress?.remainingDistanceInMeters?.let { String.format("%.1f", it) } ?: "null"}m")
                        nextWaypointProgress?.remainingDistanceInMeters
                    }
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
}


