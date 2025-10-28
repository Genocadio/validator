package com.gocavgo.validator.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for VehicleLocationEntity.
 * Handles the latest location data for each vehicle.
 */
@Dao
interface VehicleLocationDao {
    
    /**
     * Upsert operation to insert or update vehicle location.
     * Since vehicleId is primary key, this will replace the existing record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVehicleLocation(location: VehicleLocationEntity)
    
    /**
     * Get the latest location for a specific vehicle.
     */
    @Query("SELECT * FROM vehicle_locations WHERE vehicleId = :vehicleId")
    suspend fun getVehicleLocation(vehicleId: Int): VehicleLocationEntity?
    
    /**
     * Get all vehicle locations (for debugging/admin purposes).
     */
    @Query("SELECT * FROM vehicle_locations")
    suspend fun getAllVehicleLocations(): List<VehicleLocationEntity>
    
    /**
     * Delete a vehicle's location data.
     */
    @Query("DELETE FROM vehicle_locations WHERE vehicleId = :vehicleId")
    suspend fun deleteVehicleLocation(vehicleId: Int)
    
    /**
     * Delete all vehicle locations.
     */
    @Query("DELETE FROM vehicle_locations")
    suspend fun deleteAllVehicleLocations()
}

