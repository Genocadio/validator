package com.gocavgo.validator.dataclass;

data class VehicleResponseDto(
    val id: Long,
    val companyId: Long,
    val companyName: String,
    val make: String,
    val model: String,
    val capacity: Int,
    val licensePlate: String,
    val vehicleType: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)