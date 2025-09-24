package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus(val value: String) {
    PENDING("PENDING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    REFUNDED("REFUNDED");

    companion object {
        fun fromString(status: String): PaymentStatus {
            return values().find { it.value == status } ?: PENDING
        }
    }
}


