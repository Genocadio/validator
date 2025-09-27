package com.gocavgo.validator.service

import android.util.Log
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/**
 * Dedicated MQTT service for RouteProgressTracker
 * This service handles MQTT communication specifically for route progress updates,
 * completely independent of TripProgressTracker to avoid conflicts.
 */
class RouteProgressMqttService private constructor() {
    
    companion object {
        private const val TAG = "RouteProgressMqttService"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 20000L // 20 seconds
        
        @Volatile
        private var INSTANCE: RouteProgressMqttService? = null
        
        fun getInstance(): RouteProgressMqttService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RouteProgressMqttService().also { INSTANCE = it }
            }
        }
    }
    
    private var mqttService: MqttService? = null
    private var lastUpdateTime = 0L
    
    /**
     * Initialize the service with the main MQTT service
     */
    fun initialize(mqttService: MqttService) {
        this.mqttService = mqttService
        Logging.d(TAG, "RouteProgressMqttService initialized")
    }
    
    /**
     * Send trip progress update via MQTT
     * This method is specifically designed for RouteProgressTracker and uses
     * the correct section-based calculations
     */
    fun sendTripProgressUpdate(
        tripResponse: TripResponse,
        remainingTimeToDestination: Long?,
        remainingDistanceToDestination: Double?,
        currentSpeed: Double? = null,
        currentLocation: MqttService.Location? = null
    ): CompletableFuture<*>? {
        return try {
            val currentTime = System.currentTimeMillis()
            
            // Check if enough time has passed since last update
            if (currentTime - lastUpdateTime < PROGRESS_UPDATE_INTERVAL_MS) {
                Logging.d(TAG, "Skipping MQTT update - interval not reached (${(currentTime - lastUpdateTime)}ms < ${PROGRESS_UPDATE_INTERVAL_MS}ms)")
                return CompletableFuture.completedFuture(null)
            }
            
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    Logging.d(TAG, "=== SENDING ROUTE PROGRESS MQTT UPDATE ===")
                    Logging.d(TAG, "Trip ID: ${tripResponse.id}")
                    Logging.d(TAG, "Remaining time to destination: ${remainingTimeToDestination?.let { formatDuration(it) } ?: "Unknown"} (${remainingTimeToDestination} seconds)")
                    Logging.d(TAG, "Remaining distance to destination: ${remainingDistanceToDestination?.let { String.format("%.1f", it) } ?: "Unknown"}m (${remainingDistanceToDestination} meters)")
                    Logging.d(TAG, "Current speed: ${currentSpeed?.let { String.format("%.2f", it) } ?: "Unknown"} m/s")
                    Logging.d(TAG, "Current location: ${currentLocation?.let { "${it.latitude}, ${it.longitude}" } ?: "Unknown"}")
                    
                    // Log waypoint data for verification
                    Logging.d(TAG, "Waypoint data:")
                    tripResponse.waypoints.filter { !it.is_passed }.forEach { wp ->
                        Logging.d(TAG, "  Waypoint ${wp.order} (${wp.location.google_place_name}): ${wp.remaining_time}s, ${wp.remaining_distance}m")
                    }
                    Logging.d(TAG, "=========================================")
                    
                    // Log trip data BEFORE MQTT conversion
                    Logging.d(TAG, "=== TRIP DATA BEFORE MQTT CONVERSION ===")
                    Logging.d(TAG, "Trip ID: ${tripResponse.id}")
                    Logging.d(TAG, "Trip Status: ${tripResponse.status}")
                    Logging.d(TAG, "Trip remaining_time_to_destination: ${tripResponse.remaining_time_to_destination}")
                    Logging.d(TAG, "Trip remaining_distance_to_destination: ${tripResponse.remaining_distance_to_destination}")
                    Logging.d(TAG, "Live remainingTimeToDestination: $remainingTimeToDestination")
                    Logging.d(TAG, "Live remainingDistanceToDestination: $remainingDistanceToDestination")
                    Logging.d(TAG, "Current Speed: $currentSpeed")
                    Logging.d(TAG, "Current Location: $currentLocation")
                    Logging.d(TAG, "Total Waypoints: ${tripResponse.waypoints.size}")
                    Logging.d(TAG, "Unpassed Waypoints: ${tripResponse.waypoints.count { !it.is_passed }}")
                    Logging.d(TAG, "Next Waypoint: ${tripResponse.waypoints.find { it.is_next }?.location?.google_place_name ?: "None"}")
                    Logging.d(TAG, "=========================================")
                    
                    // Convert trip data using the main MQTT service's conversion method
                    val tripData = mqtt.convertTripResponseToTripData(
                        tripResponse = tripResponse,
                        currentSpeed = currentSpeed,
                        currentLocation = currentLocation,
                        remainingTimeToDestination = remainingTimeToDestination,
                        remainingDistanceToDestination = remainingDistanceToDestination
                    )
                    
                    // Log trip data AFTER MQTT conversion
                    Logging.d(TAG, "=== TRIP DATA AFTER MQTT CONVERSION ===")
                    Logging.d(TAG, "Trip ID: ${tripData.id}")
                    Logging.d(TAG, "Trip Status: ${tripData.status}")
                    Logging.d(TAG, "Trip remaining_time_to_destination: ${tripData.remaining_time_to_destination}")
                    Logging.d(TAG, "Trip remaining_distance_to_destination: ${tripData.remaining_distance_to_destination}")
                    Logging.d(TAG, "Current Speed: ${tripData.current_speed}")
                    Logging.d(TAG, "Total Waypoints: ${tripData.waypoints.size}")
                    Logging.d(TAG, "Unpassed Waypoints: ${tripData.waypoints.count { !it.is_passed }}")
                    Logging.d(TAG, "Next Waypoint: ${tripData.waypoints.find { it.is_next }?.location?.google_place_name ?: "None"}")
                    
                    // Log detailed waypoint data after conversion
                    Logging.d(TAG, "Waypoint details after conversion:")
                    tripData.waypoints.filter { !it.is_passed }.forEach { wp ->
                        Logging.d(TAG, "  Waypoint ${wp.order} (${wp.location.google_place_name}):")
                        Logging.d(TAG, "    ID: ${wp.id}")
                        Logging.d(TAG, "    Is Next: ${wp.is_next}")
                        Logging.d(TAG, "    Remaining Time: ${wp.remaining_time}s")
                        Logging.d(TAG, "    Remaining Distance: ${wp.remaining_distance}m")
                    }
                    Logging.d(TAG, "=========================================")
                    
                    // Log the MQTT message being sent
                    Logging.d(TAG, "=== SENDING MQTT MESSAGE ===")
                    Logging.d(TAG, "Event: progress_update")
                    Logging.d(TAG, "Trip Data Summary:")
                    Logging.d(TAG, "  - Trip ID: ${tripData.id}")
                    Logging.d(TAG, "  - Status: ${tripData.status}")
                    Logging.d(TAG, "  - Remaining Time: ${tripData.remaining_time_to_destination}s")
                    Logging.d(TAG, "  - Remaining Distance: ${tripData.remaining_distance_to_destination}m")
                    Logging.d(TAG, "  - Current Speed: ${tripData.current_speed} m/s")
                    Logging.d(TAG, "  - Waypoints: ${tripData.waypoints.size} total, ${tripData.waypoints.count { !it.is_passed }} unpassed")
                    Logging.d(TAG, "==========================")
                    
                    // Send the MQTT message
                    mqtt.sendTripEventMessage(
                        event = "progress_update",
                        tripData = tripData
                    )?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "=== MQTT SEND FAILED ===")
                            Logging.e(TAG, "Error: ${throwable.message}", throwable)
                            Logging.e(TAG, "========================")
                        } else {
                            Logging.d(TAG, "=== MQTT SEND SUCCESSFUL ===")
                            Logging.d(TAG, "Message sent successfully")
                            Logging.d(TAG, "Result: $result")
                            Logging.d(TAG, "Update timer updated: $currentTime")
                            Logging.d(TAG, "===========================")
                            lastUpdateTime = currentTime
                        }
                    }
                } else {
                    Logging.w(TAG, "MQTT not connected, cannot send route progress update")
                    null
                }
            } ?: run {
                Logging.w(TAG, "MQTT service not available, cannot send route progress update")
                null
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error sending route progress update: ${e.message}", e)
            null
        }
    }
    
    /**
     * Send trip start event via MQTT
     */
    fun sendTripStartEvent(tripResponse: TripResponse): CompletableFuture<*>? {
        return try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    Logging.d(TAG, "=== SENDING TRIP START EVENT ===")
                    Logging.d(TAG, "Trip ID: ${tripResponse.id}")
                    Logging.d(TAG, "Vehicle: ${tripResponse.vehicle.license_plate}")
                    Logging.d(TAG, "Origin: ${tripResponse.route.origin.google_place_name}")
                    Logging.d(TAG, "Destination: ${tripResponse.route.destination.google_place_name}")
                    Logging.d(TAG, "================================")
                    
                    mqtt.sendTripStatusUpdate(
                        tripId = tripResponse.id.toString(),
                        status = "started"
                    )?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to send trip start event: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Trip start event sent successfully")
                        }
                    }
                } else {
                    Logging.w(TAG, "MQTT not connected, cannot send trip start event")
                    null
                }
            } ?: run {
                Logging.w(TAG, "MQTT service not available, cannot send trip start event")
                null
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error sending trip start event: ${e.message}", e)
            null
        }
    }
    
    /**
     * Send trip completion event via MQTT
     */
    fun sendTripCompletionEvent(tripResponse: TripResponse): CompletableFuture<*>? {
        return try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    Logging.d(TAG, "=== SENDING TRIP COMPLETION EVENT ===")
                    Logging.d(TAG, "Trip ID: ${tripResponse.id}")
                    Logging.d(TAG, "================================")
                    
                    // Convert trip data with completed status
                    val tripData = mqtt.convertTripResponseToTripData(
                        tripResponse = tripResponse,
                        currentSpeed = 0.0, // Vehicle stopped at destination
                        currentLocation = null, // Use destination location
                        remainingTimeToDestination = 0L, // Trip completed
                        remainingDistanceToDestination = 0.0 // Trip completed
                    )
                    
                    mqtt.sendTripEventMessage(
                        event = "progress_update",
                        tripData = tripData
                    )?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to send trip completion event: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Trip completion event sent successfully")
                        }
                    }
                } else {
                    Logging.w(TAG, "MQTT not connected, cannot send trip completion event")
                    null
                }
            } ?: run {
                Logging.w(TAG, "MQTT service not available, cannot send trip completion event")
                null
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error sending trip completion event: ${e.message}", e)
            null
        }
    }
    
    /**
     * Check if MQTT is connected
     */
    fun isConnected(): Boolean {
        return mqttService?.isConnected() ?: false
    }
    
    /**
     * Get service status for debugging
     */
    fun getServiceStatus(): String {
        return "RouteProgressMqttService: connected=${isConnected()}, lastUpdate=${lastUpdateTime}, interval=${PROGRESS_UPDATE_INTERVAL_MS}ms"
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
     * Reset the update timer (useful for testing or manual updates)
     */
    fun resetUpdateTimer() {
        lastUpdateTime = 0L
        Logging.d(TAG, "Update timer reset")
    }
    
    /**
     * Force send an update regardless of interval (useful for testing)
     */
    fun forceSendUpdate(
        tripResponse: TripResponse,
        remainingTimeToDestination: Long?,
        remainingDistanceToDestination: Double?,
        currentSpeed: Double? = null,
        currentLocation: MqttService.Location? = null
    ): CompletableFuture<*>? {
        val originalLastUpdate = lastUpdateTime
        lastUpdateTime = 0L // Reset timer to force update
        
        val result = sendTripProgressUpdate(
            tripResponse = tripResponse,
            remainingTimeToDestination = remainingTimeToDestination,
            remainingDistanceToDestination = remainingDistanceToDestination,
            currentSpeed = currentSpeed,
            currentLocation = currentLocation
        )
        
        // Restore original timer if update failed
        result?.whenComplete { _, throwable ->
            if (throwable != null) {
                lastUpdateTime = originalLastUpdate
            }
        }
        
        return result
    }
}
