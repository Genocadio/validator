package com.gocavgo.validator.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tickets")
data class TicketEntity(
    @PrimaryKey val id: String,
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
) {
    fun toTicket(): com.gocavgo.validator.dataclass.Ticket {
        return com.gocavgo.validator.dataclass.Ticket(
            id = id,
            booking_id = booking_id,
            ticket_number = ticket_number,
            qr_code = qr_code,
            is_used = is_used,
            used_at = used_at,
            validated_by = validated_by,
            created_at = created_at,
            updated_at = updated_at,
            pickup_location_name = pickup_location_name,
            dropoff_location_name = dropoff_location_name,
            car_plate = car_plate,
            car_company = car_company,
            pickup_time = pickup_time
        )
    }

    companion object {
        fun fromTicket(ticket: com.gocavgo.validator.dataclass.Ticket): TicketEntity {
            return TicketEntity(
                id = ticket.id,
                booking_id = ticket.booking_id,
                ticket_number = ticket.ticket_number,
                qr_code = ticket.qr_code,
                is_used = ticket.is_used,
                used_at = ticket.used_at,
                validated_by = ticket.validated_by,
                created_at = ticket.created_at,
                updated_at = ticket.updated_at,
                pickup_location_name = ticket.pickup_location_name,
                dropoff_location_name = ticket.dropoff_location_name,
                car_plate = ticket.car_plate,
                car_company = ticket.car_company,
                pickup_time = ticket.pickup_time
            )
        }
    }
}


