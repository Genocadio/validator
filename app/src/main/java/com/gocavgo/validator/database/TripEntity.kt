package com.gocavgo.validator.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.VehicleInfo
import com.gocavgo.validator.dataclass.DriverInfo
import com.gocavgo.validator.dataclass.TripRoute
import com.gocavgo.validator.dataclass.TripWaypoint
import com.gocavgo.validator.dataclass.SavePlaceResponse

@Entity(tableName = "trips")
@TypeConverters(TripConverters::class)
data class TripEntity(
    @PrimaryKey val id: Int,
    val route_id: Int,
    val vehicle_id: Int,
    val vehicle: VehicleInfo,
    val status: String,
    val departure_time: Long,
    val connection_mode: String,
    val notes: String?,
    val seats: Int,
    val remaining_time_to_destination: Long?,
    val remaining_distance_to_destination: Double?,
    val is_reversed: Boolean,
    val has_custom_waypoints: Boolean,
    val created_at: String,
    val updated_at: String,
    val completion_timestamp: Long? = null, // Timestamp when trip was completed (in milliseconds)
    val route: TripRoute,
    val waypoints: List<TripWaypoint>
) {
    fun toTripResponse(): TripResponse {
        return TripResponse(
            id = id,
            route_id = route_id,
            vehicle_id = vehicle_id,
            vehicle = vehicle,
            status = com.gocavgo.validator.dataclass.TripStatus.normalizeStatus(status),
            departure_time = departure_time,
            connection_mode = connection_mode,
            notes = notes,
            seats = seats,
            remaining_time_to_destination = remaining_time_to_destination,
            remaining_distance_to_destination = remaining_distance_to_destination,
            is_reversed = is_reversed,
            has_custom_waypoints = has_custom_waypoints,
            created_at = created_at,
            updated_at = updated_at,
            completion_timestamp = completion_timestamp,
            route = route,
            waypoints = waypoints
        )
    }

    companion object {
        fun fromTripResponse(trip: TripResponse): TripEntity {
            return TripEntity(
                id = trip.id,
                route_id = trip.route_id,
                vehicle_id = trip.vehicle_id,
                vehicle = trip.vehicle,
                status = com.gocavgo.validator.dataclass.TripStatus.normalizeStatus(trip.status),
                departure_time = trip.departure_time,
                connection_mode = trip.connection_mode,
                notes = trip.notes,
                seats = trip.seats,
                remaining_time_to_destination = trip.remaining_time_to_destination,
                remaining_distance_to_destination = trip.remaining_distance_to_destination,
                is_reversed = trip.is_reversed,
                has_custom_waypoints = trip.has_custom_waypoints,
                created_at = trip.created_at,
                updated_at = trip.updated_at,
                completion_timestamp = trip.completion_timestamp,
                route = trip.route,
                waypoints = trip.waypoints
            )
        }
    }
}

