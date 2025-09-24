package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class Ticket(
    val id: String,
    val booking_id: String,
    val ticket_number: String,
    val qr_code: String,
    val is_used: Boolean = false,
    val used_at: Long? = null,
    val validated_by: String? = null,
    val created_at: Long,
    val updated_at: Long,
    val pickup_location_name: String,
    val dropoff_location_name: String,
    val car_plate: String,
    val car_company: String,
    val pickup_time: Long
)


