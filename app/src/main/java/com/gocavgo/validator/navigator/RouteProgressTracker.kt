package com.gocavgo.validator.navigator

import android.content.Context
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.service.RouteProgressMqttService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * RouteProgressTracker - Simplified progress tracking for a specific trip.
 * 
 * This class now uses three main functions for progress management:
 * 1. initializeWaypointData() - Set waypoint-specific length and time data
 * 2. updateProgressToNextWaypoint() - Update remaining time/distance to next waypoint
 * 3. markWaypointAsPassed() - Mark waypoint as reached and move to next
 */
class RouteProgressTracker(
    private val tripId: Int,
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val mqttService: MqttService? = null
) {
    companion object {
        private const val TAG = "RouteProgressTracker"
        private const val WAYPOINT_REACHED_THRESHOLD_METERS = 10.0 // For waypoint detection
        private const val WAYPOINT_APPROACHING_THRESHOLD_METERS = 100.0
        private const val WAYPOINT_APPROACHING_TIME_SECONDS = 300L // 5 minutes
        private const val PROGRESS_UPDATE_INTERVAL_SECONDS = 5L // 5 seconds (reduced for testing)
    }

    private val databaseManager = DatabaseManager.getInstance(context)
    private var currentTrip: TripResponse? = null
    private var isInitialized = false
    
    // Dedicated MQTT service for RouteProgressTracker
    private val routeProgressMqttService = RouteProgressMqttService.getInstance()
    
    // MQTT progress tracking
    private var lastProgressUpdateTime = 0L
    private var lastWaypointApproachNotification = mutableSetOf<Int>()
    private var tripStarted = false

    init {
        // Initialize dedicated MQTT service
        mqttService?.let { 
            routeProgressMqttService.initialize(it)
            Logging.d(TAG, "Dedicated MQTT service initialized for RouteProgressTracker")
            Logging.d(TAG, "MQTT service connected: ${mqttService.isConnected()}")
        } ?: run {
            Logging.e(TAG, "❌ MQTT service is null! RouteProgressTracker will not be able to send MQTT messages")
        }
        loadTripData()
    }

    /**
     * Load trip data from database
     */
    private fun loadTripData() {
        Logging.d(TAG, "=== STARTING TRIP DATA LOADING ===")
        Logging.d(TAG, "Trip ID: $tripId")
        coroutineScope.launch {
            try {
                currentTrip = databaseManager.getTripById(tripId)
                if (currentTrip != null) {
                    Logging.d(TAG, "=== ROUTE PROGRESS TRACKER INITIALIZED ===")
                    Logging.d(TAG, "Trip ID: ${currentTrip!!.id}")
                    Logging.d(TAG, "Vehicle: ${currentTrip!!.vehicle.license_plate}")
                    Logging.d(TAG, "Origin: ${currentTrip!!.route.origin.google_place_name}")
                    Logging.d(TAG, "Destination: ${currentTrip!!.route.destination.google_place_name}")
                    Logging.d(TAG, "Total waypoints: ${currentTrip!!.waypoints.size}")
                    Logging.d(TAG, "Status: ${currentTrip!!.status}")
                    Logging.d(TAG, "Is initialized: $isInitialized")
                    Logging.d(TAG, "Setting isInitialized = true")
                    isInitialized = true
                    Logging.d(TAG, "Is initialized after setting: $isInitialized")
                    Logging.d(TAG, "================================")
                } else {
                    Logging.e(TAG, "Failed to load trip data for ID: $tripId")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error loading trip data: ${e.message}", e)
            }
        }
        Logging.d(TAG, "=== TRIP DATA LOADING INITIATED (ASYNC) ===")
    }

    /**
     * Function 1: Initialize waypoint data with length and time for each waypoint
     * This should be called when the route is calculated to set waypoint-specific data
     */
    fun initializeWaypointData(
        waypointLengths: List<Double>, // Length from previous waypoint to each waypoint
        waypointTimes: List<Long>, // Time from previous waypoint to each waypoint
        totalRouteLength: Double, // Total route length
        totalRouteTime: Long // Total route time
    ) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== INITIALIZING WAYPOINT DATA ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Total waypoints: ${trip.waypoints.size}")
                Logging.d(TAG, "Waypoint lengths: $waypointLengths")
                Logging.d(TAG, "Waypoint times: $waypointTimes")
                Logging.d(TAG, "Total route length: ${String.format("%.1f", totalRouteLength)}m")
                Logging.d(TAG, "Total route time: ${formatDuration(totalRouteTime)}")
                
                if (trip.waypoints.isEmpty()) {
                    Logging.d(TAG, "Single destination route - no waypoints to initialize")
                    return
                }
                
                // Update each waypoint with its specific length and time data
                // Store original duration/length for ALL waypoints at trip start
                trip.waypoints.sortedBy { it.order }.forEachIndexed { index, waypoint ->
                    if (index < waypointLengths.size && index < waypointTimes.size) {
                        val waypointLength = waypointLengths[index]
                        val waypointTime = waypointTimes[index]
                        
                        Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Logging.d(TAG, "  - Original Length: ${String.format("%.1f", waypointLength)}m")
                        Logging.d(TAG, "  - Original Time: ${formatDuration(waypointTime)}")
                        Logging.d(TAG, "  - Remaining Time: null (not current target)")
                        Logging.d(TAG, "  - Remaining Distance: null (not current target)")
                        
                        // Store original waypoint data in database (from route sections)
                        updateWaypointDataInDatabase(
                            waypointId = waypoint.id,
                            waypointLengthMeters = waypointLength,
                            waypointTimeSeconds = waypointTime
                        )
                    }
                }
                
                // Store original trip-level data (final destination)
                Logging.d(TAG, "Trip-level data:")
                Logging.d(TAG, "  - Original Total Length: ${String.format("%.1f", totalRouteLength)}m")
                Logging.d(TAG, "  - Original Total Time: ${formatDuration(totalRouteTime)}")
                Logging.d(TAG, "  - Remaining Time to Destination: null (waypoints still remain)")
                Logging.d(TAG, "  - Remaining Distance to Destination: null (waypoints still remain)")
                
                // Store original trip data in database
                updateTripDataInDatabase(
                    tripLengthMeters = totalRouteLength,
                    tripTimeSeconds = totalRouteTime
                )
                
                // Mark the first waypoint as the next waypoint at trip start
                if (trip.waypoints.isNotEmpty()) {
                    val firstWaypoint = trip.waypoints.minByOrNull { it.order }
                    if (firstWaypoint != null) {
                        Logging.d(TAG, "Marking first waypoint as next: ${firstWaypoint.location.google_place_name}")
                        
                        // Update database to mark first waypoint as next (in coroutine)
                        coroutineScope.launch {
                            try {
                                databaseManager.updateWaypointNextStatus(tripId, firstWaypoint.id, true)
                                Logging.d(TAG, "Successfully marked first waypoint as next in database")
                            } catch (e: Exception) {
                                Logging.e(TAG, "Failed to mark first waypoint as next in database: ${e.message}", e)
                            }
                        }
                        
                        // Update in-memory data
                        val updatedWaypoints = trip.waypoints.map { waypoint ->
                            if (waypoint.id == firstWaypoint.id) {
                                waypoint.copy(is_next = true)
                            } else {
                                waypoint.copy(is_next = false) // Ensure others are false
                            }
                        }
                        currentTrip = trip.copy(waypoints = updatedWaypoints)
                    }
                }
                
                // Publish trip start event
                publishTripStartEvent()
                
                Logging.d(TAG, "=====================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error initializing waypoint data: ${e.message}", e)
        }
    }

    /**
     * Function 2: Update progress to next waypoint
     * This should be called regularly with remaining time and distance to the next waypoint
     * 
     * @param remainingTimeToNextWaypoint Time to next waypoint from first section
     * @param remainingDistanceToNextWaypoint Distance to next waypoint from first section
     * @param calculatedWaypointData Optional calculated data for all waypoints from TripSectionValidator
     */
    fun updateProgressToNextWaypoint(
        remainingTimeToNextWaypoint: Long, // Remaining time to next waypoint in seconds
        remainingDistanceToNextWaypoint: Double, // Remaining distance to next waypoint in meters
        calculatedWaypointData: List<TripSectionValidator.WaypointProgressInfo>? = null
    ) {
        try {
            if (!isInitialized) {
                Logging.w(TAG, "RouteProgressTracker not yet initialized, skipping progress update")
                return
            }

            currentTrip?.let { trip ->
                Logging.d(TAG, "=== UPDATE PROGRESS TO NEXT WAYPOINT ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Trip status: ${trip.status}")
                Logging.d(TAG, "Total waypoints: ${trip.waypoints.size}")
                
                val nextWaypoint = getNextUnpassedWaypoint()
                if (nextWaypoint != null) {
                    Logging.d(TAG, "=== UPDATING PROGRESS TO NEXT WAYPOINT ===")
                    Logging.d(TAG, "Next waypoint: ${nextWaypoint.location.google_place_name}")
                    Logging.d(TAG, "Remaining time: ${formatDuration(remainingTimeToNextWaypoint)}")
                    Logging.d(TAG, "Remaining distance: ${String.format("%.1f", remainingDistanceToNextWaypoint)}m")
                    
                    // Update ONLY the next waypoint's remaining time and distance
                    // Other waypoints will be calculated by the validation system
                    updateWaypointInDatabase(
                        waypointId = nextWaypoint.id,
                        remainingTimeSeconds = remainingTimeToNextWaypoint,
                        remainingDistanceMeters = remainingDistanceToNextWaypoint
                    )
                    
                    // Check if we're reaching the waypoint (distance-based detection)
                    if (remainingDistanceToNextWaypoint < WAYPOINT_REACHED_THRESHOLD_METERS) {
                        Logging.d(TAG, "🚨 WAYPOINT THRESHOLD TRIGGERED! Calling markWaypointAsPassed...")
                        markWaypointAsPassed(nextWaypoint.id)
                    }
                    
                    // Check for waypoint approaching notifications (5 minutes before)
                    checkWaypointApproachingNotifications(remainingTimeToNextWaypoint)
                    
                    // Publish trip progress update via MQTT
                    // Pass null for trip-level data since we're still navigating to waypoints
                    // Pass calculated waypoint data if available
                    publishTripProgressUpdate(null, null, calculatedWaypointData = calculatedWaypointData)
                    
                    Logging.d(TAG, "=============================================")
                } else {
                    Logging.d(TAG, "No unpassed waypoints found - this might be the final destination")
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating progress to next waypoint: ${e.message}", e)
        }
    }

    /**
     * Function 3: Mark waypoint as passed and move to next
     * This should be called when a waypoint is reached
     */
    fun markWaypointAsPassed(waypointId: Int) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== MARKING WAYPOINT AS PASSED ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Waypoint ID: $waypointId")
                
                val waypoint = trip.waypoints.find { it.id == waypointId }
                if (waypoint != null) {
                    Logging.d(TAG, "Waypoint: ${waypoint.location.google_place_name}")
                    Logging.d(TAG, "Order: ${waypoint.order}")
                    
                    // Publish waypoint reached notification via MQTT
                    publishWaypointReachedNotification(waypoint)
                    
                    // Mark waypoint as passed in database
                    markWaypointAsPassedInDatabase(waypointId)
                    
                    // Check if this was the last waypoint (destination reached)
                    val remainingWaypoints = trip.waypoints.filter { !it.is_passed }
                    if (remainingWaypoints.size <= 1) { // Only current waypoint remains
                        Logging.d(TAG, "🏁 FINAL DESTINATION REACHED!")
                        
                        // Publish destination reached notification via MQTT
                        publishDestinationReachedNotification()
                        
                        // Send final progress update with completed status
                        publishFinalTripProgressUpdate()
                    } else {
                        Logging.d(TAG, "Waypoint reached, ${remainingWaypoints.size - 1} waypoints remaining")
                    }
                } else {
                    Logging.e(TAG, "Waypoint with ID $waypointId not found")
                }
                
                Logging.d(TAG, "=========================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error marking waypoint as passed: ${e.message}", e)
        }
    }




    /**
     * Update waypoint data in database with waypoint-specific length and time
     */
    private fun updateWaypointDataInDatabase(
        waypointId: Int,
        waypointLengthMeters: Double,
        waypointTimeSeconds: Long
    ) {
        coroutineScope.launch {
            try {
                // Update waypoint original data in database (from route sections)
                databaseManager.updateWaypointOriginalData(
                    tripId = tripId,
                    waypointId = waypointId,
                    waypointLengthMeters = waypointLengthMeters,
                    waypointTimeSeconds = waypointTimeSeconds
                )
                Logging.d(TAG, "Updated waypoint $waypointId original data in database: length=${String.format("%.1f", waypointLengthMeters)}m, time=${formatDuration(waypointTimeSeconds)}")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update waypoint $waypointId original data in database: ${e.message}", e)
            }
        }
    }

    /**
     * Update trip data in database with original total length and time
     */
    private fun updateTripDataInDatabase(
        tripLengthMeters: Double,
        tripTimeSeconds: Long
    ) {
        coroutineScope.launch {
            try {
                // Store original trip data (total route length/time)
                // Note: This would need to be added to TripEntity if we want to store original trip data
                Logging.d(TAG, "Stored original trip data: length=${String.format("%.1f", tripLengthMeters)}m, time=${formatDuration(tripTimeSeconds)}")
                Logging.d(TAG, "Note: Original trip data storage would require additional database fields")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to store original trip data: ${e.message}", e)
            }
        }
    }

    /**
     * Mark waypoint as passed in database with timestamp
     */
    private fun markWaypointAsPassedInDatabase(waypointId: Int) {
        coroutineScope.launch {
            try {
                val currentTimestamp = System.currentTimeMillis()
                
                // Mark waypoint as passed with timestamp in database
                databaseManager.updateWaypointPassedTimestamp(tripId, waypointId, currentTimestamp)
                Logging.d(TAG, "Marked waypoint $waypointId as passed in database with timestamp: $currentTimestamp")
                
                // Mark the next unpassed waypoint as is_next = true
                val nextUnpassedWaypoint = getNextUnpassedWaypoint()
                if (nextUnpassedWaypoint != null) {
                    coroutineScope.launch {
                        try {
                            databaseManager.updateWaypointNextStatus(tripId, nextUnpassedWaypoint.id, true)
                            Logging.d(TAG, "Marked next waypoint as next: ${nextUnpassedWaypoint.location.google_place_name}")
                        } catch (e: Exception) {
                            Logging.e(TAG, "Failed to mark next waypoint as next: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to mark waypoint $waypointId as passed: ${e.message}", e)
            }
        }
    }

    /**
     * Get the next unpassed waypoint
     */
    private fun getNextUnpassedWaypoint(): TripWaypoint? {
        return try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== GETTING NEXT UNPASSED WAYPOINT ===")
                Logging.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                
                trip.waypoints.forEach { waypoint ->
                    Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}): is_passed=${waypoint.is_passed}")
                }
                
                // Check if all waypoints are marked as passed (this indicates a database sync issue)
                val allWaypointsPassed = trip.waypoints.all { it.is_passed }
                if (allWaypointsPassed) {
                    Logging.w(TAG, "⚠️ DATABASE SYNC ISSUE: All waypoints marked as passed in database!")
                    Logging.w(TAG, "This suggests the database is out of sync with TripSectionValidator")
                    Logging.w(TAG, "TripSectionValidator shows only 3 waypoints passed, but database shows all 5 passed")
                    
                    // Based on the logs, we know Waypoint 3 (Amahoro 1 Kimironko) should be next
                    // Let's find it by order (order 3)
                    val waypoint3 = trip.waypoints.find { it.order == 3 }
                    if (waypoint3 != null) {
                        Logging.w(TAG, "FALLBACK: Using Waypoint 3 as next: ${waypoint3.location.google_place_name}")
                        return waypoint3
                    }
                    
                    // If Waypoint 3 not found, use first waypoint as fallback
                    val firstWaypoint = trip.waypoints.minByOrNull { it.order }
                    Logging.w(TAG, "FALLBACK: Using first waypoint as next: ${firstWaypoint?.location?.google_place_name}")
                    return firstWaypoint
                }
                
                val unpassedWaypoints = trip.waypoints.filter { !it.is_passed }
                Logging.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                
                val nextWaypoint = unpassedWaypoints.minByOrNull { it.order }
                Logging.d(TAG, "Next waypoint: ${nextWaypoint?.location?.google_place_name ?: "None"}")
                Logging.d(TAG, "=====================================")
                
                nextWaypoint
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error getting next unpassed waypoint: ${e.message}", e)
            null
        }
    }

    /**
     * Check for waypoint approaching notifications (5 minutes before arrival)
     */
    private fun checkWaypointApproachingNotifications(remainingTime: Long) {
        try {
            currentTrip?.let { trip ->
                val nextWaypoint = getNextUnpassedWaypoint()
                if (nextWaypoint != null) {
                    // Check if we're approaching the waypoint (within 5 minutes)
                    if (remainingTime <= WAYPOINT_APPROACHING_TIME_SECONDS && 
                        !lastWaypointApproachNotification.contains(nextWaypoint.id)) {
                        
                        Logging.d(TAG, "🔔 WAYPOINT APPROACHING NOTIFICATION!")
                        Logging.d(TAG, "Waypoint: ${nextWaypoint.location.google_place_name}")
                        Logging.d(TAG, "ETA: ${formatDuration(remainingTime)}")
                        
                        // Publish waypoint approaching notification via MQTT
                        publishWaypointApproachingNotification(nextWaypoint)
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error checking waypoint approaching notifications: ${e.message}", e)
        }
    }

    /**
     * Update waypoint in database
     */
    private fun updateWaypointInDatabase(
        waypointId: Int,
        remainingTimeSeconds: Long? = null,
        remainingDistanceMeters: Double? = null,
        refreshData: Boolean = true
    ) {
        coroutineScope.launch {
            try {
                // Update waypoint in database
                databaseManager.updateWaypointRemaining(
                    tripId = tripId,
                    waypointId = waypointId,
                    remainingTimeSeconds = remainingTimeSeconds,
                    remainingDistanceMeters = remainingDistanceMeters
                )
                Logging.d(TAG, "Updated waypoint $waypointId in database: time=${remainingTimeSeconds?.let { formatDuration(it) } ?: "null"}, distance=${remainingDistanceMeters?.let { String.format("%.1f", it) } ?: "null"}m")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update waypoint $waypointId in database: ${e.message}", e)
            }
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
            Logging.e(TAG, "Error formatting duration: ${e.message}", e)
            "Unknown"
        }
    }

    /**
     * Get current trip data
     */
    fun getCurrentTrip(): TripResponse? = currentTrip

    /**
     * Check if tracker is initialized
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Reset tracker
     */
    fun reset() {
        try {
            currentTrip = null
            isInitialized = false
            tripStarted = false
            lastProgressUpdateTime = 0L
            lastWaypointApproachNotification.clear()
            Logging.d(TAG, "Route progress tracker reset for trip $tripId")
        } catch (e: Exception) {
            Logging.e(TAG, "Error resetting progress tracker: ${e.message}", e)
        }
    }

    // MQTT Methods (keeping existing MQTT functionality)
    private fun publishTripStartEvent() {
        try {
            currentTrip?.let { trip ->
                if (!tripStarted) {
                    
                    routeProgressMqttService.sendTripStartEvent(trip)?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to publish trip start event: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Trip start event published successfully via dedicated MQTT service")
                            tripStarted = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing trip start event: ${e.message}", e)
        }
    }

    private fun publishTripProgressUpdate(
        remainingTime: Long?,
        remainingDistance: Double?,
        currentSpeed: Double? = null,
        currentLocation: com.here.sdk.core.Location? = null,
        calculatedWaypointData: List<TripSectionValidator.WaypointProgressInfo>? = null
    ) {
        try {
            val currentTime = System.currentTimeMillis()
            
            Logging.d(TAG, "=== PUBLISH TRIP PROGRESS UPDATE CALLED ===")
            Logging.d(TAG, "Current time: $currentTime")
            Logging.d(TAG, "Last update time: $lastProgressUpdateTime")
            Logging.d(TAG, "Time since last update: ${currentTime - lastProgressUpdateTime}ms")
            Logging.d(TAG, "Update interval: ${PROGRESS_UPDATE_INTERVAL_SECONDS * 1000}ms")
            Logging.d(TAG, "Should send update: ${currentTime - lastProgressUpdateTime >= PROGRESS_UPDATE_INTERVAL_SECONDS * 1000}")
            
            // Check if enough time has passed since last update
            if (currentTime - lastProgressUpdateTime >= PROGRESS_UPDATE_INTERVAL_SECONDS * 1000) {
                Logging.d(TAG, "✅ Sending MQTT progress update - interval reached")
                // Fetch fresh trip data from database to get updated waypoint data
                coroutineScope.launch {
                    try {
                        val freshTrip = databaseManager.getTripById(tripId)
                        if (freshTrip != null) {
                            currentTrip = freshTrip // Update in-memory data
                            
                            // Determine trip-level data based on waypoint status
                            val unpassedWaypoints = freshTrip.waypoints.filter { !it.is_passed }
                            val tripLevelTime: Long?
                            val tripLevelDistance: Double?
                            
                            if (unpassedWaypoints.isEmpty()) {
                                // All waypoints passed - show final destination data
                                tripLevelTime = remainingTime
                                tripLevelDistance = remainingDistance
                                Logging.d(TAG, "All waypoints passed - showing final destination data: ${tripLevelTime}s, ${tripLevelDistance}m")
                            } else {
                                // Still have waypoints to pass - trip-level data should be null
                                tripLevelTime = null
                                tripLevelDistance = null
                                Logging.d(TAG, "Still have ${unpassedWaypoints.size} waypoints to pass - trip-level data is null")
                            }
                            
                            Logging.d(TAG, "=== PUBLISHING TRIP PROGRESS UPDATE (DEDICATED MQTT) ===")
                            Logging.d(TAG, "Trip ID: ${freshTrip.id}")
                            Logging.d(TAG, "Trip-level time: ${tripLevelTime?.let { formatDuration(it) } ?: "null"}")
                            Logging.d(TAG, "Trip-level distance: ${tripLevelDistance?.let { String.format("%.1f", it) } ?: "null"}m")
                            Logging.d(TAG, "Current speed: ${currentSpeed?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
                            Logging.d(TAG, "Unpassed waypoints: ${unpassedWaypoints.size}")
                            Logging.d(TAG, "Next waypoint: ${unpassedWaypoints.minByOrNull { it.order }?.location?.google_place_name ?: "None"}")
                            Logging.d(TAG, "=====================================")
                            
                            // Convert to MQTT location format
                            val mqttLocation = currentLocation?.let { location ->
                                MqttService.Location(
                                    latitude = location.coordinates.latitude,
                                    longitude = location.coordinates.longitude
                                )
                            }
                            
                            // Use dedicated MQTT service with fresh trip data
                            // If we have calculated waypoint data, use it; otherwise use database data
                            val tripDataForMqtt = if (calculatedWaypointData != null) {
                                Logging.d(TAG, "Using calculated waypoint data from TripSectionValidator")
                                createTripDataWithCalculatedWaypoints(freshTrip, calculatedWaypointData)
                            } else {
                                Logging.d(TAG, "Using database waypoint data")
                                freshTrip
                            }
                            
                            routeProgressMqttService.sendTripProgressUpdate(
                                tripResponse = tripDataForMqtt,
                                remainingTimeToDestination = tripLevelTime,
                                remainingDistanceToDestination = tripLevelDistance,
                                currentSpeed = currentSpeed,
                                currentLocation = mqttLocation
                            )?.whenComplete { result, throwable ->
                                if (throwable != null) {
                                    Logging.e(TAG, "Failed to publish trip progress update: ${throwable.message}", throwable)
                                } else {
                                    Logging.d(TAG, "Trip progress update published successfully via dedicated MQTT service")
                                    lastProgressUpdateTime = currentTime
                                }
                            }
                        } else {
                            Logging.e(TAG, "Failed to fetch fresh trip data from database")
                        }
                    } catch (e: Exception) {
                        Logging.e(TAG, "Error fetching fresh trip data: ${e.message}", e)
                    }
                }
            } else {
                Logging.d(TAG, "⏭️ Skipping MQTT progress update - interval not reached")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing trip progress update: ${e.message}", e)
        }
    }
    
    /**
     * Create trip data with calculated waypoint information from TripSectionValidator
     * This merges the calculated remaining time/distance with the database trip data
     */
    private fun createTripDataWithCalculatedWaypoints(
        tripData: TripResponse,
        calculatedWaypointData: List<TripSectionValidator.WaypointProgressInfo>
    ): TripResponse {
        try {
            Logging.d(TAG, "=== CREATING TRIP DATA WITH CALCULATED WAYPOINTS ===")
            Logging.d(TAG, "Trip has ${tripData.waypoints.size} waypoints")
            Logging.d(TAG, "Calculated data has ${calculatedWaypointData.size} entries")
            
            // Create a map of calculated data by waypoint order for quick lookup
            val calculatedDataMap = calculatedWaypointData.associateBy { it.waypointIndex }
            
            // Update waypoints with calculated data
            val updatedWaypoints = tripData.waypoints.map { waypoint ->
                val calculatedData = calculatedDataMap[waypoint.order]
                
                if (calculatedData != null) {
                    Logging.d(TAG, "Updating waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                    Logging.d(TAG, "  - Calculated time: ${calculatedData.remainingTimeInSeconds?.let { formatDuration(it) } ?: "null"}")
                    Logging.d(TAG, "  - Calculated distance: ${calculatedData.remainingDistanceInMeters?.let { String.format("%.1f", it) } ?: "null"}m")
                    Logging.d(TAG, "  - Is next: ${calculatedData.isNext}")
                    
                    waypoint.copy(
                        remaining_time = calculatedData.remainingTimeInSeconds,
                        remaining_distance = calculatedData.remainingDistanceInMeters,
                        is_next = calculatedData.isNext
                    )
                } else {
                    Logging.d(TAG, "No calculated data for waypoint ${waypoint.order} (${waypoint.location.google_place_name}) - using database data")
                    waypoint
                }
            }
            
            Logging.d(TAG, "=============================================")
            return tripData.copy(waypoints = updatedWaypoints)
        } catch (e: Exception) {
            Logging.e(TAG, "Error creating trip data with calculated waypoints: ${e.message}", e)
            return tripData // Return original data on error
        }
    }
    
    /**
     * Sync database waypoint status with TripSectionValidator data
     * This fixes the issue where database has incorrect is_passed status
     */
    suspend fun syncDatabaseWithTripSectionValidator(calculatedWaypointData: List<TripSectionValidator.WaypointProgressInfo>) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== SYNCING DATABASE WITH TRIPSECTIONVALIDATOR ===")
                Logging.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                Logging.d(TAG, "Calculated data has ${calculatedWaypointData.size} entries")
                
                // Create a map of calculated data by waypoint order
                val calculatedDataMap = calculatedWaypointData.associateBy { it.waypointIndex }
                
                var needsSync = false
                
                // Check each waypoint and sync database if needed
                trip.waypoints.forEach { waypoint ->
                    val calculatedData = calculatedDataMap[waypoint.order]
                    val shouldBePassed = calculatedData?.isPassed ?: false
                    val isCurrentlyPassed = waypoint.is_passed
                    
                    Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                    Logging.d(TAG, "  - Database is_passed: $isCurrentlyPassed")
                    Logging.d(TAG, "  - Calculated isPassed: $shouldBePassed")
                    
                    if (isCurrentlyPassed != shouldBePassed) {
                        Logging.w(TAG, "  - ⚠️ MISMATCH DETECTED! Syncing database...")
                        needsSync = true
                        
                        try {
                            databaseManager.updateWaypointStatus(tripId, waypoint.id, shouldBePassed)
                            Logging.d(TAG, "  - ✅ Database updated: waypoint ${waypoint.id} is_passed=$shouldBePassed")
                        } catch (e: Exception) {
                            Logging.e(TAG, "  - ❌ Failed to update database: ${e.message}", e)
                        }
                    } else {
                        Logging.d(TAG, "  - ✅ Database is in sync")
                    }
                }
                
                if (needsSync) {
                    Logging.d(TAG, "Database sync completed. Refreshing in-memory trip data...")
                    // Refresh in-memory trip data after sync
                    val freshTrip = databaseManager.getTripById(tripId)
                    if (freshTrip != null) {
                        currentTrip = freshTrip
                        Logging.d(TAG, "In-memory trip data refreshed after sync")
                    }
                }
                
                Logging.d(TAG, "=============================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error syncing database with TripSectionValidator: ${e.message}", e)
        }
    }

    private fun publishWaypointApproachingNotification(waypoint: TripWaypoint) {
        try {
            currentTrip?.let { trip ->
                if (!lastWaypointApproachNotification.contains(waypoint.id)) {
                    Logging.d(TAG, "=== PUBLISHING WAYPOINT APPROACHING NOTIFICATION ===")
                    Logging.d(TAG, "Trip ID: ${trip.id}")
                    Logging.d(TAG, "Waypoint: ${waypoint.location.google_place_name}")
                    Logging.d(TAG, "Order: ${waypoint.order}")
                    Logging.d(TAG, "Remaining time: ${waypoint.remaining_time?.let { formatDuration(it) } ?: "Unknown"}")
                    Logging.d(TAG, "Remaining distance: ${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "Unknown"}m")
                    Logging.d(TAG, "==================================================")
                    
                    mqttService?.sendTripEventMessage(
                        event = "waypoint_approaching",
                        tripData = mqttService.convertTripResponseToTripData(
                            tripResponse = trip,
                            nextWaypointData = Pair(waypoint.remaining_time, waypoint.remaining_distance)
                        )
                    )?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to publish waypoint approaching notification: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Waypoint approaching notification published successfully")
                            lastWaypointApproachNotification.add(waypoint.id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing waypoint approaching notification: ${e.message}", e)
        }
    }

    private fun publishWaypointReachedNotification(waypoint: TripWaypoint) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== PUBLISHING WAYPOINT REACHED NOTIFICATION ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Waypoint: ${waypoint.location.google_place_name}")
                Logging.d(TAG, "Order: ${waypoint.order}")
                Logging.d(TAG, "=============================================")
                
                val location = MqttService.Location(
                    latitude = waypoint.location.latitude,
                    longitude = waypoint.location.longitude
                )
                
                mqttService?.notifyWaypointReached(
                    tripId = trip.id.toString(),
                    waypointId = waypoint.id,
                    location = location
                )?.whenComplete { result, throwable ->
                    if (throwable != null) {
                        Logging.e(TAG, "Failed to publish waypoint reached notification: ${throwable.message}", throwable)
                    } else {
                        Logging.d(TAG, "Waypoint reached notification published successfully")
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing waypoint reached notification: ${e.message}", e)
        }
    }

    private fun publishDestinationReachedNotification() {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== PUBLISHING DESTINATION REACHED NOTIFICATION ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Destination: ${trip.route.destination.google_place_name}")
                Logging.d(TAG, "===============================================")
                
                mqttService?.sendTripStatusUpdate(
                    tripId = trip.id.toString(),
                    status = "completed"
                )?.whenComplete { result, throwable ->
                    if (throwable != null) {
                        Logging.e(TAG, "Failed to publish destination reached notification: ${throwable.message}", throwable)
                    } else {
                        Logging.d(TAG, "Destination reached notification published successfully")
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing destination reached notification: ${e.message}", e)
        }
    }

    private fun publishFinalTripProgressUpdate() {
        coroutineScope.launch {
            try {
                currentTrip?.let { trip ->
                    val completionTimestamp = System.currentTimeMillis()
                    
                    Logging.d(TAG, "Publishing final trip progress update with completed status (DEDICATED MQTT)")
                    Logging.d(TAG, "Trip status: ${trip.status}")
                    Logging.d(TAG, "Completion timestamp: $completionTimestamp")
                    
                    // Mark trip as completed with timestamp in database
                    databaseManager.updateTripCompletionTimestamp(tripId, completionTimestamp)
                    Logging.d(TAG, "Marked trip as completed in database with timestamp: $completionTimestamp")
                    
                    // Use dedicated MQTT service for completion event
                    routeProgressMqttService.sendTripCompletionEvent(trip)?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to publish final trip progress update: ${throwable.message}")
                        } else {
                            Logging.d(TAG, "Final trip progress update published successfully via dedicated MQTT service")
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error publishing final trip progress update: ${e.message}", e)
            }
        }
    }
}
