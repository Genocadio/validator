package com.gocavgo.validator.repository

import android.content.Context
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.database.TripEntity
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.service.RemoteDataManager
import com.gocavgo.validator.service.RemoteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.gocavgo.validator.util.Logging

class TripRepository(context: Context) {
    
    private val tripDao = AppDatabase.getDatabase(context).tripDao()
    private val vehicleLocationDao = AppDatabase.getDatabase(context).vehicleLocationDao()
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
        // Use insertTrip to handle both new and existing trips (REPLACE on conflict)
        tripDao.insertTrip(entity)
        Logging.d("TripRepository", "Saved trip in database: ID=${trip.id}, remaining_time=${trip.remaining_time_to_destination}, remaining_distance=${trip.remaining_distance_to_destination}")
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
    
    // Delete trip by ID
    suspend fun deleteTripById(tripId: Int) {
        tripDao.deleteTripById(tripId)
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
    
    // Update waypoint next status within a trip
    suspend fun updateWaypointNextStatus(tripId: Int, waypointId: Int, isNext: Boolean) {
        val trip = getTripById(tripId)
        if (trip != null) {
            // Update the waypoint next status
            val updatedWaypoints = trip.waypoints.map { waypoint ->
                if (waypoint.id == waypointId) {
                    waypoint.copy(is_next = isNext)
                } else {
                    waypoint
                }
            }
            
            // Create updated trip with modified waypoints
            val updatedTrip = trip.copy(waypoints = updatedWaypoints)
            
            // Save updated trip to database
            saveTrip(updatedTrip)
            Logging.d("TripRepository", "Updated waypoint $waypointId is_next status to $isNext in trip $tripId")
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
            Logging.d("TripRepository", "Saved remaining progress to DB: trip=$tripId, waypoint=$waypointId, time=${remainingTimeSeconds}, distance=${remainingDistanceMeters}")
        }
    }

    // Update original duration/length for a waypoint in a trip (from route sections)
    suspend fun updateWaypointOriginalData(
        tripId: Int,
        waypointId: Int,
        waypointLengthMeters: Double,
        waypointTimeSeconds: Long
    ) {
        val trip = getTripById(tripId)
        if (trip != null) {
            val updatedWaypoints = trip.waypoints.map { waypoint ->
                if (waypoint.id == waypointId) {
                    waypoint.copy(
                        waypoint_length_meters = waypointLengthMeters,
                        waypoint_time_seconds = waypointTimeSeconds
                    )
                } else {
                    waypoint
                }
            }

            val updatedTrip = trip.copy(waypoints = updatedWaypoints)
            saveTrip(updatedTrip)
            Logging.d("TripRepository", "Saved original waypoint data to DB: trip=$tripId, waypoint=$waypointId, length=${waypointLengthMeters}m, time=${waypointTimeSeconds}s")
        }
    }

    // Update waypoint passed timestamp
    suspend fun updateWaypointPassedTimestamp(
        tripId: Int,
        waypointId: Int,
        passedTimestamp: Long
    ) {
        val trip = getTripById(tripId)
        if (trip != null) {
            val updatedWaypoints = trip.waypoints.map { waypoint ->
                if (waypoint.id == waypointId) {
                    waypoint.copy(
                        is_passed = true,
                        passed_timestamp = passedTimestamp
                    )
                } else {
                    waypoint
                }
            }

            val updatedTrip = trip.copy(waypoints = updatedWaypoints)
            saveTrip(updatedTrip)
            Logging.d("TripRepository", "Saved waypoint passed timestamp to DB: trip=$tripId, waypoint=$waypointId, timestamp=$passedTimestamp")
        }
    }

    // Update trip completion timestamp
    suspend fun updateTripCompletionTimestamp(
        tripId: Int,
        completionTimestamp: Long
    ) {
        val trip = getTripById(tripId)
        if (trip != null) {
            val updatedTrip = trip.copy(
                status = "completed",
                completion_timestamp = completionTimestamp
            )
            saveTrip(updatedTrip)
            Logging.d("TripRepository", "Saved trip completion timestamp to DB: trip=$tripId, timestamp=$completionTimestamp")
        }
    }

    // Update remaining time/distance for the entire trip (route to destination)
    suspend fun updateTripRemaining(
        tripId: Int,
        remainingTimeToDestination: Long?,
        remainingDistanceToDestination: Double?
    ) {
        val trip = getTripById(tripId)
        if (trip != null) {
            Logging.d("TripRepository", "=== BEFORE UPDATE ===")
            Logging.d("TripRepository", "Current trip remaining_time_to_destination: ${trip.remaining_time_to_destination}")
            Logging.d("TripRepository", "Current trip remaining_distance_to_destination: ${trip.remaining_distance_to_destination}")
            Logging.d("TripRepository", "New remainingTimeToDestination: $remainingTimeToDestination")
            Logging.d("TripRepository", "New remainingDistanceToDestination: $remainingDistanceToDestination")
            
            val updatedTrip = trip.copy(
                remaining_time_to_destination = remainingTimeToDestination ?: trip.remaining_time_to_destination,
                remaining_distance_to_destination = remainingDistanceToDestination ?: trip.remaining_distance_to_destination
            )
            
            Logging.d("TripRepository", "=== AFTER COPY ===")
            Logging.d("TripRepository", "Updated trip remaining_time_to_destination: ${updatedTrip.remaining_time_to_destination}")
            Logging.d("TripRepository", "Updated trip remaining_distance_to_destination: ${updatedTrip.remaining_distance_to_destination}")
            
            saveTrip(updatedTrip)
            Logging.d("TripRepository", "Saved trip remaining progress to DB: trip=$tripId, time=${remainingTimeToDestination}, distance=${remainingDistanceToDestination}")
            
            // Verify the save by immediately fetching the trip again
            val verifyTrip = getTripById(tripId)
            if (verifyTrip != null) {
                Logging.d("TripRepository", "=== VERIFICATION AFTER SAVE ===")
                Logging.d("TripRepository", "Verified trip remaining_time_to_destination: ${verifyTrip.remaining_time_to_destination}")
                Logging.d("TripRepository", "Verified trip remaining_distance_to_destination: ${verifyTrip.remaining_distance_to_destination}")
            } else {
                Logging.e("TripRepository", "Failed to fetch trip for verification after save")
            }
        } else {
            Logging.e("TripRepository", "Trip not found for ID: $tripId")
        }
    }
    
    // Update vehicle current location
    suspend fun updateVehicleCurrentLocation(
        vehicleId: Int, 
        latitude: Double, 
        longitude: Double, 
        speed: Double,
        accuracy: Double,
        bearing: Double?
    ) {
        try {
            val timestamp = System.currentTimeMillis()
            
            // Store in VehicleLocationEntity (latest location per vehicle)
            val vehicleLocation = com.gocavgo.validator.database.VehicleLocationEntity(
                vehicleId = vehicleId,
                latitude = latitude,
                longitude = longitude,
                speed = speed,
                accuracy = accuracy,
                bearing = bearing,
                timestamp = timestamp
            )
            vehicleLocationDao.upsertVehicleLocation(vehicleLocation)
            Logging.d("TripRepository", "Vehicle location stored in VehicleLocationEntity: $latitude, $longitude, speed: $speed, accuracy: $accuracy, bearing: $bearing")
            
            // Update the vehicle location in the active trip (for MQTT)
            val activeTrip = getActiveTripByVehicle(vehicleId)
            if (activeTrip != null) {
                // Update the vehicle location in the active trip
                val updatedVehicle = activeTrip.vehicle.copy(
                    current_latitude = latitude,
                    current_longitude = longitude,
                    current_speed = speed,
                    last_location_update = timestamp
                )
                
                val updatedTrip = activeTrip.copy(vehicle = updatedVehicle)
                saveTrip(updatedTrip)
                
                Logging.d("TripRepository", "Vehicle location updated in trip: $latitude, $longitude, speed: $speed")
            } else {
                Logging.w("TripRepository", "No active trip found for vehicle $vehicleId, but location stored in VehicleLocationEntity")
            }
        } catch (e: Exception) {
            Logging.e("TripRepository", "Failed to update vehicle location: ${e.message}", e)
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
            
            Logging.d("TripRepository", "=== CLEANUP DEBUG ===")
            Logging.d("TripRepository", "Vehicle ID: $vehicleId")
            Logging.d("TripRepository", "Available trip IDs from remote: $availableTripIds")
            Logging.d("TripRepository", "Current trip IDs in database: $currentTripIds")
            Logging.d("TripRepository", "Trips to keep: ${currentTripIds.intersect(availableTripIds.toSet())}")
            Logging.d("TripRepository", "Trips to delete: ${currentTripIds.filter { it !in availableTripIds }}")
            
            // Delete trips that are not in the available list
            tripDao.deleteTripsNotInList(vehicleId, availableTripIds)
            
            // Get trip count after cleanup
            val afterCount = tripDao.getTripCountByVehicle(vehicleId)
            val deletedCount = beforeCount - afterCount
            
            Logging.d("TripRepository", "Cleanup result: $deletedCount trips deleted (before: $beforeCount, after: $afterCount)")
            Logging.d("TripRepository", "=== END CLEANUP DEBUG ===")
        } catch (e: Exception) {
            Logging.e("TripRepository", "Failed to cleanup unavailable trips: ${e.message}", e)
        }
    }
    
    // Get all trip IDs for a vehicle
    suspend fun getAllTripIdsByVehicle(vehicleId: Int): List<Int> {
        return tripDao.getAllTripIdsByVehicle(vehicleId)
    }
    
    // Get vehicle location from VehicleLocationEntity
    suspend fun getVehicleLocation(vehicleId: Int): com.gocavgo.validator.database.VehicleLocationEntity? {
        return vehicleLocationDao.getVehicleLocation(vehicleId)
    }
}
