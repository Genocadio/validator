package com.gocavgo.validator.repository

import android.content.Context
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.database.TripEntity
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.service.RemoteDataManager
import com.gocavgo.validator.service.RemoteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.util.Log

class TripRepository(context: Context) {
    
    private val tripDao = AppDatabase.getDatabase(context).tripDao()
    private val remoteDataManager = RemoteDataManager.getInstance()
    
    // Get trips from database
    fun getTripsByVehicle(vehicleId: Int): Flow<List<TripResponse>> {
        return tripDao.getTripsByVehicle(vehicleId).map { entities ->
            entities.map { it.toTripResponse() }
        }
    }
    
    // Get specific trip by ID from database
    suspend fun getTripById(tripId: Int): TripResponse? {
        return tripDao.getTripById(tripId)?.toTripResponse()
    }
    
    // Get latest trip for a vehicle from database
    suspend fun getLatestTripByVehicle(vehicleId: Int): TripResponse? {
        return tripDao.getLatestTripByVehicle(vehicleId)?.toTripResponse()
    }
    
    // Fetch trips from remote API and save to database
    suspend fun fetchAndSaveTrips(vehicleId: Int, page: Int = 1, limit: Int = 20): RemoteResult<Int> {
        return try {
            val remoteResult = remoteDataManager.getVehicleTrips(vehicleId, page, limit)
            
            when {
                remoteResult.isSuccess() -> {
                    val paginatedResult = remoteResult.getDataOrNull()
                    if (paginatedResult != null) {
                        // Convert to entities and save to database
                        val entities = paginatedResult.data.map { TripEntity.fromTripResponse(it) }
                        if (entities.isNotEmpty()) {
                            tripDao.insertTrips(entities)
                        }
                        
                        // Clean up trips that are no longer available from remote
                        val availableTripIds = paginatedResult.data.map { it.id }
                        cleanupUnavailableTrips(vehicleId, availableTripIds)
                        
                        RemoteResult.Success(entities.size)
                    } else {
                        // If no paginated result, clean up all local trips
                        cleanupUnavailableTrips(vehicleId, emptyList())
                        RemoteResult.Success(0)
                    }
                }
                remoteResult.isError() -> {
                    val errorMessage = remoteResult.getErrorOrNull() ?: "Unknown error occurred"
                    RemoteResult.Error(errorMessage)
                }
                else -> RemoteResult.Error("Unknown result type")
            }
        } catch (e: Exception) {
            RemoteResult.Error("Exception: ${e.message}")
        }
    }
    
    // Save a single trip to database
    suspend fun saveTrip(trip: TripResponse) {
        val entity = TripEntity.fromTripResponse(trip)
        tripDao.insertTrip(entity)
    }
    
    // Update trip in database
    suspend fun updateTrip(trip: TripResponse) {
        val entity = TripEntity.fromTripResponse(trip)
        tripDao.updateTrip(entity)
    }
    
    // Delete trip from database
    suspend fun deleteTrip(trip: TripResponse) {
        val entity = TripEntity.fromTripResponse(trip)
        tripDao.deleteTrip(entity)
    }
    
    // Delete all trips for a vehicle
    suspend fun deleteTripsByVehicle(vehicleId: Int) {
        tripDao.deleteTripsByVehicle(vehicleId)
    }
    
    // Get trip count for a vehicle
    suspend fun getTripCountByVehicle(vehicleId: Int): Int {
        return tripDao.getTripCountByVehicle(vehicleId)
    }
    
    // Get active trip for a vehicle
    suspend fun getActiveTripByVehicle(vehicleId: Int): TripResponse? {
        return tripDao.getActiveTripByVehicle(vehicleId)?.toTripResponse()
    }
    
    // Get active trip count for a vehicle
    suspend fun getActiveTripCountByVehicle(vehicleId: Int): Int {
        return tripDao.getActiveTripCountByVehicle(vehicleId)
    }
    
    // Update trip status
    suspend fun updateTripStatus(tripId: Int, newStatus: String) {
        val normalizedStatus = com.gocavgo.validator.dataclass.TripStatus.normalizeStatus(newStatus)
        tripDao.updateTripStatus(tripId, normalizedStatus)
    }
    
