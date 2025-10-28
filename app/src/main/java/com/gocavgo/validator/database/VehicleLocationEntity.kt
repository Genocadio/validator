package com.gocavgo.validator.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity to store the latest location data for each vehicle.
 * Only keeps the most recent location per vehicle (latest record only).
 */
@Entity(tableName = "vehicle_locations")
data class VehicleLocationEntity(
    @PrimaryKey val vehicleId: Int,
    val latitude: Double,
    val longitude: Double,
    val speed: Double, // in meters per second
    val accuracy: Double, // in meters per second
    val bearing: Double?, // in degrees, nullable
    val timestamp: Long // when the location was recorded (milliseconds since epoch)
)

