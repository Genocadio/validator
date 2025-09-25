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
