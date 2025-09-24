package com.gocavgo.validator.trip

import android.util.Log
import com.gocavgo.validator.Navigator
import com.gocavgo.validator.dataclass.WaypointProgress

/**
 * Example usage of TripProgressTracker functionality
 * This demonstrates how to monitor trip progress and get updates
 */
class TripProgressExample {
    companion object {
        private const val TAG = "TripProgressExample"
        
        /**
         * Example of how to get current trip progress from Navigator
         */
        fun demonstrateProgressTracking(navigator: Navigator) {
            Log.d(TAG, "=== DEMONSTRATING TRIP PROGRESS TRACKING ===")
            
            // Get current progress for all waypoints
            val currentProgress = navigator.getCurrentTripProgress()
            if (currentProgress != null) {
                Log.d(TAG, "Current waypoint progress:")
                currentProgress.forEach { (waypointId, progress) ->
                    Log.d(TAG, "  Waypoint ${progress.locationName}:")
                    Log.d(TAG, "    - Distance remaining: ${String.format("%.1f", progress.remainingDistanceInMeters)}m")
                    Log.d(TAG, "    - Time remaining: ${progress.remainingTimeInSeconds}s")
                    Log.d(TAG, "    - Traffic delay: ${progress.trafficDelayInSeconds}s")
                    Log.d(TAG, "    - Is next: ${progress.isNext}")
                }
            } else {
                Log.d(TAG, "No progress data available yet")
            }
            
            // Get list of reached waypoints
            val reachedWaypoints = navigator.getReachedWaypoints()
            if (reachedWaypoints != null) {
                Log.d(TAG, "Reached waypoints: ${reachedWaypoints.size}")
                reachedWaypoints.forEach { waypointIndex ->
                    Log.d(TAG, "  - Waypoint index: $waypointIndex")
                }
            }
            
            // Get comprehensive trip summary
            val summary = navigator.getTripProgressSummary()
            Log.d(TAG, "Trip Summary:\n$summary")
            
            Log.d(TAG, "=========================================")
        }
        
        /**
         * Example of how to monitor specific waypoint progress
         */
        fun monitorSpecificWaypoint(navigator: Navigator, waypointId: Int) {
            val progress = navigator.getCurrentTripProgress()?.get(waypointId)
            if (progress != null) {
                Log.d(TAG, "=== MONITORING WAYPOINT $waypointId ===")
                Log.d(TAG, "Location: ${progress.locationName}")
                Log.d(TAG, "Order: ${progress.order}")
                Log.d(TAG, "Distance remaining: ${String.format("%.1f", progress.remainingDistanceInMeters)}m")
                Log.d(TAG, "Time remaining: ${progress.remainingTimeInSeconds}s")
                Log.d(TAG, "Traffic delay: ${progress.trafficDelayInSeconds}s")
                Log.d(TAG, "Is next: ${progress.isNext}")
                Log.d(TAG, "Last updated: ${progress.timestamp}")
                Log.d(TAG, "================================")
            } else {
                Log.d(TAG, "No progress data available for waypoint $waypointId")
            }
        }
        
        /**
         * Example of how to check if approaching a waypoint
         */
        fun checkWaypointApproach(navigator: Navigator, waypointId: Int): Boolean {
            val progress = navigator.getCurrentTripProgress()?.get(waypointId)
            return progress?.let {
                // Check if within 100 meters of waypoint
                it.remainingDistanceInMeters < 100.0
            } ?: false
        }
        
        /**
         * Example of how to get ETA for a specific waypoint
         */
        fun getWaypointETA(navigator: Navigator, waypointId: Int): Long? {
            return navigator.getCurrentTripProgress()?.get(waypointId)?.remainingTimeInSeconds
        }
        
        /**
         * Example of how to check overall trip completion
         */
        fun getTripCompletionPercentage(navigator: Navigator): Double {
            val reached = navigator.getReachedWaypoints()?.size ?: 0
            val total = navigator.getCurrentTripProgress()?.size ?: 0
            
            return if (total > 0) {
                (reached.toDouble() / total) * 100.0
            } else {
                0.0
            }
        }
    }
}
