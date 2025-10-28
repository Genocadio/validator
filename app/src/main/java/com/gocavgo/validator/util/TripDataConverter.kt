package com.gocavgo.validator.util

import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripData

/**
 * Utility object for converting backend trip data to Android trip response format
 */
object TripDataConverter {
    
    /**
     * Convert backend trip data to Android trip response format
     */
    fun convertBackendTripToAndroid(backendTrip: TripData): TripResponse {
        return TripResponse(
            id = backendTrip.id,
            route_id = backendTrip.route_id,
            vehicle_id = backendTrip.vehicle_id,
            vehicle = com.gocavgo.validator.dataclass.VehicleInfo(
                id = backendTrip.vehicle.id,
                company_id = backendTrip.vehicle.company_id,
                company_name = backendTrip.vehicle.company_name,
                capacity = backendTrip.vehicle.capacity,
                license_plate = backendTrip.vehicle.license_plate,
                driver = backendTrip.vehicle.driver?.let { driver ->
                    com.gocavgo.validator.dataclass.DriverInfo(
                        name = driver.name,
                        phone = driver.phone
                    )
                }
            ),
            status = backendTrip.status,
            departure_time = backendTrip.departure_time,
            connection_mode = backendTrip.connection_mode,
            notes = backendTrip.notes,
            seats = backendTrip.seats,
            remaining_time_to_destination = backendTrip.remaining_time_to_destination,
            remaining_distance_to_destination = backendTrip.remaining_distance_to_destination,
            is_reversed = backendTrip.is_reversed,
            has_custom_waypoints = backendTrip.has_custom_waypoints,
            created_at = backendTrip.created_at,
            updated_at = backendTrip.updated_at,
            completion_timestamp = backendTrip.completion_time,
            route = com.gocavgo.validator.dataclass.TripRoute(
                id = backendTrip.route.id,
                origin = com.gocavgo.validator.dataclass.SavePlaceResponse(
                    id = backendTrip.route.origin.id,
                    latitude = backendTrip.route.origin.latitude,
                    longitude = backendTrip.route.origin.longitude,
                    code = backendTrip.route.origin.code,
                    google_place_name = backendTrip.route.origin.google_place_name,
                    custom_name = backendTrip.route.origin.custom_name,
                    province = "", // Backend doesn't have this field
                    district = "", // Backend doesn't have this field
                    place_id = backendTrip.route.origin.place_id,
                    created_at = backendTrip.route.origin.created_at ?: "",
                    updated_at = backendTrip.route.origin.updated_at ?: ""
                ),
                destination = com.gocavgo.validator.dataclass.SavePlaceResponse(
                    id = backendTrip.route.destination.id,
                    latitude = backendTrip.route.destination.latitude,
                    longitude = backendTrip.route.destination.longitude,
                    code = backendTrip.route.destination.code,
                    google_place_name = backendTrip.route.destination.google_place_name,
                    custom_name = backendTrip.route.destination.custom_name,
                    province = "", // Backend doesn't have this field
                    district = "", // Backend doesn't have this field
                    place_id = backendTrip.route.destination.place_id,
                    created_at = backendTrip.route.destination.created_at ?: "",
                    updated_at = backendTrip.route.destination.updated_at ?: ""
                )
            ),
            waypoints = backendTrip.waypoints.map { waypoint ->
                com.gocavgo.validator.dataclass.TripWaypoint(
                    id = waypoint.id,
                    trip_id = waypoint.trip_id,
                    location_id = waypoint.location_id,
                    order = waypoint.order,
                    price = waypoint.price,
                    is_passed = waypoint.is_passed,
                    is_next = waypoint.is_next,
                    is_custom = waypoint.is_custom,
                    remaining_time = waypoint.remaining_time,
                    remaining_distance = waypoint.remaining_distance,
                    waypoint_length_meters = waypoint.waypoint_length_meters,
                    waypoint_time_seconds = waypoint.waypoint_time_seconds,
                    passed_timestamp = waypoint.passed_timestamp,
                    location = com.gocavgo.validator.dataclass.SavePlaceResponse(
                        id = waypoint.location.id,
                        latitude = waypoint.location.latitude,
                        longitude = waypoint.location.longitude,
                        code = waypoint.location.code,
                        google_place_name = waypoint.location.google_place_name,
                        custom_name = waypoint.location.custom_name,
                        province = "", // Backend doesn't have this field
                        district = "", // Backend doesn't have this field
                        place_id = waypoint.location.place_id,
                        created_at = waypoint.location.created_at ?: "",
                        updated_at = waypoint.location.updated_at ?: ""
                    )
                )
            }
        )
    }
}
