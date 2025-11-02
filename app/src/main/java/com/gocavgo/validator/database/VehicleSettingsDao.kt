package com.gocavgo.validator.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VehicleSettingsDao {
    
    @Query("SELECT * FROM vehicle_settings WHERE vehicleId = :vehicleId LIMIT 1")
    suspend fun getSettings(vehicleId: Int): VehicleSettingsEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: VehicleSettingsEntity)
    
    @Query("DELETE FROM vehicle_settings WHERE vehicleId = :vehicleId")
    suspend fun deleteSettings(vehicleId: Int)
}

