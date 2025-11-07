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
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.service.RouteProgressMqttService
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.routing.Route
import com.here.sdk.routing.Section
import com.here.sdk.navigation.SectionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Validates route sections against trip data and maps section progress to waypoint information.
 * Now includes integrated RouteProgressTracker for MQTT trip progress updates.
 * 
 * This class ensures that:
 * 1. Route has correct number of sections based on simulation mode and location proximity:
 *    - Simulated mode: n-1 sections for n total locations (origin + waypoints + destination)
 *    - Device location mode (device ≠ trip origin): n sections for n+1 total locations (device + origin + waypoints + destination)
 *    - Device location mode (device ≈ trip origin): n-1 sections for n total locations (same as simulated mode)
 * 2. Maps section progress to appropriate waypoint names and locations
 * 3. Tracks waypoint completion with timestamps as sections are reduced
 * 4. Records passed waypoints with distance-based detection (10m threshold)
 * 5. Handles device location awareness with proximity detection (50m threshold) to determine if device location matches trip origin
 * 6. Sends MQTT trip progress updates and handles trip lifecycle events
 */
class TripSectionValidator(private val context: Context) {
    
    private var isVerified = false
    private var tripResponse: TripResponse? = null
    private var route: Route? = null
    private var waypointNames: List<String> = emptyList()
    private var passedWaypoints: MutableSet<Int> = mutableSetOf()
    private var passedWaypointData: MutableList<PassedWaypointInfo> = mutableListOf()
    private var currentSectionProgress: List<SectionProgress> = emptyList()
    private var lastProcessedSectionCount: Int = -1
    
    // Device location awareness
    private var isDeviceLocationMode = false
    private var deviceLocationOffset = 0 // How many extra waypoints at the start due to device location
    
    // File logging
    private var tripSessionLogger: TripSessionLogger? = null
    private var isFirstProgressUpdate = true
    
    // Flag to prevent database writes during initialization/resume
    private var isInitializingAfterResume = true
    
    // Callback for waypoint passed notifications (for UI updates)
    private var waypointPassedCallback: (() -> Unit)? = null
    
    // Callback for trip deletion/cancellation (notifies service/activity to stop navigation)
    private var tripDeletedCallback: ((Int) -> Unit)? = null
    
    // Integrated RouteProgressTracker functionality
    private val databaseManager = DatabaseManager.getInstance(context)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val routeProgressMqttService = RouteProgressMqttService.getInstance()
    private var mqttService: MqttService? = null
    private var isInitialized = false
    private var lastProgressUpdateTime = 0L
    private var lastWaypointApproachNotification = mutableSetOf<Int>()
    private var tripStarted = false
    
    // Latest location data from navigableLocationListener
    private var latestLat: Double? = null
    private var latestLng: Double? = null
    private var latestSpeed: Double? = null
    private var latestAccuracy: Double? = null
    private var latestBearing: Double? = null
    
    companion object {
        private const val TAG = "TripSectionValidator"
        private const val LOCATION_PROXIMITY_THRESHOLD_METERS = 50.0 // Consider locations "same" if within 50m
        private const val WAYPOINT_APPROACHING_TIME_SECONDS = 300L // 5 minutes
        private const val PROGRESS_UPDATE_INTERVAL_SECONDS = 5L // 5 seconds
    }
    
    /**
     * Data class to store information about passed waypoints
     */
    data class PassedWaypointInfo(
        val waypointIndex: Int,
        val waypointName: String,
        val passedTimestamp: Long,
        val passedDistance: Double,
        val sectionIndex: Int
    )
    
    /**
     * Initialize MQTT service for trip progress tracking
     */
    fun initializeMqttService(mqttService: MqttService) {
        this.mqttService = mqttService
        routeProgressMqttService.initialize(mqttService)
        Logging.d(TAG, "MQTT service initialized for TripSectionValidator")
        Logging.d(TAG, "MQTT service connected: ${mqttService.isConnected()}")
    }
    
    /**
     * Update latest location data from navigableLocationListener
     */
    fun updateLocationData(lat: Double, lng: Double, speed: Double, accuracy: Double, bearing: Double?) {
        latestLat = lat
        latestLng = lng
        latestSpeed = speed
        latestAccuracy = accuracy
        latestBearing = bearing
    }
    
    /**
     * Set callback for UI updates when waypoint is passed
     */
    fun setWaypointPassedCallback(callback: (() -> Unit)?) {
        this.waypointPassedCallback = callback
    }
    
    /**
     * Set callback for trip deletion/cancellation
     * Called when trip is not found in database during processing
     */
    fun setTripDeletedCallback(callback: ((Int) -> Unit)?) {
        this.tripDeletedCallback = callback
    }

    /**
     * Verifies if the route sections match the trip data structure.
     * 
     * @param tripResponse The trip data containing waypoints
     * @param route The calculated route with sections
     * @param isSimulated Whether the route was calculated with simulated location (true) or device location (false)
     * @param deviceLocation The current device location (optional, used to check if it matches trip origin)
     * @param skippedWaypointCount Number of waypoints that were skipped (already passed)
     * @return true if verification passes, false otherwise
     */
    fun verifyRouteSections(
        tripResponse: TripResponse, 
        route: Route, 
        isSimulated: Boolean = true, 
        deviceLocation: GeoCoordinates? = null,
        skippedWaypointCount: Int = 0
    ): Boolean {
        Log.d(TAG, "=== STARTING ROUTE SECTION VERIFICATION ===")
        
        this.tripResponse = tripResponse
        this.route = route
        this.isDeviceLocationMode = !isSimulated
        
        // Calculate expected sections accounting for skipped waypoints
        val totalWaypoints = tripResponse.waypoints.size
        val activeWaypoints = totalWaypoints - skippedWaypointCount
        val tripLocations = 1 + activeWaypoints + 1 // origin + active waypoints + destination
        val expectedSections: Int
        val actualSections = route.sections.size
        
        Log.d(TAG, "=== WAYPOINT ACCOUNTING ===")
        Log.d(TAG, "Total waypoints: $totalWaypoints")
        Log.d(TAG, "Skipped waypoints: $skippedWaypointCount")
        Log.d(TAG, "Active waypoints: $activeWaypoints")
        Log.d(TAG, "===========================")
        
        if (isDeviceLocationMode) {
            // Device location completely replaces trip origin
            // Route structure: Device Location -> Active Waypoints -> Destination (no trip origin)
            // Same as simulated mode: n-1 sections for n locations
            expectedSections = tripLocations - 1
            deviceLocationOffset = 0 // No extra waypoints (device location replaces origin)
            Log.d(TAG, "🔍 DEVICE LOCATION MODE: Route starts from device location (replaces trip origin)")
            Log.d(TAG, "  Route structure: Device Location -> Active Waypoints -> Destination")
        } else {
            // In simulated mode, route starts from trip origin
            expectedSections = tripLocations - 1 // n-1 sections for n locations
            deviceLocationOffset = 0 // No extra waypoints
            Log.d(TAG, "🎮 SIMULATED MODE: Route starts from trip origin")
            Log.d(TAG, "  Route structure: Trip Origin -> Active Waypoints -> Destination")
        }
        
        Log.d(TAG, "Trip ID: ${tripResponse.id}")
        Log.d(TAG, "Trip locations: $tripLocations (origin + $activeWaypoints active waypoints + destination)")
        Log.d(TAG, "Device location offset: $deviceLocationOffset")
        Log.d(TAG, "Expected sections: $expectedSections")
        Log.d(TAG, "Actual sections: $actualSections")
        
        // Build waypoint names list for mapping (only unpassed waypoints)
        waypointNames = buildWaypointNamesList(tripResponse, isDeviceLocationMode, skippedWaypointCount)
        Log.d(TAG, "Waypoint names (excluding skipped): $waypointNames")
        
        // Verify section count
        val verificationPassed = actualSections == expectedSections
        
        if (verificationPassed) {
            Log.d(TAG, "✅ VERIFICATION PASSED: Route sections match trip structure (accounting for $skippedWaypointCount skipped waypoints)")
            isVerified = true
            passedWaypoints.clear() // Reset passed waypoints
            passedWaypointData.clear() // Reset passed waypoint data
            lastProcessedSectionCount = -1 // Reset section count tracking
            isFirstProgressUpdate = true // Reset first progress update flag
            
            // Initialize file logging for this trip session
            initializeTripSessionLogging(tripResponse)
            
            // Initialize waypoint data for MQTT tracking
            initializeWaypointData(route.sections)
            
            // Rebuild passedWaypoints set from database state to fix index mismatch
            rebuildPassedWaypointsFromDatabase()
            
            // Mark origin as passed at trip start (verification time) if not already marked
            if (!passedWaypoints.contains(0)) {
                markOriginAsPassed()
            } else {
                Log.d(TAG, "Origin already marked as passed in database, skipping markOriginAsPassed()")
            }
            
            // Log initial section details
            logSectionDetails(route.sections)
        } else {
            Log.e(TAG, "❌ VERIFICATION FAILED: Expected $expectedSections sections, got $actualSections")
            Log.e(TAG, "   (Total waypoints: $totalWaypoints, Skipped: $skippedWaypointCount, Active: $activeWaypoints)")
            isVerified = false
        }
        
        Log.d(TAG, "=== VERIFICATION COMPLETE ===")
        return verificationPassed
    }
    
