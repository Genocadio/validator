package com.gocavgo.validator.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.gocavgo.validator.dataclass.PaymentStatus
import com.gocavgo.validator.dataclass.PaymentMethod

@Entity(tableName = "payments")
@TypeConverters(TripConverters::class)
data class PaymentEntity(
    @PrimaryKey val id: String,
    val booking_id: String,
    val amount: Double,
    val payment_method: PaymentMethod,
    val status: PaymentStatus,
    val transaction_id: String? = null,
    val payment_data: String? = null,
    val created_at: Long,
    val updated_at: Long
) {
    fun toPayment(): com.gocavgo.validator.dataclass.Payment {
        return com.gocavgo.validator.dataclass.Payment(
            id = id,
            booking_id = booking_id,
            amount = amount,
            payment_method = payment_method,
            status = status,
            transaction_id = transaction_id,
            payment_data = payment_data,
            created_at = created_at,
            updated_at = updated_at
        )
    }

    companion object {
        fun fromPayment(payment: com.gocavgo.validator.dataclass.Payment): PaymentEntity {
            return PaymentEntity(
                id = payment.id,
                booking_id = payment.booking_id,
                amount = payment.amount,
                payment_method = payment.payment_method,
                status = payment.status,
                transaction_id = payment.transaction_id,
                payment_data = payment.payment_data,
                created_at = payment.created_at,
                updated_at = payment.updated_at
            )
        }
    }
}


