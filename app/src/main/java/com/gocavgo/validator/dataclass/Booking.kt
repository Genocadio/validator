package com.gocavgo.validator.dataclass

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TripBooking(
    val id: String,
    val trip_id: Int,
    val user_id: String? = null,
    val user_email: String? = null,
    val user_phone: String,
    val user_name: String,
    val pickup_location_id: String,
    val dropoff_location_id: String,
    val number_of_tickets: Int,
    val total_amount: Double,
    val status: BookingStatus,
    val booking_reference: String,
    val created_at: Long,
    val updated_at: Long,
    val tickets: List<Ticket> = emptyList(),
    val payment: Payment? = null
)

/**
 * API response data classes for booking endpoint
 * These match the structure returned by https://api.gocavgo.com/api/book/bookings/trip/{trip_id}
 * The API returns a direct array of ApiBooking objects
 */
@Serializable
data class ApiBooking(
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
    val tickets: List<ApiTicket>? = null,
    val payment: ApiPayment? = null
)

@Serializable
data class ApiTicket(
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
data class ApiPayment(
    val id: String,
    val booking_id: String,
    val amount: Double,
    val payment_method: String,
    val status: String,
    val transaction_id: String? = null,
    val payment_data: String? = null,
    val created_at: String,
    val updated_at: String
)