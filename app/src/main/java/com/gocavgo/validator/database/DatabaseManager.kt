package com.gocavgo.validator.database

import android.content.Context
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.repository.TripRepository
import com.gocavgo.validator.service.BookingService
import kotlinx.coroutines.flow.Flow

class DatabaseManager(context: Context) {
    
    private val tripRepository = TripRepository(context)
    private val bookingService = BookingService.getInstance(context)
    
    // Trip operations
    suspend fun saveTrip(trip: TripResponse) = tripRepository.saveTrip(trip)
    
    suspend fun getTripById(tripId: Int): TripResponse? = tripRepository.getTripById(tripId)
    
    suspend fun getLatestTripByVehicle(vehicleId: Int): TripResponse? = 
        tripRepository.getLatestTripByVehicle(vehicleId)
    
    fun getTripsByVehicle(vehicleId: Int): Flow<List<TripResponse>> = 
        tripRepository.getTripsByVehicle(vehicleId)
    
    suspend fun updateTrip(trip: TripResponse) = tripRepository.updateTrip(trip)
    
    suspend fun deleteTrip(trip: TripResponse) = tripRepository.deleteTrip(trip)
    
    suspend fun deleteTripsByVehicle(vehicleId: Int) = tripRepository.deleteTripsByVehicle(vehicleId)
    
    suspend fun getTripCountByVehicle(vehicleId: Int): Int = 
        tripRepository.getTripCountByVehicle(vehicleId)
    
    // Remote sync operations
    suspend fun syncTripsFromRemote(vehicleId: Int, page: Int = 1, limit: Int = 20) = 
        tripRepository.fetchAndSaveTrips(vehicleId, page, limit)
    
    // Trip status management
    suspend fun getActiveTripByVehicle(vehicleId: Int): TripResponse? = 
        tripRepository.getActiveTripByVehicle(vehicleId)
    
    suspend fun hasActiveTrips(vehicleId: Int): Boolean = 
        tripRepository.hasActiveTrips(vehicleId)
    
    suspend fun updateTripStatus(tripId: Int, newStatus: String) = 
        tripRepository.updateTripStatus(tripId, newStatus)
    
    // Waypoint status management
    suspend fun updateWaypointStatus(tripId: Int, waypointId: Int, isPassed: Boolean) = 
        tripRepository.updateWaypointStatus(tripId, waypointId, isPassed)
    
    // Waypoint next status management
    suspend fun updateWaypointNextStatus(tripId: Int, waypointId: Int, isNext: Boolean) = 
        tripRepository.updateWaypointNextStatus(tripId, waypointId, isNext)
    
    // Waypoint remaining progress persistence
    suspend fun updateWaypointRemaining(
        tripId: Int,
        waypointId: Int,
        remainingTimeSeconds: Long?,
        remainingDistanceMeters: Double?
    ) {
        try {
            tripRepository.updateWaypointRemaining(tripId, waypointId, remainingTimeSeconds, remainingDistanceMeters)
            android.util.Log.d(
                "DatabaseManager",
                "Persisted remaining progress: trip=$tripId, waypoint=$waypointId, time=${remainingTimeSeconds}, distance=${remainingDistanceMeters}"
            )
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to persist remaining progress: ${e.message}", e)
            throw e
        }
    }

    // Waypoint original duration/length persistence (from route sections)
    suspend fun updateWaypointOriginalData(
        tripId: Int,
        waypointId: Int,
        waypointLengthMeters: Double,
        waypointTimeSeconds: Long
    ) {
        try {
            tripRepository.updateWaypointOriginalData(tripId, waypointId, waypointLengthMeters, waypointTimeSeconds)
            android.util.Log.d(
                "DatabaseManager",
                "Persisted original waypoint data: trip=$tripId, waypoint=$waypointId, length=${waypointLengthMeters}m, time=${waypointTimeSeconds}s"
            )
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to persist original waypoint data: ${e.message}", e)
            throw e
        }
    }

    // Waypoint passed timestamp persistence
    suspend fun updateWaypointPassedTimestamp(
        tripId: Int,
        waypointId: Int,
        passedTimestamp: Long
    ) {
        try {
            tripRepository.updateWaypointPassedTimestamp(tripId, waypointId, passedTimestamp)
            android.util.Log.d(
                "DatabaseManager",
                "Persisted waypoint passed timestamp: trip=$tripId, waypoint=$waypointId, timestamp=$passedTimestamp"
            )
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to persist waypoint passed timestamp: ${e.message}", e)
            throw e
        }
    }

    // Trip completion timestamp persistence
    suspend fun updateTripCompletionTimestamp(
        tripId: Int,
        completionTimestamp: Long
    ) {
        try {
            tripRepository.updateTripCompletionTimestamp(tripId, completionTimestamp)
            android.util.Log.d(
                "DatabaseManager",
                "Persisted trip completion timestamp: trip=$tripId, timestamp=$completionTimestamp"
            )
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to persist trip completion timestamp: ${e.message}", e)
            throw e
        }
    }

    // Trip remaining progress persistence (route to destination)
    suspend fun updateTripRemaining(
        tripId: Int,
        remainingTimeToDestination: Long?,
        remainingDistanceToDestination: Double?
    ) {
        try {
            tripRepository.updateTripRemaining(tripId, remainingTimeToDestination, remainingDistanceToDestination)
            android.util.Log.d(
                "DatabaseManager",
                "Persisted trip remaining progress: trip=$tripId, time=${remainingTimeToDestination}, distance=${remainingDistanceToDestination}"
            )
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to persist trip remaining progress: ${e.message}", e)
            throw e
        }
    }
    
    // Vehicle location tracking
    suspend fun updateVehicleCurrentLocation(vehicleId: Int, latitude: Double, longitude: Double, speed: Double) =
        tripRepository.updateVehicleCurrentLocation(vehicleId, latitude, longitude, speed)
    
    // Trip cleanup operations
    suspend fun cleanupUnavailableTrips(vehicleId: Int, availableTripIds: List<Int>) =
        tripRepository.cleanupUnavailableTrips(vehicleId, availableTripIds)
    
    suspend fun getAllTripIdsByVehicle(vehicleId: Int): List<Int> =
        tripRepository.getAllTripIdsByVehicle(vehicleId)
    
    // Booking operations
    suspend fun createBookingWithPaymentAndTicket(
        tripId: Int,
        nfcId: String,
        fromLocation: String,
        toLocation: String,
        price: Double,
        userPhone: String = "N/A",
        userName: String = "NFC User"
    ) = bookingService.createBookingWithPaymentAndTicket(
        tripId, nfcId, fromLocation, toLocation, price, userPhone, userName
    )
    
    suspend fun getBookingById(bookingId: String) = bookingService.getBookingById(bookingId)
    
    suspend fun getTicketsByBookingId(bookingId: String) = bookingService.getTicketsByBookingId(bookingId)
    
    suspend fun getPaymentsByBookingId(bookingId: String) = bookingService.getPaymentsByBookingId(bookingId)
    
    suspend fun getExistingBookingByNfcTag(nfcTagId: String) = bookingService.getExistingBookingByNfcTag(nfcTagId)
    
    suspend fun getTicketByNumber(ticketNumber: String) = bookingService.getTicketByNumber(ticketNumber)
    
    suspend fun countPaidPassengersForWaypoint(tripId: Int, waypointLocationName: String) = 
        bookingService.countPaidPassengersForWaypoint(tripId, waypointLocationName)
    
    companion object {
        @Volatile
        private var INSTANCE: DatabaseManager? = null
        
        fun getInstance(context: Context): DatabaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
