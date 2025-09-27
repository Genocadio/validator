package com.gocavgo.validator.navigator

import android.content.Context
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.service.MqttService
import com.here.sdk.navigation.RouteProgress
import com.here.sdk.navigation.SectionProgress
import com.here.sdk.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RouteProgressTracker - Tracks route progress for a specific trip and updates database
 * with ETA and distance information for each waypoint.
 * 
 * This class works with App and NavigationHandler to provide real-time progress updates
 * and database persistence for trip navigation.
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
        private const val PROGRESS_UPDATE_INTERVAL_SECONDS = 20L // 20 seconds
    }

    private val databaseManager = DatabaseManager.getInstance(context)
    private var currentTrip: TripResponse? = null
    private var currentRoute: Route? = null
    private var isInitialized = false
    
    // MQTT progress tracking
    private var lastProgressUpdateTime = 0L
    private var lastWaypointApproachNotification = mutableSetOf<Int>()
    private var tripStarted = false

    init {
        loadTripData()
    }

    /**
     * Load trip data from database
     */
    private fun loadTripData() {
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
                    Logging.d(TAG, "================================")
                    isInitialized = true
                } else {
                    Logging.e(TAG, "Failed to load trip data for ID: $tripId")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error loading trip data: ${e.message}", e)
            }
        }
    }

    /**
     * Set the current route for progress tracking
     */
    fun setCurrentRoute(route: Route?) {
        try {
            if (route == null) {
                Logging.w(TAG, "Attempted to set null route")
                return
            }

            currentRoute = route
            Logging.d(TAG, "=== ROUTE SET FOR PROGRESS TRACKING ===")
            Logging.d(TAG, "Route sections: ${route.sections.size}")
            Logging.d(TAG, "Total distance: ${String.format("%.1f", route.lengthInMeters / 1000.0)}km")
            Logging.d(TAG, "Estimated duration: ${formatDuration(route.duration.seconds)}")
            
            // Initialize waypoint progress from route sections
            initializeWaypointProgressFromRoute(route)
            
            // Publish trip start event
            publishTripStartEvent()
            
            Logging.d(TAG, "================================")
        } catch (e: Exception) {
            Logging.e(TAG, "Error setting current route: ${e.message}", e)
        }
    }

    /**
     * Initialize waypoint progress from route sections
     */
    private fun initializeWaypointProgressFromRoute(route: Route) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== INITIALIZING WAYPOINT PROGRESS FROM ROUTE ===")
                Logging.d(TAG, "Route has ${route.sections.size} sections")
                Logging.d(TAG, "Trip has ${trip.waypoints.size} waypoints")
                
                if (trip.waypoints.isEmpty()) {
                    Logging.d(TAG, "Single destination route - no waypoints to initialize")
                    return
                }
                
                // Get unpassed waypoints sorted by order
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }
                
                Logging.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                
                if (unpassedWaypoints.isNotEmpty()) {
                    val totalDistance = route.lengthInMeters.toDouble()
                    val totalTime = route.duration.seconds
                    
                    Logging.d(TAG, "Total route distance: ${String.format("%.1f", totalDistance)}m")
                    Logging.d(TAG, "Total route time: ${formatDuration(totalTime)}")
                    
                    unpassedWaypoints.forEachIndexed { index, waypoint ->
                        // Calculate remaining distance and time for this waypoint
                        val waypointOrder = waypoint.order
                        val totalUnpassedWaypoints = unpassedWaypoints.size
                        
                        // Calculate proportional remaining values
                        val orderRatio = (totalUnpassedWaypoints - index).toDouble() / totalUnpassedWaypoints.toDouble()
                        val remainingTime = (totalTime * orderRatio).toLong()
                        val remainingDistance = totalDistance * orderRatio
                        
                        // Add buffer to ensure proper ordering
                        val timeBuffer = (totalTime * 0.1 * orderRatio).toLong()
                        val distanceBuffer = totalDistance * 0.1 * orderRatio
                        
                        val finalTime = remainingTime + timeBuffer
                        val finalDistance = remainingDistance + distanceBuffer
                        
                        Logging.d(TAG, "Initializing waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Logging.d(TAG, "  - Final time: ${formatDuration(finalTime)}, Final distance: ${String.format("%.1f", finalDistance)}m")
                        
                        // Update waypoint in database
                        updateWaypointInDatabase(
                            waypointId = waypoint.id,
                            remainingTimeSeconds = finalTime,
                            remainingDistanceMeters = finalDistance
                        )
                    }
                }
                
                Logging.d(TAG, "=======================================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error initializing waypoint progress from route: ${e.message}", e)
        }
    }

    /**
     * Handle destination reached event from NavigationHandler (HERE SDK - for final destinations only)
     */
    fun onDestinationReached() {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== FINAL DESTINATION REACHED (HERE SDK) ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                
                // HERE SDK destination reached listener is for final destinations only
                // Mark any remaining waypoints as passed and complete the trip
                val remainingWaypoints = trip.waypoints.filter { !it.is_passed }
                if (remainingWaypoints.isNotEmpty()) {
                    Logging.d(TAG, "Marking ${remainingWaypoints.size} remaining waypoints as passed")
                    remainingWaypoints.forEach { waypoint ->
                        markWaypointAsPassed(waypoint.id)
                    }
                }
                
                Logging.d(TAG, "🏁 FINAL DESTINATION REACHED!")
                
                // Publish destination reached notification via MQTT
                publishDestinationReachedNotification()
                
                // Send final progress update with completed status (this will update status and refresh data)
                publishFinalTripProgressUpdate()
                
                Logging.d(TAG, "==========================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling destination reached: ${e.message}", e)
        }
    }

    /**
     * Handle route progress updates from NavigationHandler
     */
    fun onRouteProgressUpdate(routeProgress: RouteProgress?) {
        try {
            if (routeProgress == null) {
                Logging.w(TAG, "Received null route progress update")
                return
            }

            if (!isInitialized) {
                Logging.w(TAG, "RouteProgressTracker not yet initialized, skipping progress update")
                return
            }

            val sectionProgressList = routeProgress.sectionProgress
            if (sectionProgressList.isNotEmpty()) {
                // Pass section data directly to the new method
                onSectionProgressUpdate(sectionProgressList)
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error in route progress update: ${e.message}", e)
        }
    }

    /**
     * Handle section progress updates directly from NavigationHandler
     */
    fun onSectionProgressUpdate(sectionProgressList: List<SectionProgress>) {
        try {
            if (!isInitialized) {
                Logging.w(TAG, "RouteProgressTracker not yet initialized, skipping section progress update")
                return
            }

            if (sectionProgressList.isEmpty()) {
                Logging.w(TAG, "Received empty section progress list")
                return
            }

            val currentSectionProgress = sectionProgressList[0]
            val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
            
            // Get remaining distance and time for current section (next waypoint)
            val currentRemainingDistance = try {
                currentSectionProgress.remainingDistanceInMeters.toDouble()
            } catch (e: Exception) {
                Logging.w(TAG, "Error getting current remaining distance: ${e.message}")
                0.0
            }

            val currentRemainingDuration = try {
                currentSectionProgress.remainingDuration.seconds
            } catch (e: Exception) {
                Logging.w(TAG, "Error getting current remaining duration: ${e.message}")
                0L
            }

            // Use simple logic like getETA() - get the last section for total route remaining time
            val totalRemainingDistance = try {
                lastSectionProgress.remainingDistanceInMeters.toDouble()
            } catch (e: Exception) {
                Logging.w(TAG, "Error getting total remaining distance: ${e.message}")
                0.0
            }

            val totalRemainingDuration = try {
                lastSectionProgress.remainingDuration.seconds
            } catch (e: Exception) {
                Logging.w(TAG, "Error getting total remaining duration: ${e.message}")
                0L
            }

            val trafficDelay = try {
                currentSectionProgress.trafficDelay.seconds
            } catch (e: Exception) {
                Logging.w(TAG, "Error getting traffic delay: ${e.message}")
                0L
            }

            Logging.d(TAG, "=== ROUTE PROGRESS UPDATE ===")
            Logging.d(TAG, "Total sections: ${sectionProgressList.size}")
            Logging.d(TAG, "Current section remaining distance: ${String.format("%.1f", currentRemainingDistance)}m")
            Logging.d(TAG, "Current section remaining time: ${formatDuration(currentRemainingDuration)}")
            Logging.d(TAG, "Total route remaining distance: ${String.format("%.1f", totalRemainingDistance)}m")
            Logging.d(TAG, "Total route remaining time: ${formatDuration(totalRemainingDuration)}")
            Logging.d(TAG, "Traffic delay: ${formatDuration(trafficDelay)}")

            // Update waypoint progress using actual section data
            updateWaypointProgressWithSections(sectionProgressList, currentRemainingDistance, currentRemainingDuration, totalRemainingDistance, totalRemainingDuration, trafficDelay)

            // Check if we're reaching a waypoint (distance-based detection for waypoints)
            Logging.d(TAG, "Checking waypoint threshold: ${String.format("%.1f", currentRemainingDistance)}m < ${WAYPOINT_REACHED_THRESHOLD_METERS}m = ${currentRemainingDistance < WAYPOINT_REACHED_THRESHOLD_METERS}")
            if (currentRemainingDistance < WAYPOINT_REACHED_THRESHOLD_METERS) {
                Logging.d(TAG, "🚨 WAYPOINT THRESHOLD TRIGGERED! Calling handleWaypointReached...")
                handleWaypointReached(currentSectionProgress)
            }

            // Check for waypoint approaching notifications (5 minutes before)
            checkWaypointApproachingNotifications(currentRemainingDuration)

            // Store trip's total remaining time/distance in database
            coroutineScope.launch {
                try {
                    currentTrip?.let { trip ->
                        databaseManager.updateTripRemaining(
                            tripId = trip.id,
                            remainingTimeToDestination = totalRemainingDuration,
                            remainingDistanceToDestination = totalRemainingDistance
                        )
                        
                        // Update in-memory trip data
                        currentTrip = trip.copy(
                            remaining_time_to_destination = totalRemainingDuration,
                            remaining_distance_to_destination = totalRemainingDistance
                        )
                        
                        Logging.d(TAG, "Updated trip remaining progress: time=${formatDuration(totalRemainingDuration)}, distance=${String.format("%.1f", totalRemainingDistance)}m")
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to update trip remaining progress: ${e.message}", e)
                }
            }

            // Publish trip progress update via MQTT using total route remaining time
            publishTripProgressUpdate(totalRemainingDuration, totalRemainingDistance)

            Logging.d(TAG, "=============================")
        } catch (e: Exception) {
            Logging.e(TAG, "Error in section progress update: ${e.message}", e)
        }
    }

    /**
     * Update waypoint progress using actual section data
     */
    private fun updateWaypointProgressWithSections(
        sectionProgressList: List<SectionProgress>,
        currentRemainingDistance: Double,
        currentRemainingDuration: Long,
        totalRemainingDistance: Double,
        totalRemainingDuration: Long,
        trafficDelay: Long
    ) {
        try {
            currentTrip?.let { trip ->
                if (trip.waypoints.isEmpty()) {
                    // Single destination route - update destination progress using total remaining time
                    updateDestinationProgress(totalRemainingDistance, totalRemainingDuration, trafficDelay)
                } else {
                    // Multi-waypoint route - update waypoint progress using actual section data
                    updateMultiWaypointProgressWithSections(sectionProgressList, currentRemainingDistance, currentRemainingDuration, totalRemainingDistance, totalRemainingDuration, trafficDelay)
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating waypoint progress with sections: ${e.message}", e)
        }
    }

    /**
     * Update waypoint progress based on current section progress (legacy method)
     */
    private fun updateWaypointProgress(
        sectionProgress: SectionProgress,
        currentRemainingDistance: Double,
        currentRemainingDuration: Long,
        totalRemainingDistance: Double,
        totalRemainingDuration: Long,
        trafficDelay: Long
    ) {
        try {
            currentTrip?.let { trip ->
                if (trip.waypoints.isEmpty()) {
                    // Single destination route - update destination progress using total remaining time
                    updateDestinationProgress(totalRemainingDistance, totalRemainingDuration, trafficDelay)
                } else {
                    // Multi-waypoint route - update waypoint progress
                    updateMultiWaypointProgress(sectionProgress, currentRemainingDistance, currentRemainingDuration, totalRemainingDistance, totalRemainingDuration, trafficDelay)
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating waypoint progress: ${e.message}", e)
        }
    }

    /**
     * Update progress for single destination route
     */
    private fun updateDestinationProgress(
        remainingDistance: Double,
        remainingDuration: Long,
        trafficDelay: Long
    ) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "🚗 SINGLE DESTINATION ROUTE")
                Logging.d(TAG, "Destination: ${trip.route.destination.google_place_name}")
                Logging.d(TAG, "Remaining distance: ${String.format("%.1f", remainingDistance)}m")
                Logging.d(TAG, "Remaining time: ${formatDuration(remainingDuration)}")
                Logging.d(TAG, "Traffic delay: ${formatDuration(trafficDelay)}")

                // For single destination, we don't have waypoints to update
                // The progress is tracked in the route progress itself
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating destination progress: ${e.message}", e)
        }
    }

    /**
     * Update progress for multi-waypoint route using actual section data
     */
    private fun updateMultiWaypointProgressWithSections(
        sectionProgressList: List<SectionProgress>,
        currentRemainingDistance: Double,
        currentRemainingDuration: Long,
        totalRemainingDistance: Double,
        totalRemainingDuration: Long,
        trafficDelay: Long
    ) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "🗺️ MULTI-WAYPOINT ROUTE WITH SECTIONS")
                Logging.d(TAG, "Total sections: ${sectionProgressList.size}")
                
                // Get unpassed waypoints
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }
                
                Logging.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                
                if (unpassedWaypoints.isNotEmpty()) {
                    // Update each waypoint using the corresponding section data
                    // Sections are cumulative: Section 1 = to waypoint 1, Section 2 = to waypoint 2, etc.
                    unpassedWaypoints.forEachIndexed { index, waypoint ->
                        val sectionIndex = index // Use index directly since sections are already cumulative
                        
                        if (sectionIndex < sectionProgressList.size) {
                            val sectionProgress = sectionProgressList[sectionIndex]
                            val sectionRemainingTime = sectionProgress.remainingDuration.seconds
                            val sectionRemainingDistance = sectionProgress.remainingDistanceInMeters.toDouble()
                            
                            Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                            Logging.d(TAG, "  - Section ${sectionIndex + 1}: ${formatDuration(sectionRemainingTime)}, ${String.format("%.1f", sectionRemainingDistance)}m")
                            
                            // Update waypoint in database with actual section data
                            updateWaypointInDatabase(
                                waypointId = waypoint.id,
                                remainingTimeSeconds = sectionRemainingTime,
                                remainingDistanceMeters = sectionRemainingDistance,
                                refreshData = false
                            )
                        } else {
                            // If we don't have enough sections, use the last section data
                            val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
                            val lastSectionRemainingTime = lastSectionProgress.remainingDuration.seconds
                            val lastSectionRemainingDistance = lastSectionProgress.remainingDistanceInMeters.toDouble()
                            
                            Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                            Logging.d(TAG, "  - Using last section data: ${formatDuration(lastSectionRemainingTime)}, ${String.format("%.1f", lastSectionRemainingDistance)}m")
                            
                            updateWaypointInDatabase(
                                waypointId = waypoint.id,
                                remainingTimeSeconds = lastSectionRemainingTime,
                                remainingDistanceMeters = lastSectionRemainingDistance,
                                refreshData = false
                            )
                        }
                    }
                } else {
                    Logging.d(TAG, "No unpassed waypoints found")
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating multi-waypoint progress with sections: ${e.message}", e)
        }
    }

    /**
     * Update progress for multi-waypoint route (legacy method)
     */
    private fun updateMultiWaypointProgress(
        sectionProgress: SectionProgress,
        currentRemainingDistance: Double,
        currentRemainingDuration: Long,
        totalRemainingDistance: Double,
        totalRemainingDuration: Long,
        trafficDelay: Long
    ) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "🗺️ MULTI-WAYPOINT ROUTE")
                
                // Get the next unpassed waypoint
                val nextWaypoint = getNextUnpassedWaypoint()
                if (nextWaypoint != null) {
                    Logging.d(TAG, "Next waypoint: ${nextWaypoint.location.google_place_name}")
                    Logging.d(TAG, "Remaining distance to next: ${String.format("%.1f", currentRemainingDistance)}m")
                    Logging.d(TAG, "ETA to next: ${formatDuration(currentRemainingDuration)}")
                    Logging.d(TAG, "Total route remaining distance: ${String.format("%.1f", totalRemainingDistance)}m")
                    Logging.d(TAG, "Total route remaining time: ${formatDuration(totalRemainingDuration)}")
                    Logging.d(TAG, "Traffic delay: ${formatDuration(trafficDelay)}")

                    // Update the next waypoint's remaining time and distance (don't refresh, will be done by updateAllUnpassedWaypoints)
                    updateWaypointInDatabase(
                        waypointId = nextWaypoint.id,
                        remainingTimeSeconds = currentRemainingDuration,
                        remainingDistanceMeters = currentRemainingDistance,
                        refreshData = false
                    )

                    // Update all unpassed waypoints with progressive values using total route remaining time
                    updateAllUnpassedWaypoints(totalRemainingDistance, totalRemainingDuration)
                } else {
                    Logging.d(TAG, "No unpassed waypoints found")
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating multi-waypoint progress: ${e.message}", e)
        }
    }

    /**
     * Update all unpassed waypoints with progressive remaining time/distance
     */
    private fun updateAllUnpassedWaypoints(currentRemainingDistance: Double, currentRemainingTime: Long) {
        try {
            currentTrip?.let { trip ->
                val unpassedWaypoints = trip.waypoints
                    .filter { !it.is_passed }
                    .sortedBy { it.order }

                Logging.d(TAG, "=== UPDATING ALL UNPASSED WAYPOINTS ===")
                Logging.d(TAG, "Found ${unpassedWaypoints.size} unpassed waypoints")
                Logging.d(TAG, "Current remaining distance: ${String.format("%.1f", currentRemainingDistance)}m")
                Logging.d(TAG, "Current remaining time: ${formatDuration(currentRemainingTime)}")

                unpassedWaypoints.forEachIndexed { index, waypoint ->
                    val waypointOrder = waypoint.order
                    
                    // Calculate progressive remaining time/distance
                    // Each waypoint gets more time/distance than the previous
                    val additionalTimePerWaypoint = 30L // 30 seconds per waypoint
                    val additionalDistancePerWaypoint = 200.0 // 200 meters per waypoint
                    
                    val calculatedTime = currentRemainingTime + (additionalTimePerWaypoint * (waypointOrder - 1))
                    val calculatedDistance = currentRemainingDistance + (additionalDistancePerWaypoint * (waypointOrder - 1))
                    
                    Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                    Logging.d(TAG, "  - Base time: ${formatDuration(currentRemainingTime)}, Base distance: ${String.format("%.1f", currentRemainingDistance)}m")
                    Logging.d(TAG, "  - Additional time: ${formatDuration(additionalTimePerWaypoint * (waypointOrder - 1))}, Additional distance: ${String.format("%.1f", additionalDistancePerWaypoint * (waypointOrder - 1))}m")
                    Logging.d(TAG, "  - Final time: ${formatDuration(calculatedTime)}, Final distance: ${String.format("%.1f", calculatedDistance)}m")
                    
                    // Update waypoint in database (don't refresh individual waypoints)
                    updateWaypointInDatabase(
                        waypointId = waypoint.id,
                        remainingTimeSeconds = calculatedTime,
                        remainingDistanceMeters = calculatedDistance,
                        refreshData = false
                    )
                }
                
                Logging.d(TAG, "=====================================")
                
                // Refresh trip data once after updating all waypoints
                refreshTripData()
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating all unpassed waypoints: ${e.message}", e)
        }
    }

    /**
     * Handle waypoint reached event (distance-based detection for waypoints)
     */
    private fun handleWaypointReached(sectionProgress: SectionProgress) {
        try {
            currentTrip?.let { trip ->
                Logging.d(TAG, "=== HANDLING WAYPOINT REACHED (distance-based) ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Total waypoints: ${trip.waypoints.size}")
                
                // Log all waypoints for debugging
                trip.waypoints.forEachIndexed { index, waypoint ->
                    Logging.d(TAG, "Waypoint $index: ID=${waypoint.id}, Order=${waypoint.order}, Passed=${waypoint.is_passed}, Name=${waypoint.location.google_place_name}")
                }
                
                val nextWaypoint = getNextUnpassedWaypoint()
                Logging.d(TAG, "Next unpassed waypoint: ${nextWaypoint?.location?.google_place_name ?: "null"}")
                
                if (nextWaypoint != null) {
                    Logging.d(TAG, "🎉 WAYPOINT REACHED (distance-based)!")
                    Logging.d(TAG, "Waypoint: ${nextWaypoint.location.google_place_name}")
                    Logging.d(TAG, "Order: ${nextWaypoint.order}")

                    // Publish waypoint reached notification via MQTT
                    publishWaypointReachedNotification(nextWaypoint)

                    // Mark waypoint as passed
                    markWaypointAsPassed(nextWaypoint.id)
                    
                    // Check if this was the last waypoint (destination reached)
                    val remainingWaypoints = trip.waypoints.filter { !it.is_passed }
                    if (remainingWaypoints.isEmpty()) {
                        Logging.d(TAG, "🏁 FINAL DESTINATION REACHED (distance-based)!")
                        
                        // Publish destination reached notification via MQTT
                        publishDestinationReachedNotification()
                        
                        // Send final progress update with completed status (this will update status and refresh data)
                        publishFinalTripProgressUpdate()
                    } else {
                        Logging.d(TAG, "Waypoint reached, ${remainingWaypoints.size} waypoints remaining")
                    }
                } else {
                    Logging.d(TAG, "No unpassed waypoints found - this might be the final destination")
                }
                
                Logging.d(TAG, "===============================================")
            } ?: run {
                Logging.e(TAG, "Current trip is null in handleWaypointReached")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling waypoint reached: ${e.message}", e)
        }
    }

    /**
     * Get the next unpassed waypoint
     */
    private fun getNextUnpassedWaypoint(): TripWaypoint? {
        return try {
            currentTrip?.waypoints
                ?.filter { !it.is_passed }
                ?.minByOrNull { it.order }
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
                withContext(Dispatchers.IO) {
                    databaseManager.updateWaypointRemaining(
                        tripId = tripId,
                        waypointId = waypointId,
                        remainingTimeSeconds = remainingTimeSeconds,
                        remainingDistanceMeters = remainingDistanceMeters
                    )
                }
                Logging.d(TAG, "Updated waypoint $waypointId in database: time=${remainingTimeSeconds?.let { formatDuration(it) } ?: "null"}, distance=${remainingDistanceMeters?.let { String.format("%.1f", it) } ?: "null"}m")
                
                // Refresh trip data to get updated waypoint values for MQTT (if requested)
                if (refreshData) {
                    refreshTripData()
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update waypoint $waypointId in database: ${e.message}", e)
            }
        }
    }

    /**
     * Mark waypoint as passed
     */
    private fun markWaypointAsPassed(waypointId: Int) {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    databaseManager.updateWaypointStatus(tripId, waypointId, true)
                }
                Logging.d(TAG, "Marked waypoint $waypointId as passed")
                
                // Refresh trip data to get updated waypoint status
                refreshTripData()
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to mark waypoint $waypointId as passed: ${e.message}", e)
            }
        }
    }

    /**
     * Refresh trip data from database
     */
    private fun refreshTripData() {
        coroutineScope.launch {
            try {
                val freshTrip = databaseManager.getTripById(tripId)
                if (freshTrip != null) {
                    currentTrip = freshTrip
                    Logging.d(TAG, "Refreshed trip data from database")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error refreshing trip data: ${e.message}", e)
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
            currentRoute = null
            isInitialized = false
            tripStarted = false
            lastProgressUpdateTime = 0L
            lastWaypointApproachNotification.clear()
            Logging.d(TAG, "Route progress tracker reset for trip $tripId")
        } catch (e: Exception) {
            Logging.e(TAG, "Error resetting progress tracker: ${e.message}", e)
        }
    }

    /**
     * Publish trip start event via MQTT
     */
    private fun publishTripStartEvent() {
        try {
            currentTrip?.let { trip ->
                if (!tripStarted) {
                    Logging.d(TAG, "=== PUBLISHING TRIP START EVENT ===")
                    Logging.d(TAG, "Trip ID: ${trip.id}")
                    Logging.d(TAG, "Vehicle: ${trip.vehicle.license_plate}")
                    Logging.d(TAG, "Origin: ${trip.route.origin.google_place_name}")
                    Logging.d(TAG, "Destination: ${trip.route.destination.google_place_name}")
                    Logging.d(TAG, "================================")
                    
                    mqttService?.sendTripStatusUpdate(
                        tripId = trip.id.toString(),
                        status = "started"
                    )?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to publish trip start event: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Trip start event published successfully")
                            tripStarted = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing trip start event: ${e.message}", e)
        }
    }

    /**
     * Publish trip progress update via MQTT
     */
    private fun publishTripProgressUpdate(
        remainingTime: Long?,
        remainingDistance: Double?,
        currentSpeed: Double? = null,
        currentLocation: com.here.sdk.core.Location? = null
    ) {
        try {
            currentTrip?.let { trip ->
                val currentTime = System.currentTimeMillis()
                
                // Check if enough time has passed since last update
                if (currentTime - lastProgressUpdateTime >= PROGRESS_UPDATE_INTERVAL_SECONDS * 1000) {
                    Logging.d(TAG, "=== PUBLISHING TRIP PROGRESS UPDATE ===")
                    Logging.d(TAG, "Trip ID: ${trip.id}")
                    Logging.d(TAG, "Remaining time: ${remainingTime?.let { formatDuration(it) } ?: "Unknown"} (${remainingTime} seconds)")
                    Logging.d(TAG, "Remaining distance: ${remainingDistance?.let { String.format("%.1f", it) } ?: "Unknown"}m (${remainingDistance} meters)")
                    Logging.d(TAG, "Current speed: ${currentSpeed?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
                    Logging.d(TAG, "=====================================")
                    
                    // Convert to MQTT location format
                    val mqttLocation = currentLocation?.let { location ->
                        MqttService.Location(
                            latitude = location.coordinates.latitude,
                            longitude = location.coordinates.longitude
                        )
                    }
                    
                    mqttService?.sendTripEventMessage(
                        event = "progress_update",
                        tripData = mqttService.convertTripResponseToTripData(
                            tripResponse = trip,
                            currentSpeed = currentSpeed,
                            currentLocation = mqttLocation,
                            remainingTimeToDestination = remainingTime,
                            remainingDistanceToDestination = remainingDistance
                        )
                    )?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to publish trip progress update: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Trip progress update published successfully")
                            lastProgressUpdateTime = currentTime
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing trip progress update: ${e.message}", e)
        }
    }

    /**
     * Publish waypoint approaching notification via MQTT
     */
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

    /**
     * Publish waypoint reached notification via MQTT
     */
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

    /**
     * Publish destination reached notification via MQTT
     */
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

    /**
     * Publish final trip progress update with completed status
     */
    private fun publishFinalTripProgressUpdate() {
        coroutineScope.launch {
            try {
                // First update the trip status and wait for refresh
                updateTripStatusToCompletedAndRefresh()
                
                currentTrip?.let { trip ->
                    Logging.d(TAG, "Publishing final trip progress update with completed status")
                    Logging.d(TAG, "Trip status: ${trip.status}")
                    
                    // Convert trip data with completed status
                    val tripData = mqttService?.convertTripResponseToTripData(
                        tripResponse = trip,
                        currentSpeed = 0.0, // Vehicle stopped at destination
                        currentLocation = null, // Use destination location
                        remainingTimeToDestination = 0L, // Trip completed
                        remainingDistanceToDestination = 0.0 // Trip completed
                    )
                    
                    if (tripData != null) {
                        Logging.d(TAG, "Trip data status: ${tripData.status}")
                        mqttService?.sendTripEventMessage(
                            event = "progress_update",
                            tripData = tripData
                        )?.whenComplete { result, throwable ->
                            if (throwable != null) {
                                Logging.e(TAG, "Failed to publish final trip progress update: ${throwable.message}")
                            } else {
                                Logging.d(TAG, "Final trip progress update published successfully")
                            }
                        }
                    } else {
                        Logging.e(TAG, "Failed to convert trip data for final progress update")
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error publishing final trip progress update: ${e.message}", e)
            }
        }
    }

    /**
     * Update trip status to completed in database
     */
    private fun updateTripStatusToCompleted() {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    databaseManager.updateTripStatus(tripId, "completed")
                }
                Logging.d(TAG, "✅ Trip $tripId status updated to 'completed' in database")
                
                // Refresh trip data to get updated status
                refreshTripData()
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update trip status to completed: ${e.message}", e)
            }
        }
    }

    /**
     * Update trip status to completed and wait for refresh to complete
     */
    private suspend fun updateTripStatusToCompletedAndRefresh() {
        try {
            withContext(Dispatchers.IO) {
                databaseManager.updateTripStatus(tripId, "completed")
            }
            Logging.d(TAG, "✅ Trip $tripId status updated to 'completed' in database")
            
            // Refresh trip data synchronously to get updated status
            refreshTripDataSync()
            Logging.d(TAG, "Trip data refreshed after status update")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to update trip status to completed: ${e.message}")
        }
    }

    /**
     * Refresh trip data synchronously from database
     */
    private suspend fun refreshTripDataSync() {
        try {
            val freshTrip = databaseManager.getTripById(tripId)
            if (freshTrip != null) {
                currentTrip = freshTrip
                Logging.d(TAG, "Refreshed trip data from database - Status: ${freshTrip.status}")
            } else {
                Logging.e(TAG, "Failed to get fresh trip data from database")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error refreshing trip data: ${e.message}", e)
        }
    }
}
