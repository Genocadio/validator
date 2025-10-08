package com.gocavgo.validator.dataclass


import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TripResponse(
    val id: Int,
    val route_id: Int,
    val vehicle_id: Int,
    val vehicle: VehicleInfo,
    val status: String,
    val departure_time: Long,
    val connection_mode: String,
    val notes: String?,
    val seats: Int,
    val remaining_time_to_destination: Long? = null,
    val remaining_distance_to_destination: Double? = null,
    val is_reversed: Boolean,
    val has_custom_waypoints: Boolean,
    val created_at: String,
    val updated_at: String,
    val completion_timestamp: Long? = null, // Timestamp when trip was completed (in milliseconds)
    val route: TripRoute,
    val waypoints: List<TripWaypoint>
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class VehicleInfo(
    val id: Int,
    val company_id: Int,
    val company_name: String,
    val capacity: Int,
    val license_plate: String,
    val driver: DriverInfo?,
    val current_latitude: Double? = null,
    val current_longitude: Double? = null,
    val current_speed: Double? = null,
    val last_location_update: Long? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DriverInfo(
    val name: String,
    val phone: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TripRoute(
    val id: Int,
    val origin: SavePlaceResponse,
    val destination: SavePlaceResponse
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TripWaypoint(
    val id: Int,
    val trip_id: Int,
    val location_id: Int,
    val order: Int,
    val price: Double,
    val is_passed: Boolean,
    val is_next: Boolean,
    val is_custom: Boolean,
    val remaining_time: Long?,
    val remaining_distance: Double?,
    val waypoint_length_meters: Double? = null, // Length from previous waypoint to this waypoint
    val waypoint_time_seconds: Long? = null, // Time from previous waypoint to this waypoint
    val passed_timestamp: Long? = null, // Timestamp when waypoint was passed (in milliseconds)
    val location: SavePlaceResponse
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class WaypointProgress(
    val waypointId: Int,
    val tripId: Int,
    val order: Int,
    val locationName: String,
    val remainingDistanceInMeters: Double,
    val remainingTimeInSeconds: Long,
    val trafficDelayInSeconds: Long,
    val isReached: Boolean,
    val isNext: Boolean,
    val timestamp: Long,
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val currentSpeedInMetersPerSecond: Double? = null,
    val speedAccuracyInMetersPerSecond: Double? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PaginatedTripsResponse(
    val trips: List<TripResponse>,
    val total: Int,
    val limit: Int,
    val offset: Int
)
