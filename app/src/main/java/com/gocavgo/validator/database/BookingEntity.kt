package com.gocavgo.validator.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.gocavgo.validator.dataclass.BookingStatus
import com.gocavgo.validator.dataclass.PaymentMethod

@Entity(tableName = "bookings")
@TypeConverters(TripConverters::class)
data class BookingEntity(
    @PrimaryKey val id: String,
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
    val updated_at: Long
) {
    fun toTripBooking(): com.gocavgo.validator.dataclass.TripBooking {
        return com.gocavgo.validator.dataclass.TripBooking(
            id = id,
            trip_id = trip_id,
            user_id = user_id,
            user_email = user_email,
            user_phone = user_phone,
            user_name = user_name,
            pickup_location_id = pickup_location_id,
            dropoff_location_id = dropoff_location_id,
            number_of_tickets = number_of_tickets,
            total_amount = total_amount,
            status = status,
            booking_reference = booking_reference,
            created_at = created_at,
            updated_at = updated_at,
            tickets = emptyList(), // Will be populated separately
            payment = null // Will be populated separately
        )
    }

    companion object {
        fun fromTripBooking(booking: com.gocavgo.validator.dataclass.TripBooking): BookingEntity {
            return BookingEntity(
                id = booking.id,
                trip_id = booking.trip_id,
                user_id = booking.user_id,
                user_email = booking.user_email,
                user_phone = booking.user_phone,
                user_name = booking.user_name,
                pickup_location_id = booking.pickup_location_id,
                dropoff_location_id = booking.dropoff_location_id,
                number_of_tickets = booking.number_of_tickets,
                total_amount = booking.total_amount,
                status = booking.status,
                booking_reference = booking.booking_reference,
                created_at = booking.created_at,
                updated_at = booking.updated_at
            )
        }
    }
}
