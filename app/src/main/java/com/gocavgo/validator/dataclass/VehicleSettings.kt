package com.gocavgo.validator.dataclass

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class VehicleSettings(
    val id: Int,
    val vehicleId: Int,
    val logout: Boolean,
    val devmode: Boolean,
    val deactivate: Boolean,
    val appmode: Boolean,
    val simulate: Boolean
)

// MQTT payload format (may include licensePlate)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class VehicleSettingsMqttPayload(
    val licensePlate: String? = null,
    val logout: Boolean,
    val devmode: Boolean,
    val deactivate: Boolean,
    val appmode: Boolean,
    val simulate: Boolean
)
















