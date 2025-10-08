@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

// MQTT Status Messages
@Serializable
data class CarStatusMessage(
    val status: String,
    val car_id: String,
    val timestamp: Long
)

@Serializable
data class PingMessage(
    val car_id: String,
    val ping_time: Long,
    val expected_response: String
)

@Serializable
data class PongMessage(
    val car_id: String,
    val ping_time: Long,
    val pong_time: Long,
    val response: String
)

// Trip Assignment Messages
@Serializable
data class TripAssignmentMessage(
    val trip_id: String,
    val vehicle_id: String,
    val start_location: String,
    val end_location: String,
    val timestamp: Long
)

// Trip Status Update Messages
@Serializable
data class TripStatusUpdateMessage(
    val trip_id: String,
    val car_id: String,
    val status: String,
    val timestamp: Long,
    val current_latitude: Double? = null,
    val current_longitude: Double? = null
)

// Booking Event Messages
@Serializable
data class BookingEventMessage(
    val event: String,
    val data: BookingData
)

@Serializable
data class BookingData(
    val booking: Booking,
    val message: String? = null,
    val payment_reference: String? = null
)

@Serializable
data class Booking(
    val id: String,
    val trip_id: Int,
    val user_id: String? = null,
    val user_email: String? = null,
    val user_phone: String? = null,
    val user_name: String? = null,
    val pickup_location_id: String? = null,
    val dropoff_location_id: String? = null,
    val number_of_tickets: Int? = null,
    val total_amount: Double? = null,
    val status: String,
    val booking_reference: String? = null,
    val created_at: String,
    val updated_at: String,
    val tickets: List<MqttTicket>? = null,
    val payment: MqttPayment? = null
)

@Serializable
data class MqttTicket(
    val id: String,
    val booking_id: String,
    val ticket_number: String,
    val qr_code: String,
    val is_used: Boolean,
    val created_at: String,
    val updated_at: String,
    val pickup_location_name: String? = null,
    val dropoff_location_name: String? = null,
    val car_plate: String? = null,
    val car_company: String? = null,
    val pickup_time: String? = null
)

@Serializable
data class MqttPayment(
    val id: String,
    val booking_id: String,
    val amount: Double,
    val payment_method: String,
    val status: String,
    val created_at: String,
    val updated_at: String
)

// Trip Event Messages
@Serializable
data class TripEventMessage(
    val event: String,
    val data: TripData
)

@Serializable
data class TripData(
    val id: Int,
    val route_id: Int,
    val vehicle_id: Int,
    val vehicle: VehicleData,
    val status: String,
    val departure_time: Long,
    val completion_time: Long? = null,
    val connection_mode: String,
    val notes: String?,
    val seats: Int,
    val remaining_time_to_destination: Long? = null,
    val remaining_distance_to_destination: Double? = null,
    val is_reversed: Boolean,
    val current_speed: Double? = null,
    val current_latitude: Double? = null,
    val current_longitude: Double? = null,
    val has_custom_waypoints: Boolean,
    val created_at: String,
    val updated_at: String,
    val route: RouteData,
    val waypoints: List<WaypointData>
)

@Serializable
data class VehicleData(
    val id: Int,
    val company_id: Int,
    val company_name: String,
    val capacity: Int,
    val license_plate: String,
    val driver: DriverData?
)

@Serializable
data class DriverData(
    val name: String,
    val phone: String
)

@Serializable
data class RouteData(
    val id: Int,
    val origin: LocationData,
    val destination: LocationData
)

@Serializable
data class WaypointData(
    val id: Int,
    val trip_id: Int,
    val location_id: Int,
    val order: Int,
    val price: Double,
    val is_passed: Boolean,
    val is_next: Boolean,
    val passed_timestamp: Long? = null,
    val remaining_time: Long?,
    val remaining_distance: Double?,
    val waypoint_length_meters: Double? = null, // Original length from route sections
    val waypoint_time_seconds: Long? = null, // Original time from route sections
    val is_custom: Boolean,
    val created_at: String? = null,
    val updated_at: String? = null,
    val location: LocationData
)

@Serializable
data class LocationData(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val code: String,
    val google_place_name: String,
    val custom_name: String?,
    val place_id: String?,
    val created_at: String?,
    val updated_at: String?
)

// Booking Update Messages
@Serializable
data class BookingUpdateMessage(
    val trip_id: String,
    val booking: BookingUpdate,
    val timestamp: Long
)

@Serializable
data class BookingUpdate(
    val booking_id: String,
    val passenger_id: String,
    val pickup_location: String,
    val dropoff_location: String,
    val action: String, // NEW, CANCELLED, UPDATED
    val timestamp: Long
)

// Booking Confirmation Messages
@Serializable
data class BookingConfirmationMessage(
    val trip_id: String,
    val booking_id: String,
    val car_id: String,
    val action: String,
    val timestamp: Long
)

// Backend Trip Status Update Message
@Serializable
data class BackendTripStatusUpdateMessage(
    val trip_id: String,
    val vehicle_id: String,
    val status: String,
    val timestamp: Long,
    val current_latitude: Double,
    val current_longitude: Double
)