    /**
     * Processes section progress updates and maps them to waypoint information.
     * Only works for verified trips.
     * 
     * @param sectionProgressList List of section progress from navigation
     * @param totalSections Total number of sections in current route
     */
    fun processSectionProgress(sectionProgressList: List<SectionProgress>, totalSections: Int) {
        // FIRST: Check if trip is still valid (not null/reset)
        if (tripResponse == null) {
            Log.w(TAG, "Trip is null (cancelled/reset), skipping section progress processing")
            return
        }
        
        // SECOND: Verify trip still exists in database (might have been deleted)
        // Note: We check this in publishTripProgressUpdate() where we already fetch fresh trip data
        // This avoids blocking the main processing thread with a database check on every update
        
        if (!isVerified) {
            Log.w(TAG, "Trip not verified, skipping section progress processing")
            return
        }
        
        Log.d(TAG, "=== PROCESSING SECTION PROGRESS ===")
        Log.d(TAG, "Current sections: ${sectionProgressList.size}")
        Log.d(TAG, "Total sections: $totalSections")
        
        // Log first progress update to file
        if (isFirstProgressUpdate) {
            val detailedStatus = getComprehensiveStatusInfo()
            tripSessionLogger?.logFirstProgressUpdate(detailedStatus)
            isFirstProgressUpdate = false
            
            // Set initialization flag to false after first real progress update
            // This prevents overwriting correct database state with incorrect in-memory indices during resume
            if (isInitializingAfterResume) {
                Log.d(TAG, "First real progress update received - enabling database writes")
                isInitializingAfterResume = false
            }
        }
        
        // Store current section progress for UI access
        currentSectionProgress = sectionProgressList
        
        // Map current sections to waypoint information
        mapSectionsToWaypoints(sectionProgressList)
        
        // CRITICAL: ALWAYS write to database first, then trigger MQTT
        // This ensures database is the single source of truth
        // Skip database writes during initialization to prevent index mismatch issues
        if (!isInitializingAfterResume) {
            writeProgressToDatabase(sectionProgressList)
        } else {
            Log.d(TAG, "Skipping database write during initialization - preserving database state")
        }
        
        // Send periodic progress update via MQTT (reads from database)
        publishTripProgressUpdate()
        
        // Log passed waypoints summary
        logPassedWaypointsSummary()
        
        Log.d(TAG, "=== SECTION PROGRESS PROCESSING COMPLETE ===")
    }
    
