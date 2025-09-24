package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class SavePlaceResponse(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val code: String,
    val google_place_name: String,
    val custom_name: String?,
    val province: String,
    val district: String,
    val place_id: String?,
    val created_at: String,
    val updated_at: String
)

