package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentMethod(val value: String) {
    CASH("CASH"),
    CARD("CARD"),
    MOBILE_MONEY("MOBILE_MONEY"),
    BANK_TRANSFER("BANK_TRANSFER");

    companion object {
        fun fromString(method: String): PaymentMethod {
            return values().find { it.value == method } ?: CASH
        }
    }
}


