package com.gocavgo.validator.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    
    @Query("SELECT * FROM trips ORDER BY created_at DESC")
    fun getAllTrips(): Flow<List<TripEntity>>
    
    @Query("SELECT * FROM trips WHERE vehicle_id = :vehicleId ORDER BY created_at DESC")
    fun getTripsByVehicle(vehicleId: Int): Flow<List<TripEntity>>
    
    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: Int): TripEntity?
    
    @Query("SELECT * FROM trips WHERE vehicle_id = :vehicleId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestTripByVehicle(vehicleId: Int): TripEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrips(trips: List<TripEntity>)
    
    @Update
    suspend fun updateTrip(trip: TripEntity)
    
    @Delete
    suspend fun deleteTrip(trip: TripEntity)
    
    @Query("DELETE FROM trips WHERE vehicle_id = :vehicleId")
    suspend fun deleteTripsByVehicle(vehicleId: Int)
    
    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: Int)
    
    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()
    
    @Query("SELECT COUNT(*) FROM trips WHERE vehicle_id = :vehicleId")
    suspend fun getTripCountByVehicle(vehicleId: Int): Int
    
    @Query("SELECT * FROM trips WHERE vehicle_id = :vehicleId AND status IN ('pending', 'scheduled', 'in_progress') ORDER BY created_at DESC LIMIT 1")
    suspend fun getActiveTripByVehicle(vehicleId: Int): TripEntity?
    
    @Query("SELECT COUNT(*) FROM trips WHERE vehicle_id = :vehicleId AND status IN ('pending', 'scheduled', 'in_progress')")
    suspend fun getActiveTripCountByVehicle(vehicleId: Int): Int
    
    @Query("UPDATE trips SET status = :newStatus WHERE id = :tripId")
    suspend fun updateTripStatus(tripId: Int, newStatus: String)
    
    @Query("DELETE FROM trips WHERE vehicle_id = :vehicleId AND id NOT IN (:tripIds)")
    suspend fun deleteTripsNotInList(vehicleId: Int, tripIds: List<Int>)
    
    @Query("SELECT id FROM trips WHERE vehicle_id = :vehicleId")
    suspend fun getAllTripIdsByVehicle(vehicleId: Int): List<Int>
}