    // Update waypoint status within a trip
    suspend fun updateWaypointStatus(tripId: Int, waypointId: Int, isPassed: Boolean) {
        val trip = getTripById(tripId)
        if (trip != null) {
            // Update the waypoint status
            val updatedWaypoints = trip.waypoints.map { waypoint ->
                if (waypoint.id == waypointId) {
                    waypoint.copy(is_passed = isPassed)
                } else {
                    waypoint
                }
            }
            
            // Create updated trip with modified waypoints
            val updatedTrip = trip.copy(waypoints = updatedWaypoints)
            
            // Save updated trip to database
            saveTrip(updatedTrip)
        }
    }

    // Update remaining time/distance for a waypoint in a trip
    suspend fun updateWaypointRemaining(
        tripId: Int,
        waypointId: Int,
        remainingTimeSeconds: Long?,
        remainingDistanceMeters: Double?
    ) {
        val trip = getTripById(tripId)
        if (trip != null) {
            val updatedWaypoints = trip.waypoints.map { waypoint ->
                if (waypoint.id == waypointId) {
                    waypoint.copy(
                        remaining_time = remainingTimeSeconds ?: waypoint.remaining_time,
                        remaining_distance = remainingDistanceMeters ?: waypoint.remaining_distance
                    )
                } else {
                    waypoint
                }
            }

            val updatedTrip = trip.copy(waypoints = updatedWaypoints)
            saveTrip(updatedTrip)
            Log.d("TripRepository", "Saved remaining progress to DB: trip=$tripId, waypoint=$waypointId, time=${remainingTimeSeconds}, distance=${remainingDistanceMeters}")
        }
    }
    
    // Update vehicle current location
    suspend fun updateVehicleCurrentLocation(vehicleId: Int, latitude: Double, longitude: Double, speed: Double) {
        try {
            // Get the active trip for this vehicle
            val activeTrip = getActiveTripByVehicle(vehicleId)
            if (activeTrip != null) {
                // Update the vehicle location in the active trip
                val updatedVehicle = activeTrip.vehicle.copy(
                    current_latitude = latitude,
                    current_longitude = longitude,
                    current_speed = speed,
                    last_location_update = System.currentTimeMillis()
                )
                
                val updatedTrip = activeTrip.copy(vehicle = updatedVehicle)
                saveTrip(updatedTrip)
                
                Log.d("TripRepository", "Vehicle location updated: $latitude, $longitude, speed: $speed")
            } else {
                Log.w("TripRepository", "No active trip found for vehicle $vehicleId")
            }
        } catch (e: Exception) {
            Log.e("TripRepository", "Failed to update vehicle location: ${e.message}", e)
        }
    }
    
    // Check if vehicle has any active trips
    suspend fun hasActiveTrips(vehicleId: Int): Boolean {
        return getActiveTripCountByVehicle(vehicleId) > 0
    }
    
    // Clean up trips that are no longer available from remote
    suspend fun cleanupUnavailableTrips(vehicleId: Int, availableTripIds: List<Int>) {
        try {
            // Get current trip count before cleanup
            val beforeCount = tripDao.getTripCountByVehicle(vehicleId)
            val currentTripIds = getAllTripIdsByVehicle(vehicleId)
            
            Log.d("TripRepository", "=== CLEANUP DEBUG ===")
            Log.d("TripRepository", "Vehicle ID: $vehicleId")
            Log.d("TripRepository", "Available trip IDs from remote: $availableTripIds")
            Log.d("TripRepository", "Current trip IDs in database: $currentTripIds")
            Log.d("TripRepository", "Trips to keep: ${currentTripIds.intersect(availableTripIds.toSet())}")
            Log.d("TripRepository", "Trips to delete: ${currentTripIds.filter { it !in availableTripIds }}")
            
            // Delete trips that are not in the available list
            tripDao.deleteTripsNotInList(vehicleId, availableTripIds)
            
            // Get trip count after cleanup
            val afterCount = tripDao.getTripCountByVehicle(vehicleId)
            val deletedCount = beforeCount - afterCount
            
            Log.d("TripRepository", "Cleanup result: $deletedCount trips deleted (before: $beforeCount, after: $afterCount)")
            Log.d("TripRepository", "=== END CLEANUP DEBUG ===")
        } catch (e: Exception) {
            Log.e("TripRepository", "Failed to cleanup unavailable trips: ${e.message}", e)
        }
    }
    
    // Get all trip IDs for a vehicle
    suspend fun getAllTripIdsByVehicle(vehicleId: Int): List<Int> {
        return tripDao.getAllTripIdsByVehicle(vehicleId)
    }
}
