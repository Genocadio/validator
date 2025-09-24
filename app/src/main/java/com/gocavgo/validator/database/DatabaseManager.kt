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
