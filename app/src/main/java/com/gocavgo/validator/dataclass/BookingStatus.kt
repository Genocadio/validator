package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

@Serializable
enum class BookingStatus(val value: String) {
    PENDING("PENDING"),
    CONFIRMED("CONFIRMED"),
    CANCELED("CANCELED"),
    USED("USED"),
    EXPIRED("EXPIRED");

    companion object {
        fun fromString(status: String): BookingStatus {
            return values().find { it.value == status } ?: PENDING
        }
    }
}


