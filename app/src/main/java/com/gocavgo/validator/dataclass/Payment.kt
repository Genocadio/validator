package com.gocavgo.validator.dataclass

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Payment(
    val id: String,
    val booking_id: String,
    val amount: Double,
    val payment_method: PaymentMethod,
    val status: PaymentStatus,
    val transaction_id: String? = null,
    val payment_data: String? = null,
    val created_at: Long,
    val updated_at: Long
)


