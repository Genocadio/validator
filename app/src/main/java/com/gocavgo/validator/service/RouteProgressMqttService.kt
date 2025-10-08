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
        
        init {
            // Enable logging for this service to debug MQTT delivery
            Logging.setTagEnabled(TAG, true)
        }
        private const val PROGRESS_UPDATE_INTERVAL_MS = 5000L // 5 seconds (reduced for testing)
        
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

                    Logging.d(TAG, "Waypoint data:")
                    tripResponse.waypoints.filter { !it.is_passed }.forEach { wp ->
                        Logging.d(TAG, "  Waypoint ${wp.order} (${wp.location.google_place_name}): ${wp.remaining_time}s, ${wp.remaining_distance}m")
                    }
                    
                    // Convert trip data using the main MQTT service's conversion method
                    val tripData = mqtt.convertTripResponseToTripData(
                        tripResponse = tripResponse,
                        currentSpeed = currentSpeed,
                        currentLocation = currentLocation,
                        remainingTimeToDestination = remainingTimeToDestination,
                        remainingDistanceToDestination = remainingDistanceToDestination
                    )
                    
                    // Send the MQTT message
                    mqtt.sendTripEventMessage(
                        event = "progress_update",
                        tripData = tripData
                    ).whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "=== MQTT SEND FAILED ===")
                            Logging.e(TAG, "Error: ${throwable.message}", throwable)
                            Logging.e(TAG, "========================")
                        } else {
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
     * Send trip start event via MQTT with full trip data (original route information)
     * This is sent after validation and data storage, before navigation starts
     */
    fun sendTripStartEvent(tripResponse: TripResponse): CompletableFuture<*>? {
        return try {
            mqttService?.let { mqtt ->
                if (mqtt.isConnected()) {
                    // Convert trip data with original route information (no real-time progress data)
                    val tripData = mqtt.convertTripResponseToTripData(
                        tripResponse = tripResponse,
                        currentSpeed = null, // No real-time speed yet
                        currentLocation = null, // No real-time location yet
                        remainingTimeToDestination = null, // No real-time progress yet
                        remainingDistanceToDestination = null // No real-time progress yet
                    )
                    
                    // Send the full trip start event with original route data
                    mqtt.sendTripEventMessage(
                        event = "trip_started",
                        tripData = tripData
                    ).whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to send trip start event: ${throwable.message}", throwable)
                        } else {
                            Logging.d(TAG, "Trip start event sent successfully with full trip data")
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
}
