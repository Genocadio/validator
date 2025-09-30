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

import android.util.Log
import com.gocavgo.validator.dataclass.TripResponse
import com.here.sdk.routing.Route
import com.here.sdk.routing.Section
import com.here.sdk.navigation.SectionProgress

/**
 * Validates route sections against trip data and maps section progress to waypoint information.
 * 
 * This class ensures that:
 * 1. Route has n-1 sections for n total locations (origin + waypoints + destination)
 * 2. Maps section progress to appropriate waypoint names and locations
 * 3. Tracks waypoint completion with timestamps as sections are reduced
 * 4. Records passed waypoints with distance-based detection (10m threshold)
 */
class TripSectionValidator {
    
    private var isVerified = false
    private var tripResponse: TripResponse? = null
    private var route: Route? = null
    private var waypointNames: List<String> = emptyList()
    private var passedWaypoints: MutableSet<Int> = mutableSetOf()
    private var passedWaypointData: MutableList<PassedWaypointInfo> = mutableListOf()
    private var currentSectionProgress: List<SectionProgress> = emptyList()
    private var lastProcessedSectionCount: Int = -1
    private var lastWaypointMarkedByDistance: Int = -1
    
    companion object {
        private const val TAG = "TripSectionValidator"
        private const val WAYPOINT_REACHED_THRESHOLD_METERS = 10.0
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
     * Verifies if the route sections match the trip data structure.
     * 
     * @param tripResponse The trip data containing waypoints
     * @param route The calculated route with sections
     * @return true if verification passes, false otherwise
     */
    fun verifyRouteSections(tripResponse: TripResponse, route: Route): Boolean {
        Log.d(TAG, "=== STARTING ROUTE SECTION VERIFICATION ===")
        
        this.tripResponse = tripResponse
        this.route = route
        
        // Calculate expected sections: n-1 sections for n total locations
        val totalLocations = 1 + tripResponse.waypoints.size + 1 // origin + waypoints + destination
        val expectedSections = totalLocations - 1
        val actualSections = route.sections.size
        
        Log.d(TAG, "Trip ID: ${tripResponse.id}")
        Log.d(TAG, "Total locations: $totalLocations (origin + ${tripResponse.waypoints.size} waypoints + destination)")
        Log.d(TAG, "Expected sections: $expectedSections")
        Log.d(TAG, "Actual sections: $actualSections")
        
        // Build waypoint names list for mapping
        waypointNames = buildWaypointNamesList(tripResponse)
        Log.d(TAG, "Waypoint names: $waypointNames")
        
        // Verify section count
        val verificationPassed = actualSections == expectedSections
        
        if (verificationPassed) {
            Log.d(TAG, "✅ VERIFICATION PASSED: Route sections match trip structure")
            isVerified = true
            passedWaypoints.clear() // Reset passed waypoints
            passedWaypointData.clear() // Reset passed waypoint data
            lastProcessedSectionCount = -1 // Reset section count tracking
            lastWaypointMarkedByDistance = -1 // Reset distance marking tracking
            
            // Mark origin as passed at trip start (verification time)
            markOriginAsPassed()
            
            // Log initial section details
            logSectionDetails(route.sections)
        } else {
            Log.e(TAG, "❌ VERIFICATION FAILED: Expected $expectedSections sections, got $actualSections")
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
        if (!isVerified) {
            Log.w(TAG, "Trip not verified, skipping section progress processing")
            return
        }
        
        Log.d(TAG, "=== PROCESSING SECTION PROGRESS ===")
        Log.d(TAG, "Current sections: ${sectionProgressList.size}")
        Log.d(TAG, "Total sections: $totalSections")
        
        // Store current section progress for UI access
        currentSectionProgress = sectionProgressList
        
        // Check for distance-based waypoint passing (10m threshold)
        checkDistanceBasedWaypointPassing(sectionProgressList)
        
        // Check for waypoint completion (section reduction) - only as fallback
        val currentSectionCount = sectionProgressList.size
        val expectedSectionCount = waypointNames.size - 1
        
        // Only process section reduction if the count actually changed
        if (currentSectionCount < expectedSectionCount && currentSectionCount != lastProcessedSectionCount) {
            // Calculate which waypoint was completed by the section reduction
            // When sections reduce from 3 to 2, it means 1 waypoint was completed
            // The completed waypoint is the one that was just reached
            val completedWaypointIndex = expectedSectionCount - currentSectionCount
            
            Log.d(TAG, "Section reduction check:")
            Log.d(TAG, "  Expected sections: $expectedSectionCount")
            Log.d(TAG, "  Current sections: $currentSectionCount")
            Log.d(TAG, "  Completed waypoint index: $completedWaypointIndex")
            Log.d(TAG, "  Last waypoint marked by distance: $lastWaypointMarkedByDistance")
            Log.d(TAG, "  Passed waypoints: $passedWaypoints")
            
            if (completedWaypointIndex == lastWaypointMarkedByDistance) {
                Log.d(TAG, "Waypoint $completedWaypointIndex already marked by distance-based detection, skipping section reduction")
                Log.d(TAG, "This is normal - sections reduce with delay after distance-based waypoint passing")
            } else {
                // No waypoints were marked by distance, so use section reduction as fallback
                Log.d(TAG, "Using section reduction as fallback for waypoint $completedWaypointIndex")
                handleWaypointCompletion(currentSectionCount, expectedSectionCount)
            }
            lastProcessedSectionCount = currentSectionCount
        }
        
        // Check for trip completion (no more sections)
        if (currentSectionCount == 0 && expectedSectionCount > 0) {
            handleTripCompletion()
        }
        
        // Map current sections to waypoint information
        mapSectionsToWaypoints(sectionProgressList)
        
        // Log passed waypoints summary
        logPassedWaypointsSummary()
        
        Log.d(TAG, "=== SECTION PROGRESS PROCESSING COMPLETE ===")
    }
    
    /**
     * Marks the origin as passed at trip start time
     */
    private fun markOriginAsPassed() {
        val currentTime = System.currentTimeMillis()
        val originName = waypointNames[0] // Origin is always at index 0
        
        // Mark origin as passed
        passedWaypoints.add(0)
        
        // Record origin as passed
        val passedInfo = PassedWaypointInfo(
            waypointIndex = 1, // Origin is waypoint 1
            waypointName = originName,
            passedTimestamp = currentTime,
            passedDistance = 0.0, // Origin is at start, so distance is 0
            sectionIndex = -1 // No section for origin
        )
        passedWaypointData.add(passedInfo)
        
        Log.d(TAG, "🏁 ORIGIN MARKED AS PASSED: $originName")
        Log.d(TAG, "  ⏰ Trip started at: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
    }
    
    /**
     * Builds a list of waypoint names in order (origin, waypoints, destination)
     */
    private fun buildWaypointNamesList(tripResponse: TripResponse): List<String> {
        val names = mutableListOf<String>()
        
        // Add origin
        names.add("Origin: ${tripResponse.route.origin.custom_name}")
        
        // Add intermediate waypoints sorted by order
        val sortedWaypoints = tripResponse.waypoints.sortedBy { it.order }
        sortedWaypoints.forEach { waypoint ->
            names.add("Waypoint ${waypoint.order}: ${waypoint.location.custom_name}")
        }
        
        // Add destination
        names.add("Destination: ${tripResponse.route.destination.custom_name}")
        
        return names
    }
    
    /**
     * Logs detailed information about route sections
     */
    private fun logSectionDetails(sections: List<Section>) {
        Log.d(TAG, "=== ROUTE SECTION DETAILS ===")
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
     * Checks for distance-based waypoint passing (10m threshold)
     */
    private fun checkDistanceBasedWaypointPassing(sectionProgressList: List<SectionProgress>) {
        sectionProgressList.forEachIndexed { sectionIndex, sectionProgress ->
            // Calculate the target waypoint index for this section
            // Section 0 = to first unpassed waypoint, Section 1 = to second unpassed waypoint, etc.
            val targetWaypointIndex = passedWaypoints.size + sectionIndex
            
            // Skip processing if this waypoint is already passed
            if (passedWaypoints.contains(targetWaypointIndex)) {
                Log.d(TAG, "Skipping distance check for already-passed waypoint at index $targetWaypointIndex")
                return@forEachIndexed
            }
            
            // Check if we're within 10m of the next waypoint
            if (sectionProgress.remainingDistanceInMeters < WAYPOINT_REACHED_THRESHOLD_METERS) {
                if (targetWaypointIndex < waypointNames.size) {
                    // Mark waypoint as passed
                    passedWaypoints.add(targetWaypointIndex)
                    val waypointName = waypointNames[targetWaypointIndex]
                    val currentTime = System.currentTimeMillis()
                    
                    // Record passed waypoint data
                    val passedInfo = PassedWaypointInfo(
                        waypointIndex = targetWaypointIndex + 1, // +1 to match waypoint order (1-based)
                        waypointName = waypointName,
                        passedTimestamp = currentTime,
                        passedDistance = sectionProgress.remainingDistanceInMeters.toDouble(),
                        sectionIndex = sectionIndex
                    )
                    passedWaypointData.add(passedInfo)
                    
                    // Track the last waypoint marked by distance
                    lastWaypointMarkedByDistance = targetWaypointIndex
                    
                    Log.d(TAG, "🎯 WAYPOINT PASSED (Distance-based): $waypointName")
                    Log.d(TAG, "  📍 Distance: ${sectionProgress.remainingDistanceInMeters}m")
                    Log.d(TAG, "  ⏰ Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
                    Log.d(TAG, "  📊 Section: $sectionIndex")
                    Log.d(TAG, "  📊 Target waypoint index: $targetWaypointIndex")
                    Log.d(TAG, "  📊 Set lastWaypointMarkedByDistance to: $lastWaypointMarkedByDistance")
                }
            }
        }
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
            
            // Record passed waypoint data for section reduction
            val passedInfo = PassedWaypointInfo(
                waypointIndex = completedWaypointIndex + 1, // +1 to match waypoint order (1-based)
                waypointName = waypointName,
                passedTimestamp = currentTime,
                passedDistance = 0.0, // Section reduced, so distance is 0
                sectionIndex = completedWaypointIndex - 1 // Previous section index
            )
            passedWaypointData.add(passedInfo)
            
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
                
                // Record passed waypoint data for trip completion
                val passedInfo = PassedWaypointInfo(
                    waypointIndex = i + 1, // +1 to match waypoint order (1-based)
                    waypointName = waypointName,
                    passedTimestamp = currentTime,
                    passedDistance = 0.0, // Trip completed, so distance is 0
                    sectionIndex = -1 // No section for trip completion
                )
                passedWaypointData.add(passedInfo)
                
                Log.d(TAG, "🎯 WAYPOINT COMPLETED (Trip completion): $waypointName")
                Log.d(TAG, "  ⏰ Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(currentTime)}")
                Log.d(TAG, "  📊 Waypoint index: $i")
            }
        }
        
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
            
            // Check if approaching waypoint
            if (sectionProgress.remainingDistanceInMeters < WAYPOINT_REACHED_THRESHOLD_METERS) {
                Log.d(TAG, "  🚨 APPROACHING WAYPOINT: $toWaypoint")
            }
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
     * Resets the validator state
     */
    fun reset() {
        isVerified = false
        tripResponse = null
        route = null
        waypointNames = emptyList()
        passedWaypoints.clear()
        passedWaypointData.clear()
        currentSectionProgress = emptyList()
        lastProcessedSectionCount = -1
        lastWaypointMarkedByDistance = -1
        Log.d(TAG, "Validator state reset")
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
        
        waypointNames.forEachIndexed { index, waypointName ->
            val isPassed = passedWaypoints.contains(index)
            val passedInfo = passedWaypointData.find { it.waypointIndex == index + 1 }
            val isNext = !isPassed && index == passedWaypoints.size
            
            // Calculate remaining time and distance for unpassed waypoints
            var remainingDistance: Double? = null
            var remainingTime: Long? = null
            var trafficDelay: Long? = null
            
            if (!isPassed && currentSectionProgress.isNotEmpty()) {
                // Calculate section index based on how many waypoints have been passed before this one
                // Section 0 = to first unpassed waypoint, Section 1 = to second unpassed waypoint, etc.
                val sectionIndex = index - passedWaypoints.size
                if (sectionIndex >= 0 && sectionIndex < currentSectionProgress.size) {
                    val sectionProgress = currentSectionProgress[sectionIndex]
                    remainingDistance = sectionProgress.remainingDistanceInMeters.toDouble()
                    remainingTime = sectionProgress.remainingDuration.toSeconds()
                    trafficDelay = sectionProgress.trafficDelay.seconds
                }
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
        
        return progressList
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
}