    /**
     * Initialize waypoint data with length and time for each waypoint from route sections
     */
    private fun initializeWaypointData(sections: List<Section>) {
        try {
            tripResponse?.let { trip ->
                Logging.d(TAG, "=== INITIALIZING WAYPOINT DATA ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Total waypoints: ${trip.waypoints.size}")
                Logging.d(TAG, "Route sections: ${sections.size}")
                
                if (trip.waypoints.isEmpty()) {
                    Logging.d(TAG, "Single destination route - no waypoints to initialize")
                    return
                }
                
                // Extract section lengths and durations for waypoint mapping
                val waypointLengths = mutableListOf<Double>()
                val waypointTimes = mutableListOf<Long>()
                
                for (i in sections.indices) {
                    val section: Section = sections[i]
                    val length = section.lengthInMeters
                    val duration = section.duration.seconds
                    
                    waypointLengths.add(length.toDouble())
                    waypointTimes.add(duration)
                    
                    Logging.d(TAG, "Section ${i + 1}: Length=${String.format("%.1f", length.toDouble())}m, Duration=${duration}s")
                }
                
                // Update each waypoint with its specific length and time data
                trip.waypoints.sortedBy { it.order }.forEachIndexed { index, waypoint ->
                    if (index < waypointLengths.size && index < waypointTimes.size) {
                        val waypointLength = waypointLengths[index]
                        val waypointTime = waypointTimes[index]
                        
                        Logging.d(TAG, "Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Logging.d(TAG, "  - Original Length: ${String.format("%.1f", waypointLength)}m")
                        Logging.d(TAG, "  - Original Time: ${formatDuration(waypointTime)}")
                        
                        // Store original waypoint data in database
                        updateWaypointDataInDatabase(
                            waypointId = waypoint.id,
                            waypointLengthMeters = waypointLength,
                            waypointTimeSeconds = waypointTime
                        )
                    }
                }
                
                // Mark the first waypoint as the next waypoint at trip start
                if (trip.waypoints.isNotEmpty()) {
                    val firstWaypoint = trip.waypoints.minByOrNull { it.order }
                    if (firstWaypoint != null) {
                        Logging.d(TAG, "Marking first waypoint as next: ${firstWaypoint.location.google_place_name}")
                        
                        // Update database to mark first waypoint as next
                        coroutineScope.launch {
                            try {
                                databaseManager.updateWaypointNextStatus(trip.id, firstWaypoint.id, true)
                                Logging.d(TAG, "Successfully marked first waypoint as next in database")
                            } catch (e: Exception) {
                                Logging.e(TAG, "Failed to mark first waypoint as next in database: ${e.message}", e)
                            }
                        }
                    }
                }
                
                // Publish trip start event
                publishTripStartEvent()
                
                isInitialized = true
                Logging.d(TAG, "=====================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error initializing waypoint data: ${e.message}", e)
        }
    }

    /**
     * Rebuilds passedWaypoints set from database state to fix index mismatch after resume
     */
    private fun rebuildPassedWaypointsFromDatabase() {
        tripResponse?.let { trip ->
            Log.d(TAG, "=== REBUILDING PASSED WAYPOINTS FROM DATABASE ===")
            passedWaypoints.clear()
            passedWaypointData.clear()
            
            // Check which waypoints are marked as passed in the database
            val sortedWaypoints = trip.waypoints.sortedBy { it.order }
            val unpassedWaypoints = sortedWaypoints.filter { !it.is_passed }
            
            Log.d(TAG, "Total waypoints in trip: ${sortedWaypoints.size}")
            Log.d(TAG, "Passed waypoints in DB: ${sortedWaypoints.count { it.is_passed }}")
            Log.d(TAG, "Unpassed waypoints in DB: ${unpassedWaypoints.size}")
            
            // Map database waypoint state to waypointNames indices
            // waypointNames = ["Origin", "Unpassed Waypoint 1", "Unpassed Waypoint 2", ..., "Destination"]
            
            // Index 0 is always origin - check if trip origin is conceptually "passed" (trip started)
            if (trip.status.equals("IN_PROGRESS", ignoreCase = true) || sortedWaypoints.any { it.is_passed }) {
                // Trip has started, so origin is passed
                passedWaypoints.add(0)
                Log.d(TAG, "Added origin (index 0) to passedWaypoints based on trip status")
                
                // Add origin to passedWaypointData
                val originName = waypointNames.getOrNull(0) ?: "Origin"
                val passedInfo = PassedWaypointInfo(
                    waypointIndex = 0,
                    waypointName = originName,
                    passedTimestamp = System.currentTimeMillis(),
                    passedDistance = 0.0,
                    sectionIndex = -1
                )
                passedWaypointData.add(passedInfo)
            }
            
            // Note: waypointNames excludes passed waypoints, so we don't need to mark them
            // The passed waypoints are already filtered out from waypointNames by buildWaypointNamesList()
            // So passedWaypoints should only contain index 0 (origin) if the trip has started
            
            Log.d(TAG, "Rebuilt passedWaypoints set: $passedWaypoints")
            Log.d(TAG, "================================================")
        }
    }
    
    /**
     * Marks the starting location as passed at trip start time
     */
    private fun markOriginAsPassed() {
        val currentTime = System.currentTimeMillis()
        
        if (isDeviceLocationMode) {
            // In device location mode, mark device location as passed (index 0)
            val deviceLocationName = waypointNames[0] // Device location is at index 0
            passedWaypoints.add(0)
            
            // Calculate correct waypoint order for trip data
            val actualWaypointOrder = if (isDeviceLocationMode) {
                // In device location mode, index 0 = device location (not a trip waypoint)
                // Device location is not a trip waypoint, so it should have order 0
                0 // Device location is not a trip waypoint
            } else {
                // In simulated mode, index 0 = origin, index 1 = first waypoint (order=1)
                0 // Origin at index 0, waypoint order matches (index-0)
            }
            
            val passedInfo = PassedWaypointInfo(
                waypointIndex = actualWaypointOrder, // Use correct waypoint order
                waypointName = deviceLocationName,
                passedTimestamp = currentTime,
                passedDistance = 0.0,
                sectionIndex = -1
            )
            passedWaypointData.add(passedInfo)
            
            Log.d(TAG, "🏁 DEVICE LOCATION MARKED AS PASSED: $deviceLocationName")
            Log.d(TAG, "  ⏰ Trip started at: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
            Log.d(TAG, "  📊 Actual waypoint order: $actualWaypointOrder")
            
            // Log device location marking to file
            val deviceLocationMarkingInfo = "🏁 DEVICE LOCATION MARKED AS PASSED: $deviceLocationName\n  ⏰ Trip started at: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}"
            tripSessionLogger?.logCustomEvent("DEVICE_LOCATION_MARKED", deviceLocationMarkingInfo)
        } else {
            // In simulated mode, mark origin as passed (index 0)
            val originName = waypointNames[0] // Origin is at index 0
            passedWaypoints.add(0)
            
            // Calculate correct waypoint order for trip data
            val actualWaypointOrder = if (isDeviceLocationMode) {
                // In device location mode, index 0 = device location (not a trip waypoint)
                0 // Device location is not a trip waypoint
            } else {
                // In simulated mode, index 0 = origin, index 1 = first waypoint (order=1)
                0 // Origin at index 0, waypoint order matches (index-0)
            }
            
            val passedInfo = PassedWaypointInfo(
                waypointIndex = actualWaypointOrder, // Use correct waypoint order
                waypointName = originName,
                passedTimestamp = currentTime,
                passedDistance = 0.0,
                sectionIndex = -1
            )
            passedWaypointData.add(passedInfo)
            
            Log.d(TAG, "🏁 ORIGIN MARKED AS PASSED: $originName")
            Log.d(TAG, "  ⏰ Trip started at: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
            Log.d(TAG, "  📊 Actual waypoint order: $actualWaypointOrder")
            
            // Log origin marking to file
            val originMarkingInfo = "🏁 ORIGIN MARKED AS PASSED: $originName\n  ⏰ Trip started at: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}"
            tripSessionLogger?.logCustomEvent("ORIGIN_MARKED", originMarkingInfo)
        }
    }
    
    /**
     * Builds a list of waypoint names in order (device location, origin, waypoints, destination)
     * @param tripResponse The trip data
     * @param includeDeviceLocation Whether to include device location instead of origin
     * @param skippedWaypointCount Number of waypoints to skip (already passed)
     */
    private fun buildWaypointNamesList(
        tripResponse: TripResponse, 
        includeDeviceLocation: Boolean = false,
        skippedWaypointCount: Int = 0
    ): List<String> {
        val names = mutableListOf<String>()
        
        // Add starting location based on mode
        if (includeDeviceLocation) {
            // Device location mode: device location replaces origin
            names.add("Device Location: Current Position")
        } else {
            // Simulated mode: use trip origin
            names.add("Origin: ${tripResponse.route.origin.custom_name}")
        }
        
        // Add only unpassed intermediate waypoints sorted by order
        val sortedWaypoints = tripResponse.waypoints.sortedBy { it.order }
        val unpassedWaypoints = sortedWaypoints.filter { !it.is_passed }
        
        Log.d(TAG, "=== BUILDING WAYPOINT NAMES LIST ===")
        Log.d(TAG, "Total waypoints: ${sortedWaypoints.size}")
        Log.d(TAG, "Skipped count parameter: $skippedWaypointCount")
        Log.d(TAG, "Unpassed waypoints: ${unpassedWaypoints.size}")
        
        unpassedWaypoints.forEach { waypoint ->
            names.add("Waypoint ${waypoint.order}: ${waypoint.location.custom_name}")
            Log.d(TAG, "Added waypoint: ${waypoint.order} - ${waypoint.location.custom_name}")
        }
        
        // Add destination
        names.add("Destination: ${tripResponse.route.destination.custom_name}")
        
        Log.d(TAG, "Total names in list: ${names.size}")
        Log.d(TAG, "====================================")
        
        return names
    }
    
    /**
     * Logs detailed information about route sections
     */
    private fun logSectionDetails(sections: List<Section>) {
        Log.d(TAG, "=== ROUTE SECTION DETAILS ===")
        Log.d(TAG, "Device location mode: $isDeviceLocationMode")
        Log.d(TAG, "Device location offset: $deviceLocationOffset")
        
        sections.forEachIndexed { index, section ->
            Log.d(TAG, "Section ${index + 1}:")
            Log.d(TAG, "  Length: ${section.lengthInMeters}m")
            Log.d(TAG, "  Duration: ${section.duration.seconds}s")
            Log.d(TAG, "  From: ${waypointNames.getOrNull(index) ?: "Unknown"}")
            Log.d(TAG, "  To: ${waypointNames.getOrNull(index + 1) ?: "Unknown"}")
        }
        Log.d(TAG, "=============================")
    }
    
    
    
    
    /**
     * Handles waypoint completion when sections are reduced
     */
    private fun handleWaypointCompletion(currentSections: Int, expectedSections: Int) {
        // Calculate which waypoint was completed based on section reduction
        // When sections reduce, we need to find the first unpassed waypoint
        val completedWaypointIndex = findFirstUnpassedWaypointIndex()
        
        Log.d(TAG, "Section reduction detected: $expectedSections -> $currentSections")
        Log.d(TAG, "Completed waypoint index: $completedWaypointIndex")
        
        // Mark only the next unpassed waypoint as completed
        if (completedWaypointIndex != -1 && !passedWaypoints.contains(completedWaypointIndex)) {
            passedWaypoints.add(completedWaypointIndex)
            val waypointName = waypointNames[completedWaypointIndex]
            val currentTime = System.currentTimeMillis()
            
            // Calculate correct waypoint order for trip data
            val actualWaypointOrder = if (isDeviceLocationMode) {
                // In device location mode, index 0 = device location (not a trip waypoint)
                // index 1 = first trip waypoint (order=1), index 2 = second (order=2), etc.
                completedWaypointIndex // Already correct since device location at index 0 doesn't count
            } else {
                // In simulated mode, index 0 = origin, index 1 = first waypoint (order=1)
                completedWaypointIndex // Origin at index 0, waypoint order matches (index-0)
            }
            
            // Record passed waypoint data for section reduction
            val passedInfo = PassedWaypointInfo(
                waypointIndex = actualWaypointOrder, // Use correct waypoint order
                waypointName = waypointName,
                passedTimestamp = currentTime,
                passedDistance = 0.0, // Section reduced, so distance is 0
                sectionIndex = completedWaypointIndex - 1 // Previous section index
            )
            passedWaypointData.add(passedInfo)
            
            // Log waypoint mark to file with follow-up logs
            val detailedStatus = getComprehensiveStatusInfo()
            tripSessionLogger?.logWaypointMark(waypointName, actualWaypointOrder, detailedStatus)
            
            Log.d(TAG, "🎯 WAYPOINT COMPLETED (Section reduction): $waypointName")
            Log.d(TAG, "  ⏰ Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
            Log.d(TAG, "  📊 Waypoint index: $completedWaypointIndex")
        }
        
        Log.d(TAG, "  📊 Sections reduced from $expectedSections to $currentSections")
    }
    
    /**
     * Finds the first unpassed waypoint index
     */
    private fun findFirstUnpassedWaypointIndex(): Int {
        for (i in waypointNames.indices) {
            if (!passedWaypoints.contains(i)) {
                Log.d(TAG, "findFirstUnpassedWaypointIndex: Found unpassed waypoint at index $i")
                return i
            }
        }
        Log.d(TAG, "findFirstUnpassedWaypointIndex: All waypoints passed, returning -1")
        return -1 // All waypoints passed
    }
    
    
    /**
     * Handles trip completion when all sections are gone
     */
    private fun handleTripCompletion() {
        Log.d(TAG, "🏁 TRIP COMPLETION DETECTED!")
        
        // Mark all remaining waypoints as passed
        for (i in passedWaypoints.size until waypointNames.size) {
            if (!passedWaypoints.contains(i)) {
                passedWaypoints.add(i)
                val waypointName = waypointNames[i]
                val currentTime = System.currentTimeMillis()
                
                // Calculate correct waypoint order for trip data
                val actualWaypointOrder = if (isDeviceLocationMode) {
                    // In device location mode, index 0 = device location (not a trip waypoint)
                    // index 1 = first trip waypoint (order=1), index 2 = second (order=2), etc.
                    i // Already correct since device location at index 0 doesn't count
                } else {
                    // In simulated mode, index 0 = origin, index 1 = first waypoint (order=1)
                    i // Origin at index 0, waypoint order matches (index-0)
                }
                
                // Record passed waypoint data for trip completion
                val passedInfo = PassedWaypointInfo(
                    waypointIndex = actualWaypointOrder, // Use correct waypoint order
                    waypointName = waypointName,
                    passedTimestamp = currentTime,
                    passedDistance = 0.0, // Trip completed, so distance is 0
                    sectionIndex = -1 // No section for trip completion
                )
                passedWaypointData.add(passedInfo)
                
                Log.d(TAG, "🎯 WAYPOINT COMPLETED (Trip completion): $waypointName")
                Log.d(TAG, "  ⏰ Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
                Log.d(TAG, "  📊 Waypoint index: $i")
                Log.d(TAG, "  📊 Actual waypoint order: $actualWaypointOrder")
            }
        }
        
        // Log trip completion to file
        val detailedStatus = getComprehensiveStatusInfo()
        tripSessionLogger?.logTripCompletion(detailedStatus)
        
        Log.d(TAG, "🎉 TRIP COMPLETED! All waypoints reached.")
    }
    
    /**
     * Called by milestone listener when a waypoint is reached
     */
    fun markWaypointAsPassedByMilestone(waypointOrder: Int, wayCordinates: GeoCoordinates) {
        if (!isVerified) {
            Log.w(TAG, "Trip not verified, ignoring milestone event")
            return
        }
        
        // Convert TripWaypoint.order to waypointNames index
        // waypointOrder 1 = waypointNames[1], waypointOrder 2 = waypointNames[2], etc.
        val waypointIndex = waypointOrder
        
        if (passedWaypoints.contains(waypointIndex)) {
            Log.d(TAG, "Waypoint $waypointOrder already marked as passed")
            return
        }
        
        val waypointName = waypointNames.getOrNull(waypointIndex) ?: "Unknown"
        val currentTime = System.currentTimeMillis()
        
        Log.d(TAG, "🎯 WAYPOINT PASSED VIA MILESTONE: $waypointName")
        Log.d(TAG, "  📊 Waypoint order (TripWaypoint.order): $waypointOrder")
        Log.d(TAG, "  📊 Waypoint index (waypointNames index): $waypointIndex")
        Log.d(TAG, "  📊 Passed waypoints before: $passedWaypoints")
        Log.d(TAG, "  ⏰ Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
        
        passedWaypoints.add(waypointIndex)
        
        // Store with waypointIndex (not waypointOrder) for consistent lookups
        val passedInfo = PassedWaypointInfo(
            waypointIndex = waypointIndex,
            waypointName = waypointName,
            passedTimestamp = currentTime,
            passedDistance = 0.0,
            sectionIndex = waypointIndex - 1
        )
        passedWaypointData.add(passedInfo)
        
        // Log to file
        val detailedStatus = getComprehensiveStatusInfo()
        tripSessionLogger?.logWaypointMark(waypointName, waypointOrder, detailedStatus)
        
        // Handle MQTT
        handleWaypointReached(waypointOrder, wayCordinates)
        publishTripProgressUpdate()
    }

    /**
     * Called by destination reached listener
     */
    fun handleDestinationReached() {
        Log.d(TAG, "🏁 DESTINATION REACHED!")
        
        // Mark any remaining waypoints as passed
        for (i in passedWaypoints.size until waypointNames.size) {
            if (!passedWaypoints.contains(i)) {
                passedWaypoints.add(i)
                val waypointName = waypointNames[i]
                val currentTime = System.currentTimeMillis()
                
                val passedInfo = PassedWaypointInfo(
                    waypointIndex = i,
                    waypointName = waypointName,
                    passedTimestamp = currentTime,
                    passedDistance = 0.0,
                    sectionIndex = -1
                )
                passedWaypointData.add(passedInfo)
                
                Log.d(TAG, "🎯 WAYPOINT COMPLETED (Destination): $waypointName")
            }
        }
        
        // Log trip completion
        val detailedStatus = getComprehensiveStatusInfo()
        tripSessionLogger?.logTripCompletion(detailedStatus)
        
        // Publish destination reached notification and final progress update
        publishDestinationReachedNotification()
        publishFinalTripProgressUpdate()
        
        Log.d(TAG, "🎉 TRIP COMPLETED! All waypoints reached.")
    }
    
    /**
     * Maps current section progress to waypoint information and logs details
     */
    private fun mapSectionsToWaypoints(sectionProgressList: List<SectionProgress>) {
        Log.d(TAG, "=== SECTION TO WAYPOINT MAPPING ===")
        Log.d(TAG, "Passed waypoints: $passedWaypoints")
        Log.d(TAG, "Total waypoints: ${waypointNames.size}")
        
        sectionProgressList.forEachIndexed { sectionIndex, sectionProgress ->
            // Calculate the target waypoint index for this section
            // Section 0 = to first unpassed waypoint, Section 1 = to second unpassed waypoint, etc.
            val targetWaypointIndex = passedWaypoints.size + sectionIndex
            
            // Skip logging for already-passed waypoints
            if (passedWaypoints.contains(targetWaypointIndex)) {
                Log.d(TAG, "Section $sectionIndex: Skipping updates for already-passed waypoint at index $targetWaypointIndex")
                return@forEachIndexed
            }
            
            val fromWaypoint = if (targetWaypointIndex > 0) {
                waypointNames.getOrNull(targetWaypointIndex - 1) ?: "Unknown"
            } else {
                "Origin"
            }
            val toWaypoint = waypointNames.getOrNull(targetWaypointIndex) ?: "Unknown"
            
            Log.d(TAG, "Section $sectionIndex:")
            Log.d(TAG, "  Route: $fromWaypoint → $toWaypoint")
            Log.d(TAG, "  Target waypoint index: $targetWaypointIndex")
            Log.d(TAG, "  Remaining distance: ${sectionProgress.remainingDistanceInMeters}m")
            Log.d(TAG, "  Remaining duration: ${sectionProgress.remainingDuration.toSeconds()}s")
            Log.d(TAG, "  Traffic delay: ${sectionProgress.trafficDelay.seconds}s")
        }
        
        // Log overall progress
        val lastSection = sectionProgressList.lastOrNull()
        if (lastSection != null) {
            val totalRemainingDistance = lastSection.remainingDistanceInMeters
            val totalRemainingTime = lastSection.remainingDuration.toSeconds()
            val finalDestination = waypointNames.lastOrNull() ?: "Unknown"
            
            Log.d(TAG, "📍 OVERALL PROGRESS:")
            Log.d(TAG, "  Final destination: $finalDestination")
            Log.d(TAG, "  Total remaining distance: ${totalRemainingDistance}m")
            Log.d(TAG, "  Total remaining time: ${totalRemainingTime}s")
        }
        
        Log.d(TAG, "================================")
    }
    
    /**
     * Logs summary of all passed waypoints with timestamps
     */
    private fun logPassedWaypointsSummary() {
        if (passedWaypointData.isNotEmpty()) {
            Log.d(TAG, "=== PASSED WAYPOINTS SUMMARY ===")
            passedWaypointData.forEach { passedInfo ->
                val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                val formattedTime = timeFormat.format(passedInfo.passedTimestamp)
                Log.d(TAG, "✅ ${passedInfo.waypointName}")
                Log.d(TAG, "  📍 Distance: ${passedInfo.passedDistance}m")
                Log.d(TAG, "  ⏰ Passed at: $formattedTime")
                Log.d(TAG, "  📊 Section: ${passedInfo.sectionIndex}")
            }
            Log.d(TAG, "Total passed waypoints: ${passedWaypointData.size}")
            Log.d(TAG, "================================")
        }
    }
    
    /**
     * Gets the current verification status
     */
    fun isVerified(): Boolean = isVerified
    
    /**
     * Gets the number of passed waypoints
     */
    fun getPassedWaypointsCount(): Int = passedWaypoints.size
    
    /**
     * Gets the total number of waypoints (including origin and destination)
     */
    fun getTotalWaypointsCount(): Int = waypointNames.size
    
    /**
     * Gets whether the validator is in device location mode
     */
    fun isDeviceLocationMode(): Boolean = isDeviceLocationMode
    
    /**
     * Gets the device location offset (number of extra waypoints at start)
     */
    fun getDeviceLocationOffset(): Int = deviceLocationOffset
    
    /**
     * Gets the proximity threshold for considering locations as the same (in meters)
     */
    fun getLocationProximityThreshold(): Double = LOCATION_PROXIMITY_THRESHOLD_METERS
    
    /**
     * Calculates the distance between two coordinates using the Haversine formula
     */
    private fun calculateDistance(coord1: GeoCoordinates, coord2: GeoCoordinates): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters
        
        val lat1Rad = Math.toRadians(coord1.latitude)
        val lat2Rad = Math.toRadians(coord2.latitude)
        val deltaLatRad = Math.toRadians(coord2.latitude - coord1.latitude)
        val deltaLonRad = Math.toRadians(coord2.longitude - coord1.longitude)
        
        val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * Checks if two coordinates are close enough to be considered the same location
     */
    private fun areLocationsClose(coord1: GeoCoordinates, coord2: GeoCoordinates, thresholdMeters: Double = LOCATION_PROXIMITY_THRESHOLD_METERS): Boolean {
        val distance = calculateDistance(coord1, coord2)
        return distance <= thresholdMeters
    }
    
    
    /**
     * Resets the validator state
     */
    fun reset() {
        // Stop current trip session logging
        tripSessionLogger?.stopTripSession()
        tripSessionLogger = null
        
        isVerified = false
        tripResponse = null
        route = null
        waypointNames = emptyList()
        passedWaypoints.clear()
        passedWaypointData.clear()
        currentSectionProgress = emptyList()
        lastProcessedSectionCount = -1
        isDeviceLocationMode = false
        deviceLocationOffset = 0
        isFirstProgressUpdate = true
        isInitializingAfterResume = true
        
        // Reset MQTT-related state
        isInitialized = false
        lastProgressUpdateTime = 0L
        lastWaypointApproachNotification.clear()
        tripStarted = false
        
        Log.d(TAG, "Validator state reset")
    }
    
    /**
     * Initializes trip session logging for the current trip
     */
    private fun initializeTripSessionLogging(tripResponse: TripResponse) {
        try {
            tripSessionLogger = TripSessionLogger(context)
            
            // Set the status provider callback
            tripSessionLogger?.setStatusProvider { getComprehensiveStatusInfo() }
            
            val tripName = "${tripResponse.route.origin.custom_name} to ${tripResponse.route.destination.custom_name}"
            tripSessionLogger?.startTripSession(tripResponse.id.toString(), tripName)
            
            // Log trip validation completion with detailed information
            val validationDetails = route?.let { getTripValidationDetails(tripResponse, it) }
            tripSessionLogger?.logTripValidation(validationDetails)
            
            Log.d(TAG, "Trip session logging initialized for trip: ${tripResponse.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize trip session logging: ${e.message}", e)
        }
    }
    
    /**
     * Gets the list of passed waypoint data
     */
    fun getPassedWaypointData(): List<PassedWaypointInfo> = passedWaypointData.toList()
    
    /**
     * Gets current waypoint progress data for UI display
     */
    fun getCurrentWaypointProgress(): List<WaypointProgressInfo> {
        if (!isVerified) return emptyList()
        
        val progressList = mutableListOf<WaypointProgressInfo>()
        
        Logging.d(TAG, "=== GETTING CURRENT WAYPOINT PROGRESS ===")
        Logging.d(TAG, "Total waypoint names: ${waypointNames.size}")
        Logging.d(TAG, "Passed waypoints: $passedWaypoints")
        Logging.d(TAG, "Current section progress: ${currentSectionProgress.size}")
        
        waypointNames.forEachIndexed { index, waypointName ->
            val isPassed = passedWaypoints.contains(index)
            val passedInfo = passedWaypointData.find { it.waypointIndex == index }
            val isNext = !isPassed && index == passedWaypoints.size
            
            // Calculate remaining time and distance ONLY for the next waypoint
            var remainingDistance: Double? = null
            var remainingTime: Long? = null
            var trafficDelay: Long? = null
            
            Logging.d(TAG, "Waypoint ${index + 1} (${waypointName}):")
            Logging.d(TAG, "  - Is passed: $isPassed")
            Logging.d(TAG, "  - Is next: $isNext")
            Logging.d(TAG, "  - Passed waypoints size: ${passedWaypoints.size}")
            Logging.d(TAG, "  - Current index: $index")
            
            // Only provide real-time data for the next waypoint (first unpassed waypoint)
            if (!isPassed && isNext && currentSectionProgress.isNotEmpty()) {
                // Only the next waypoint gets real-time data from the first section
                val sectionProgress = currentSectionProgress[0] // First section = next waypoint
                remainingDistance = sectionProgress.remainingDistanceInMeters.toDouble()
                remainingTime = sectionProgress.remainingDuration.toSeconds()
                trafficDelay = sectionProgress.trafficDelay.seconds
                
                Logging.d(TAG, "  - ✅ Providing real-time data for next waypoint: ${remainingTime}s, ${remainingDistance}m")
            } else if (!isPassed && !isNext) {
                // Future waypoints should have null remaining time/distance
                Logging.d(TAG, "  - ⏭️ Future waypoint - setting remaining time/distance to null")
                remainingDistance = null
                remainingTime = null
                trafficDelay = null
            } else if (isPassed) {
                Logging.d(TAG, "  - ✅ Passed waypoint - no remaining time/distance")
                remainingDistance = null
                remainingTime = null
                trafficDelay = null
            } else {
                Logging.d(TAG, "  - ⚠️ No section progress data available")
                remainingDistance = null
                remainingTime = null
                trafficDelay = null
            }
            
            progressList.add(
                WaypointProgressInfo(
                    waypointIndex = index + 1,
                    waypointName = waypointName,
                    isPassed = isPassed,
                    passedTimestamp = passedInfo?.passedTimestamp,
                    isNext = isNext,
                    remainingDistanceInMeters = remainingDistance,
                    remainingTimeInSeconds = remainingTime,
                    trafficDelayInSeconds = trafficDelay
                )
            )
        }
        
        Logging.d(TAG, "=== WAYPOINT PROGRESS SUMMARY ===")
        progressList.forEach { progress ->
            Logging.d(TAG, "Waypoint ${progress.waypointIndex} (${progress.waypointName}):")
            Logging.d(TAG, "  - Is passed: ${progress.isPassed}")
            Logging.d(TAG, "  - Is next: ${progress.isNext}")
            Logging.d(TAG, "  - Remaining time: ${progress.remainingTimeInSeconds?.let { formatDuration(it) } ?: "null"}")
            Logging.d(TAG, "  - Remaining distance: ${progress.remainingDistanceInMeters?.let { String.format("%.1f", it) } ?: "null"}m")
        }
        Logging.d(TAG, "=====================================")
        
        return progressList
    }
    
    /**
     * Gets the current log file path (for debugging)
     */
    fun getCurrentLogFilePath(): String? = tripSessionLogger?.getCurrentLogFilePath()
    
    /**
     * Checks if trip session logging is active
     */
    fun isTripSessionLoggingActive(): Boolean = tripSessionLogger?.isLoggingActive() ?: false
    
    /**
     * Gets the current trip data
     */
    fun getCurrentTrip(): TripResponse? = tripResponse
    
    /**
     * Check if tracker is initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Gets detailed trip validation information for logging
     */
    private fun getTripValidationDetails(tripResponse: TripResponse, route: Route): String {
        val detailsBuilder = StringBuilder()
        
        // Add verification passed message
        detailsBuilder.appendLine("✅ VERIFICATION PASSED: Route sections match trip structure")
        
        // Add trip session logging start message
        detailsBuilder.appendLine("Starting trip session logging for trip: ${tripResponse.id}")
        
        // Add route section details
        detailsBuilder.appendLine("=== ROUTE SECTION DETAILS ===")
        detailsBuilder.appendLine("Device location mode: $isDeviceLocationMode")
        detailsBuilder.appendLine("Device location offset: $deviceLocationOffset")
        
        route.sections.forEachIndexed { index, section ->
            detailsBuilder.appendLine("Section ${index + 1}:")
            detailsBuilder.appendLine("  Length: ${section.lengthInMeters}m")
            detailsBuilder.appendLine("  Duration: ${section.duration.seconds}s")
            detailsBuilder.appendLine("  From: ${waypointNames.getOrNull(index) ?: "Unknown"}")
            detailsBuilder.appendLine("  To: ${waypointNames.getOrNull(index + 1) ?: "Unknown"}")
        }
        detailsBuilder.appendLine("=============================")
        
        // Add verification complete message
        detailsBuilder.appendLine("=== VERIFICATION COMPLETE ===")
        detailsBuilder.appendLine("✅ Route verification passed - proceeding with navigation ...")
        
        return detailsBuilder.toString()
    }
    
    /**
     * Gets comprehensive status information for logging
     */
    private fun getComprehensiveStatusInfo(): String {
        if (!isVerified) return "Trip not verified"
        
        val statusBuilder = StringBuilder()
        
        // Add section progress information
        statusBuilder.appendLine("=== SECTION PROGRESS PROCESSING COMPLETE ===")
        statusBuilder.appendLine("Current sections: ${currentSectionProgress.size}")
        statusBuilder.appendLine("Total waypoints: ${waypointNames.size}")
        statusBuilder.appendLine("Passed waypoints: $passedWaypoints")
        
        // Add section to waypoint mapping
        statusBuilder.appendLine("=== SECTION TO WAYPOINT MAPPING ===")
        currentSectionProgress.forEachIndexed { sectionIndex, sectionProgress ->
            val targetWaypointIndex = passedWaypoints.size + sectionIndex
            
            if (!passedWaypoints.contains(targetWaypointIndex)) {
                val fromWaypoint = if (targetWaypointIndex > 0) {
                    waypointNames.getOrNull(targetWaypointIndex - 1) ?: "Unknown"
                } else {
                    "Origin"
                }
                val toWaypoint = waypointNames.getOrNull(targetWaypointIndex) ?: "Unknown"
                
                statusBuilder.appendLine("Section $sectionIndex:")
                statusBuilder.appendLine("  Route: $fromWaypoint → $toWaypoint")
                statusBuilder.appendLine("  Target waypoint index: $targetWaypointIndex")
                statusBuilder.appendLine("  Remaining distance: ${sectionProgress.remainingDistanceInMeters}m")
                statusBuilder.appendLine("  Remaining duration: ${sectionProgress.remainingDuration.toSeconds()}s")
                statusBuilder.appendLine("  Traffic delay: ${sectionProgress.trafficDelay.seconds}s")
            }
        }
        
        // Add overall progress
        val lastSection = currentSectionProgress.lastOrNull()
        if (lastSection != null) {
            val totalRemainingDistance = lastSection.remainingDistanceInMeters
            val totalRemainingTime = lastSection.remainingDuration.toSeconds()
            val finalDestination = waypointNames.lastOrNull() ?: "Unknown"
            
            statusBuilder.appendLine("📍 OVERALL PROGRESS:")
            statusBuilder.appendLine("  Final destination: $finalDestination")
            statusBuilder.appendLine("  Total remaining distance: ${totalRemainingDistance}m")
            statusBuilder.appendLine("  Total remaining time: ${totalRemainingTime}s")
        }
        
        // Add passed waypoints summary
        if (passedWaypointData.isNotEmpty()) {
            statusBuilder.appendLine("=== PASSED WAYPOINTS SUMMARY ===")
            passedWaypointData.forEach { passedInfo ->
                val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                val formattedTime = timeFormat.format(passedInfo.passedTimestamp)
                statusBuilder.appendLine("✅ ${passedInfo.waypointName}")
                statusBuilder.appendLine("  📍 Distance: ${passedInfo.passedDistance}m")
                statusBuilder.appendLine("  ⏰ Passed at: $formattedTime")
                statusBuilder.appendLine("  📊 Section: ${passedInfo.sectionIndex}")
            }
            statusBuilder.appendLine("Total passed waypoints: ${passedWaypointData.size}")
        }
        
        statusBuilder.appendLine("================================")
        
        return statusBuilder.toString()
    }
    
    /**
     * Data class for UI waypoint progress display
     */
    data class WaypointProgressInfo(
        val waypointIndex: Int,
        val waypointName: String,
        val isPassed: Boolean,
        val passedTimestamp: Long?,
        val isNext: Boolean,
        val remainingDistanceInMeters: Double?,
        val remainingTimeInSeconds: Long?,
        val trafficDelayInSeconds: Long?
    )
    
    // MQTT Methods
    private fun publishTripStartEvent() {
        try {
            tripResponse?.let { trip ->
                if (!tripStarted) {
                    // Fetch fresh trip data from database to ensure status is IN_PROGRESS
                    coroutineScope.launch {
                        try {
                            val freshTrip = databaseManager.getTripById(trip.id)
                            if (freshTrip != null) {
                                // Merge vehicle location if needed
                                val tripWithLocation = mergeVehicleLocationIfNeeded(freshTrip)
                                
                                // Update local tripResponse with fresh data
                                tripResponse = tripWithLocation
                                
                                // Send trip start event with fresh data (including updated IN_PROGRESS status)
                                routeProgressMqttService.sendTripStartEvent(tripWithLocation)?.whenComplete { result, throwable ->
                                    if (throwable != null) {
                                        Logging.e(TAG, "Failed to publish trip start event: ${throwable.message}", throwable)
                                    } else {
                                        Logging.d(TAG, "Trip start event published successfully with status: ${tripWithLocation.status}")
                                        tripStarted = true
                                    }
                                }
                            } else {
                                // Fallback to original trip data if database fetch fails
                                Logging.w(TAG, "Failed to fetch fresh trip data for trip start event, using original trip data")
                                routeProgressMqttService.sendTripStartEvent(trip)?.whenComplete { result, throwable ->
                                    if (throwable != null) {
                                        Logging.e(TAG, "Failed to publish trip start event: ${throwable.message}", throwable)
                                    } else {
                                        Logging.d(TAG, "Trip start event published successfully via dedicated MQTT service")
                                        tripStarted = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Logging.e(TAG, "Error fetching fresh trip data for trip start event: ${e.message}", e)
                            // Fallback to original trip data
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
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing trip start event: ${e.message}", e)
        }
    }
    
    private fun handleWaypointReached(waypointOrder: Int, wayCordinates: GeoCoordinates) {
        try {
            tripResponse?.let { trip ->
                val waypoint = trip.waypoints.find { it.order == waypointOrder }
                if (waypoint != null) {
                    Logging.d(TAG, "=== HANDLING WAYPOINT REACHED ===")
                    Logging.d(TAG, "Trip ID: ${trip.id}")
                    Logging.d(TAG, "Waypoint: ${waypoint.location.google_place_name}")
                    Logging.d(TAG, "lat ${waypoint.location.latitude} lon ${waypoint.location.longitude}")
                    Logging.d(TAG, "passed lat ${wayCordinates.latitude} lon ${wayCordinates.longitude}")
                    Logging.d(TAG, "Order: ${waypoint.order}")
                    
                    // Mark waypoint as passed in database
                    markWaypointAsPassedInDatabase(waypoint.id)
                    
                    // Check remaining intermediate waypoints (not including destination)
                    val remainingWaypoints = trip.waypoints.filter { !it.is_passed }
                    if (remainingWaypoints.isEmpty()) {
                        Logging.d(TAG, "🎯 Last intermediate waypoint reached - continuing to destination")
                        Logging.d(TAG, "Destination: ${trip.route.destination.custom_name}")
                    } else {
                        Logging.d(TAG, "Waypoint reached, ${remainingWaypoints.size} intermediate waypoints remaining")
                    }
                    
                    // Publish waypoint reached notification via MQTT
                    publishWaypointReachedNotification(waypoint)
                    
                    Logging.d(TAG, "=========================================")
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling waypoint reached: ${e.message}", e)
        }
    }
    
    private fun publishWaypointReachedNotification(waypoint: TripWaypoint) {
        try {
            tripResponse?.let { trip ->
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
            tripResponse?.let { trip ->
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
     * Merges vehicle location from VehicleLocationEntity into TripResponse if vehicle location is null
     */
    private suspend fun mergeVehicleLocationIfNeeded(trip: TripResponse): TripResponse {
        // If vehicle location is already set, return as-is
        if (trip.vehicle.current_latitude != null && trip.vehicle.current_longitude != null) {
            return trip
        }
        
        // Try to get location from VehicleLocationEntity
        val vehicleLocation = databaseManager.getVehicleLocation(trip.vehicle_id)
        if (vehicleLocation != null) {
            Logging.d(TAG, "Merging vehicle location from VehicleLocationEntity: ${vehicleLocation.latitude}, ${vehicleLocation.longitude}, speed=${vehicleLocation.speed}")
            return trip.copy(
                vehicle = trip.vehicle.copy(
                    current_latitude = vehicleLocation.latitude,
                    current_longitude = vehicleLocation.longitude,
                    current_speed = vehicleLocation.speed,
                    last_location_update = vehicleLocation.timestamp
                )
            )
        }
        
        Logging.d(TAG, "No vehicle location available in VehicleLocationEntity for vehicle ${trip.vehicle_id}")
        return trip
    }
    
    private fun publishFinalTripProgressUpdate() {
        coroutineScope.launch {
            try {
                tripResponse?.let { trip ->
                    val completionTimestamp = System.currentTimeMillis()
                    
                    Logging.d(TAG, "Publishing final trip progress update with completed status (DEDICATED MQTT)")
                    Logging.d(TAG, "Trip status: ${trip.status}")
                    Logging.d(TAG, "Completion timestamp: $completionTimestamp")
                    
                    // Mark trip as completed with timestamp and status in database
                    databaseManager.updateTripCompletionTimestamp(trip.id, completionTimestamp)
                    databaseManager.updateTripStatus(trip.id, "COMPLETED")
                    Logging.d(TAG, "Marked trip as completed in database with timestamp: $completionTimestamp and status: COMPLETED")
                    
                    // Fetch fresh trip data after status update
                    val freshTrip = databaseManager.getTripById(trip.id)
                    if (freshTrip != null) {
                        // Merge vehicle location if needed
                        val tripWithLocation = mergeVehicleLocationIfNeeded(freshTrip)
                        
                        // Use dedicated MQTT service for completion event
                        routeProgressMqttService.sendTripCompletionEvent(tripWithLocation)?.whenComplete { result, throwable ->
                            if (throwable != null) {
                                Logging.e(TAG, "Failed to publish final trip progress update: ${throwable.message}")
                            } else {
                                Logging.d(TAG, "Final trip progress update published successfully via dedicated MQTT service")
                            }
                        }
                    } else {
                        Logging.w(TAG, "Failed to fetch fresh trip data after completion update")
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error publishing final trip progress update: ${e.message}", e)
            }
        }
    }
    
    private fun updateWaypointDataInDatabase(
        waypointId: Int,
        waypointLengthMeters: Double,
        waypointTimeSeconds: Long
    ) {
        coroutineScope.launch {
            try {
                tripResponse?.let { trip ->
                    databaseManager.updateWaypointOriginalData(
                        tripId = trip.id,
                        waypointId = waypointId,
                        waypointLengthMeters = waypointLengthMeters,
                        waypointTimeSeconds = waypointTimeSeconds
                    )
                    Logging.d(TAG, "Updated waypoint $waypointId original data in database: length=${String.format("%.1f", waypointLengthMeters)}m, time=${formatDuration(waypointTimeSeconds)}")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update waypoint $waypointId original data in database: ${e.message}", e)
            }
        }
    }
    
    private fun markWaypointAsPassedInDatabase(waypointId: Int) {
        coroutineScope.launch {
            try {
                tripResponse?.let { trip ->
                    val currentTimestamp = System.currentTimeMillis()
                    
                    Logging.d(TAG, "=== MARKING WAYPOINT AS PASSED IN DATABASE ===")
                    Logging.d(TAG, "Trip ID: ${trip.id}")
                    Logging.d(TAG, "Waypoint ID: $waypointId")
                    Logging.d(TAG, "Current timestamp: $currentTimestamp")
                    
                    // Mark waypoint as passed with timestamp in database
                    databaseManager.updateWaypointPassedTimestamp(trip.id, waypointId, currentTimestamp)
                    Logging.d(TAG, "✅ Marked waypoint $waypointId as passed in database with timestamp: $currentTimestamp")
                    
                    // Clear is_next status for all waypoints first
                    trip.waypoints.forEach { waypoint ->
                        if (waypoint.is_next) {
                            databaseManager.updateWaypointNextStatus(trip.id, waypoint.id, false)
                            Logging.d(TAG, "Cleared is_next status for waypoint ${waypoint.id}")
                        }
                    }
                    
                    // Mark the next unpassed waypoint as is_next = true
                    val nextUnpassedWaypoint = getNextUnpassedWaypoint()
                    if (nextUnpassedWaypoint != null) {
                        databaseManager.updateWaypointNextStatus(trip.id, nextUnpassedWaypoint.id, true)
                        Logging.d(TAG, "✅ Marked next waypoint as next: ${nextUnpassedWaypoint.location.google_place_name} (ID: ${nextUnpassedWaypoint.id})")
                    } else {
                        Logging.d(TAG, "⚠️ No next unpassed waypoint found - trip might be completed")
                    }
                    
                    Logging.d(TAG, "=============================================")
                    
                    // Notify activity to update UI
                    waypointPassedCallback?.invoke()
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to mark waypoint $waypointId as passed: ${e.message}", e)
            }
        }
    }
    
    private fun getNextUnpassedWaypoint(): TripWaypoint? {
        return try {
            tripResponse?.let { trip ->
                trip.waypoints.filter { !it.is_passed }.minByOrNull { it.order }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error getting next unpassed waypoint: ${e.message}", e)
            null
        }
    }
    
    /**
     * Write current progress to database - CRITICAL: This is called FIRST before MQTT
     * This ensures database is always the single source of truth
     */
    private fun writeProgressToDatabase(sectionProgressList: List<SectionProgress>) {
        try {
            tripResponse?.let { trip ->
                Logging.d(TAG, "=== WRITING PROGRESS TO DATABASE (FIRST) ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Section progress: ${sectionProgressList.size} sections")
                
                // Get current waypoint progress data
                val waypointProgressData = getCurrentWaypointProgress()
                
                // Determine the single current next waypoint (first unpassed)
                val nextUnpassed = try { trip.waypoints.filter { !it.is_passed }.minByOrNull { it.order } } catch (e: Exception) { null }

                // Resolve progress row for the next waypoint (display-only list)
                val nextProgress = waypointProgressData.find { it.isNext }

                // Update ONLY the current next waypoint; skip origin/destination entries entirely
                if (nextUnpassed != null && nextProgress != null) {
                    Logging.d(TAG, "Updating DB for next waypoint only: order=${nextUnpassed.order}, id=${nextUnpassed.id}")

                    coroutineScope.launch {
                        try {
                            // Set is_next flags
                            trip.waypoints.forEach { wp ->
                                val shouldBeNext = wp.id == nextUnpassed.id
                                if (wp.is_next != shouldBeNext) {
                                    databaseManager.updateWaypointNextStatus(trip.id, wp.id, shouldBeNext)
                                }
                            }

                            // Set remaining time/distance for next waypoint if available
                            if (nextProgress.remainingTimeInSeconds != null && nextProgress.remainingDistanceInMeters != null) {
                                databaseManager.updateWaypointRemaining(
                                    tripId = trip.id,
                                    waypointId = nextUnpassed.id,
                                    remainingTimeSeconds = nextProgress.remainingTimeInSeconds,
                                    remainingDistanceMeters = nextProgress.remainingDistanceInMeters
                                )
                                Logging.d(TAG, "  - ✅ Updated remaining for next waypoint id=${nextUnpassed.id}: ${nextProgress.remainingTimeInSeconds}s, ${nextProgress.remainingDistanceInMeters}m")
                            } else {
                                Logging.w(TAG, "  - ⚠️ Next waypoint has no remaining data; skipping remaining update")
                            }

                            // IMPORTANT: Do NOT mark is_passed here. Passing is handled by milestone/section-reduction/<=10m rule elsewhere.
                        } catch (e: Exception) {
                            Logging.e(TAG, "Failed to update next waypoint ${nextUnpassed.id}: ${e.message}", e)
                        }
                    }
                } else {
                    Logging.d(TAG, "No next unpassed waypoint to update or no progress row for it; skipping waypoint DB writes")
                }

                // Update trip-level remaining (destination progress) using last section
                val lastSection = sectionProgressList.lastOrNull()
                if (lastSection != null) {
                    coroutineScope.launch {
                        try {
                            databaseManager.updateTripRemaining(
                                tripId = trip.id,
                                remainingTimeToDestination = lastSection.remainingDuration.toSeconds(),
                                remainingDistanceToDestination = lastSection.remainingDistanceInMeters.toDouble()
                            )
                        } catch (e: Exception) {
                            Logging.e(TAG, "Failed to persist trip remaining progress: ${e.message}", e)
                        }
                    }
                }
                
                Logging.d(TAG, "✅ All progress written to database - database is now the source of truth")
                
                // Update vehicle location in database with latest location data
                if (latestLat != null && latestLng != null && latestSpeed != null && latestAccuracy != null) {
                    coroutineScope.launch {
                        try {
                            databaseManager.updateVehicleCurrentLocation(
                                vehicleId = trip.vehicle_id,
                                latitude = latestLat!!,
                                longitude = latestLng!!,
                                speed = latestSpeed!!,
                                accuracy = latestAccuracy!!,
                                bearing = latestBearing
                            )
                            Logging.d(TAG, "✅ Vehicle location updated in database")
                        } catch (e: Exception) {
                            Logging.e(TAG, "Failed to update vehicle location: ${e.message}", e)
                        }
                    }
                } else {
                    Logging.d(TAG, "⚠️ No location data available to update")
                }
                
                Logging.d(TAG, "=============================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error writing progress to database: ${e.message}", e)
        }
    }
    
    /**
     * Update waypoint progress in database with current section data
     * DEPRECATED: Use writeProgressToDatabase() instead
     */
    private fun updateWaypointProgressInDatabase(waypointProgressData: List<WaypointProgressInfo>) {
        try {
            tripResponse?.let { trip ->
                Logging.d(TAG, "=== UPDATING WAYPOINT PROGRESS IN DATABASE ===")
                Logging.d(TAG, "Trip ID: ${trip.id}")
                Logging.d(TAG, "Waypoint progress data: ${waypointProgressData.size} entries")
                
                waypointProgressData.forEach { progressInfo ->
                    val waypoint = trip.waypoints.find { it.order == progressInfo.waypointIndex }
                    if (waypoint != null) {
                        Logging.d(TAG, "Updating waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                        Logging.d(TAG, "  - Is passed: ${progressInfo.isPassed}")
                        Logging.d(TAG, "  - Is next: ${progressInfo.isNext}")
                        Logging.d(TAG, "  - Remaining time: ${progressInfo.remainingTimeInSeconds?.let { formatDuration(it) } ?: "null"}")
                        Logging.d(TAG, "  - Remaining distance: ${progressInfo.remainingDistanceInMeters?.let { String.format("%.1f", it) } ?: "null"}m")
                        
                        // Update waypoint in database
                        coroutineScope.launch {
                            try {
                                // Update waypoint status (is_passed)
                                if (waypoint.is_passed != progressInfo.isPassed) {
                                    databaseManager.updateWaypointStatus(trip.id, waypoint.id, progressInfo.isPassed)
                                    Logging.d(TAG, "  - Updated is_passed status: ${progressInfo.isPassed}")
                                }
                                
                                // Update waypoint next status (is_next)
                                if (waypoint.is_next != progressInfo.isNext) {
                                    databaseManager.updateWaypointNextStatus(trip.id, waypoint.id, progressInfo.isNext)
                                    Logging.d(TAG, "  - Updated is_next status: ${progressInfo.isNext}")
                                }
                                
                                // Update remaining time and distance for the next waypoint
                                if (progressInfo.isNext && progressInfo.remainingTimeInSeconds != null && progressInfo.remainingDistanceInMeters != null) {
                                    databaseManager.updateWaypointRemaining(
                                        tripId = trip.id,
                                        waypointId = waypoint.id,
                                        remainingTimeSeconds = progressInfo.remainingTimeInSeconds,
                                        remainingDistanceMeters = progressInfo.remainingDistanceInMeters
                                    )
                                    Logging.d(TAG, "  - Updated remaining time/distance for next waypoint")
                                } else if (!progressInfo.isNext) {
                                    // Clear remaining time/distance for non-next waypoints
                                    databaseManager.updateWaypointRemaining(
                                        tripId = trip.id,
                                        waypointId = waypoint.id,
                                        remainingTimeSeconds = null,
                                        remainingDistanceMeters = null
                                    )
                                    Logging.d(TAG, "  - Cleared remaining time/distance for non-next waypoint")
                                }
                                
                                // Update passed timestamp if waypoint was just marked as passed
                                if (progressInfo.isPassed && progressInfo.passedTimestamp != null && waypoint.passed_timestamp == null) {
                                    databaseManager.updateWaypointPassedTimestamp(trip.id, waypoint.id, progressInfo.passedTimestamp)
                                    Logging.d(TAG, "  - Updated passed timestamp: ${progressInfo.passedTimestamp}")
                                }
                                
                            } catch (e: Exception) {
                                Logging.e(TAG, "Failed to update waypoint ${waypoint.id} in database: ${e.message}", e)
                            }
                        }
                    } else {
                        Logging.w(TAG, "Waypoint with order ${progressInfo.waypointIndex} not found in trip data")
                    }
                }
                
                Logging.d(TAG, "=============================================")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error updating waypoint progress in database: ${e.message}", e)
        }
    }
    
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
     * Publish trip progress update immediately (bypass throttling)
     * Used when network is restored during navigation to immediately sync state
     * IMPORTANT: This method ONLY reads from database - no direct data setting in MQTT messages
     */
    fun publishTripProgressUpdateImmediate() {
        try {
            Logging.d(TAG, "=== PUBLISH TRIP PROGRESS UPDATE IMMEDIATE (NETWORK RESTORE) ===")
            val currentTime = System.currentTimeMillis()
            
            // Force update by temporarily resetting last update time
            val originalLastUpdateTime = lastProgressUpdateTime
            lastProgressUpdateTime = 0L
            
            // Call the regular publish method which will now send immediately
            publishTripProgressUpdate()
            
            // Restore original time to prevent too frequent updates after this
            lastProgressUpdateTime = originalLastUpdateTime
            
            Logging.d(TAG, "=== IMMEDIATE TRIP PROGRESS UPDATE COMPLETE ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error publishing immediate trip progress update: ${e.message}", e)
        }
    }
    
    /**
     * Publish periodic trip progress update via MQTT
     * IMPORTANT: This method ONLY reads from database - no direct data setting in MQTT messages
     */
    private fun publishTripProgressUpdate() {
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
                
                // CRITICAL: Always fetch fresh trip data from database - NO direct data setting in MQTT
                coroutineScope.launch {
                    try {
                        tripResponse?.let { currentTrip ->
                            Logging.d(TAG, "🔄 FETCHING FRESH TRIP DATA FROM DATABASE")
                            val freshTrip = databaseManager.getTripById(currentTrip.id)
                            if (freshTrip != null) {
                                Logging.d(TAG, "✅ Fetched fresh trip data from database")
                                
                                // Update in-memory trip data
                                tripResponse = freshTrip
                                
                                // CRITICAL: MQTT message uses ONLY database data - no calculated data
                                Logging.d(TAG, "📤 SENDING MQTT MESSAGE WITH DATABASE DATA ONLY")
                                Logging.d(TAG, "Trip ID: ${freshTrip.id}")
                                Logging.d(TAG, "Waypoints from database:")
                                freshTrip.waypoints.forEach { waypoint ->
                                    Logging.d(TAG, "  Waypoint ${waypoint.order} (${waypoint.location.google_place_name}):")
                                    Logging.d(TAG, "    - is_passed: ${waypoint.is_passed}")
                                    Logging.d(TAG, "    - is_next: ${waypoint.is_next}")
                                    Logging.d(TAG, "    - remaining_time: ${waypoint.remaining_time?.let { formatDuration(it) } ?: "null"}")
                                    Logging.d(TAG, "    - remaining_distance: ${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
                                }
                                
                                // Determine trip-level data based on database waypoint status
                                val unpassedWaypoints = freshTrip.waypoints.filter { !it.is_passed }
                                val tripLevelTime: Long?
                                val tripLevelDistance: Double?
                                
                                Logging.d(TAG, "=== TRIP LEVEL DATA DETERMINATION ===")
                                Logging.d(TAG, "Total waypoints: ${freshTrip.waypoints.size}")
                                Logging.d(TAG, "Unpassed waypoints: ${unpassedWaypoints.size}")
                                freshTrip.waypoints.forEach { waypoint ->
                                    Logging.d(TAG, "  Waypoint ${waypoint.order} (${waypoint.location.google_place_name}): is_passed=${waypoint.is_passed}")
                                }
                                
                                if (unpassedWaypoints.isEmpty()) {
                                    // All waypoints passed - check if we're at final destination
                                    val currentSectionProgress = currentSectionProgress
                                    if (currentSectionProgress.isNotEmpty()) {
                                        // Still have sections - not at final destination yet
                                        val lastSection = currentSectionProgress.last()
                                        tripLevelTime = lastSection.remainingDuration.toSeconds()
                                        tripLevelDistance = lastSection.remainingDistanceInMeters.toDouble()
                                        Logging.d(TAG, "All waypoints passed but still navigating to final destination: ${tripLevelTime}s, ${tripLevelDistance}m")
                                    } else {
                                        // No sections left - truly completed
                                        tripLevelTime = 0L
                                        tripLevelDistance = 0.0
                                        Logging.d(TAG, "Trip truly completed - no sections remaining: ${tripLevelTime}s, ${tripLevelDistance}m")
                                    }
                                } else {
                                    // Still have waypoints to pass - trip-level data should be null
                                    tripLevelTime = null
                                    tripLevelDistance = null
                                    Logging.d(TAG, "Still have ${unpassedWaypoints.size} waypoints to pass - trip-level data is null")
                                }
                                
                                Logging.d(TAG, "=== PUBLISHING TRIP PROGRESS UPDATE (DATABASE DATA ONLY) ===")
                                Logging.d(TAG, "Trip-level time: ${tripLevelTime?.let { formatDuration(it) } ?: "null"}")
                                Logging.d(TAG, "Trip-level distance: ${tripLevelDistance?.let { String.format("%.1f", it) } ?: "null"}m")
                                Logging.d(TAG, "Unpassed waypoints: ${unpassedWaypoints.size}")
                                Logging.d(TAG, "Next waypoint: ${unpassedWaypoints.minByOrNull { it.order }?.location?.google_place_name ?: "None"}")
                                Logging.d(TAG, "=====================================")
                                
                                // Merge vehicle location from VehicleLocationEntity if needed
                                val tripWithLocation = mergeVehicleLocationIfNeeded(freshTrip)
                                
                                // Extract vehicle location data from trip (with merged location if needed)
                                val currentSpeed = tripWithLocation.vehicle.current_speed
                                val currentLocation = if (tripWithLocation.vehicle.current_latitude != null && 
                                          tripWithLocation.vehicle.current_longitude != null) {
                                    MqttService.Location(
                                        latitude = tripWithLocation.vehicle.current_latitude!!,
                                        longitude = tripWithLocation.vehicle.current_longitude!!
                                    )
                                } else {
                                    null
                                }
                                
                                // CRITICAL: Use ONLY database trip data (with merged location if needed) - no calculated data
                                routeProgressMqttService.sendTripProgressUpdate(
                                    tripResponse = tripWithLocation, // Use fresh database data with merged location
                                    remainingTimeToDestination = tripLevelTime,
                                    remainingDistanceToDestination = tripLevelDistance,
                                    currentSpeed = currentSpeed,
                                    currentLocation = currentLocation
                                )?.whenComplete { result, throwable ->
                                    if (throwable != null) {
                                        Logging.e(TAG, "Failed to publish trip progress update: ${throwable.message}", throwable)
                                    } else {
                                        Logging.d(TAG, "✅ Trip progress update published successfully via dedicated MQTT service")
                                        lastProgressUpdateTime = currentTime
                                    }
                                }
                            } else {
                                Logging.e(TAG, "❌ Failed to fetch fresh trip data from database - trip may have been deleted")
                                
                                // Trip was deleted - reset validator and notify service/activity
                                val deletedTripId = currentTrip.id
                                Logging.w(TAG, "Trip $deletedTripId not found in database - resetting validator and notifying service")
                                
                                // Reset validator state
                                reset()
                                
                                // Notify service/activity that trip was deleted
                                tripDeletedCallback?.invoke(deletedTripId)
                            }
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
     * DEPRECATED: No longer used - MQTT now uses database data directly
     * This method was used to merge calculated data with database data,
     * but now MQTT only reads from database to ensure consistency
     */
    @Deprecated("Use database data directly in MQTT messages")
    private fun createTripDataWithCalculatedWaypoints(
        tripData: TripResponse,
        calculatedWaypointData: List<WaypointProgressInfo>
    ): TripResponse {
        // This method is no longer used - MQTT uses database data directly
        return tripData
    }
}
