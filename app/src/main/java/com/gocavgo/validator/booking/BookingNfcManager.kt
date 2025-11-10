package com.gocavgo.validator.booking

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.navigator.TripSectionValidator
import com.gocavgo.validator.service.BookingService
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manager for booking and NFC operations in Auto Mode
 * Handles ticket validation, booking creation, passenger counts, and waypoint name updates
 */
class BookingNfcManager(
    private val context: Context,
    private val lifecycleScope: CoroutineScope,
    private val databaseManager: DatabaseManager,
    private val handler: Handler
) {
    companion object {
        private const val TAG = "BookingNfcManager"
    }
    
    // Data classes for UI state
    data class BookingSuccessData(
        val ticketNumber: String,
        val fromLocation: String,
        val toLocation: String,
        val price: String
    )
    
    data class MqttNotificationData(
        val passengerName: String,
        val pickup: String,
        val dropoff: String,
        val numTickets: Int,
        val isPaid: Boolean
    )
    
    enum class PassengerListType {
        PICKUP, DROPOFF
    }
    
    // State properties (MutableState for Compose)
    val currentInput = mutableStateOf("")
    val isValidationInProgress = mutableStateOf(false)
    val nextWaypointName = mutableStateOf("")
    val pickupCount = mutableStateOf(0)
    val dropoffCount = mutableStateOf(0)
    val showBookingSuccess = mutableStateOf(false)
    val showBookingFailure = mutableStateOf(false)
    val showValidationSuccess = mutableStateOf(false)
    val showValidationFailure = mutableStateOf(false)
    val showMqttNotification = mutableStateOf(false)
    val bookingSuccessData = mutableStateOf(BookingSuccessData("", "", "", ""))
    val bookingFailureMessage = mutableStateOf("")
    val validationSuccessTicket = mutableStateOf("")
    val validationFailureMessage = mutableStateOf("")
    val mqttNotificationData = mutableStateOf(MqttNotificationData("", "", "", 0, false))
    val showPassengerListDialog = mutableStateOf(false)
    val passengerListType = mutableStateOf(PassengerListType.PICKUP)
    val passengerList = mutableStateOf<List<BookingService.PassengerInfo>>(emptyList())
    val showDestinationSelectionDialog = mutableStateOf(false)
    val availableDestinations = mutableStateOf<List<com.gocavgo.validator.navigator.AvailableDestination>>(emptyList())
    val currentLocationForDialog = mutableStateOf("")
    
    // Current trip reference
    private var currentTrip: TripResponse? = null
    private val bookingService = BookingService(context)
    
    // Reference to App's TripSectionValidator for waypoint progress
    private var tripSectionValidator: TripSectionValidator? = null
    
    /**
     * Set TripSectionValidator reference for waypoint progress updates
     */
    fun setTripSectionValidator(validator: TripSectionValidator?) {
        tripSectionValidator = validator
    }
    
    /**
     * Initialize manager with trip data
     */
    fun initialize(trip: TripResponse?) {
        currentTrip = trip
        updateWaypointName()
        updatePassengerCounts()
    }
    
    /**
     * Update trip data and refresh waypoint name and passenger counts
     */
    fun updateTrip(trip: TripResponse) {
        currentTrip = trip
        updateWaypointName()
        updatePassengerCounts()
    }
    
    /**
     * Update next waypoint name from TripSectionValidator progress data
     * Gets the next waypoint from waypoint progress info
     */
    private fun updateWaypointName() {
        // Try to get waypoint name from TripSectionValidator progress first
        val progress = tripSectionValidator?.getCurrentWaypointProgress() ?: emptyList()
        val nextWaypointProgress = progress.find { it.isNext } 
            ?: progress.firstOrNull { !it.isPassed }
        
        if (nextWaypointProgress != null) {
            // Extract clean waypoint name (remove prefixes like "Waypoint X: ", "Origin: ", etc.)
            val waypointName = extractWaypointName(nextWaypointProgress.waypointName)
            nextWaypointName.value = waypointName
            Logging.d(TAG, "Next waypoint updated from progress: $waypointName")
            return
        }
        
        // Fallback to trip data if progress not available
        val trip = currentTrip ?: return
        
        // Find next waypoint that hasn't been passed
        val nextWaypoint = trip.waypoints
            .firstOrNull { !it.is_passed && it.is_next }
            ?: trip.waypoints.firstOrNull { !it.is_passed }
        
        if (nextWaypoint != null) {
            val waypointName = nextWaypoint.location.custom_name 
                ?: nextWaypoint.location.google_place_name 
                ?: "Unknown Waypoint"
            nextWaypointName.value = waypointName
            Logging.d(TAG, "Next waypoint updated from trip data: $waypointName")
        } else {
            // All waypoints passed, show destination
            val destinationName = trip.route.destination.custom_name 
                ?: trip.route.destination.google_place_name 
                ?: "Destination"
            nextWaypointName.value = destinationName
            Logging.d(TAG, "All waypoints passed, showing destination: $destinationName")
        }
    }
    
    /**
     * Extracts clean waypoint name from WaypointProgressInfo.waypointName
     * Removes prefixes like "Origin: ", "Waypoint X: ", "Destination: "
     */
    private fun extractWaypointName(waypointName: String): String {
        return when {
            waypointName.startsWith("Origin: ") -> waypointName.removePrefix("Origin: ")
            waypointName.startsWith("Destination: ") -> waypointName.removePrefix("Destination: ")
            waypointName.contains(": ") -> {
                // Handle "Waypoint X: [name]" format
                val colonIndex = waypointName.indexOf(": ")
                if (colonIndex >= 0 && colonIndex < waypointName.length - 2) {
                    waypointName.substring(colonIndex + 2)
                } else {
                    waypointName
                }
            }
            else -> waypointName
        }
    }
    
    /**
     * Update passenger counts for current location
     */
    fun updatePassengerCounts() {
        val trip = currentTrip ?: return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get current waypoint (next waypoint that hasn't been passed)
                val nextWaypoint = trip.waypoints
                    .firstOrNull { !it.is_passed && it.is_next }
                    ?: trip.waypoints.firstOrNull { !it.is_passed }
                
                if (nextWaypoint != null) {
                    val locationId = nextWaypoint.location_id
                    
                    // Get passenger counts for this location
                    val pickupCountResult = databaseManager.getPassengerCountsForLocation(trip.id, locationId)
                    val dropoffCountResult = databaseManager.getPassengerCountsForLocation(trip.id, locationId)
                    
                    // Count passengers picking up at this location
                    val pickupPassengers = databaseManager.getPassengersPickingUpAtLocation(trip.id, locationId)
                    val pickup = pickupPassengers.size
                    
                    // Count passengers dropping off at this location
                    val dropoffPassengers = databaseManager.getPassengersDroppingOffAtLocation(trip.id, locationId)
                    val dropoff = dropoffPassengers.size
                    
                    handler.post {
                        pickupCount.value = pickup
                        dropoffCount.value = dropoff
                        Logging.d(TAG, "Passenger counts updated: pickup=$pickup, dropoff=$dropoff")
                    }
                } else {
                    handler.post {
                        pickupCount.value = 0
                        dropoffCount.value = 0
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error updating passenger counts: ${e.message}", e)
            }
        }
    }
    
    // NFC input methods
    fun addDigit(digit: String) {
        if (currentInput.value.length < 10) {
            currentInput.value += digit
        }
    }
    
    fun deleteLastDigit() {
        if (currentInput.value.isNotEmpty()) {
            currentInput.value = currentInput.value.dropLast(1)
        }
    }
    
    fun forceClearInput() {
        currentInput.value = ""
    }
    
    // Passenger list methods
    fun showPickupPassengerList() {
        val trip = currentTrip ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nextWaypoint = trip.waypoints
                    .firstOrNull { !it.is_passed && it.is_next }
                    ?: trip.waypoints.firstOrNull { !it.is_passed }
                
                if (nextWaypoint != null) {
                    val passengers = databaseManager.getPassengersPickingUpAtLocation(trip.id, nextWaypoint.location_id)
                    handler.post {
                        passengerList.value = passengers
                        passengerListType.value = PassengerListType.PICKUP
                        showPassengerListDialog.value = true
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error showing pickup passenger list: ${e.message}", e)
            }
        }
    }
    
    fun showDropoffPassengerList() {
        val trip = currentTrip ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nextWaypoint = trip.waypoints
                    .firstOrNull { !it.is_passed && it.is_next }
                    ?: trip.waypoints.firstOrNull { !it.is_passed }
                
                if (nextWaypoint != null) {
                    val passengers = databaseManager.getPassengersDroppingOffAtLocation(trip.id, nextWaypoint.location_id)
                    handler.post {
                        passengerList.value = passengers
                        passengerListType.value = PassengerListType.DROPOFF
                        showPassengerListDialog.value = true
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error showing dropoff passenger list: ${e.message}", e)
            }
        }
    }
    
    fun showPassengerDetails(bookingId: String) {
        // TODO: Implement passenger details view
        Logging.d(TAG, "Show passenger details for booking: $bookingId")
    }
    
    fun dismissPassengerList() {
        showPassengerListDialog.value = false
    }
    
    // Booking success/failure methods
    fun dismissBookingSuccess() {
        showBookingSuccess.value = false
    }
    
    fun dismissBookingFailure() {
        showBookingFailure.value = false
    }
    
    // Validation success/failure methods
    fun dismissValidationSuccess() {
        showValidationSuccess.value = false
    }
    
    fun dismissValidationFailure() {
        showValidationFailure.value = false
    }
    
    // MQTT notification methods
    fun dismissMqttNotification() {
        showMqttNotification.value = false
    }
    
    // Destination selection methods
    fun onDestinationSelected(destination: com.gocavgo.validator.navigator.AvailableDestination) {
        // TODO: Implement destination selection logic
        Logging.d(TAG, "Destination selected: ${destination.location.custom_name ?: destination.location.google_place_name}")
        showDestinationSelectionDialog.value = false
    }
    
    fun dismissDestinationSelection() {
        showDestinationSelectionDialog.value = false
    }
    
    // Activity lifecycle methods
    fun onActivityResume(activity: Activity) {
        // Refresh data when activity resumes
        updateWaypointName()
        updatePassengerCounts()
    }
    
    fun onActivityPause(activity: Activity) {
        // Clear input when activity pauses
        forceClearInput()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        currentTrip = null
        forceClearInput()
        showBookingSuccess.value = false
        showBookingFailure.value = false
        showValidationSuccess.value = false
        showValidationFailure.value = false
        showMqttNotification.value = false
        showPassengerListDialog.value = false
        showDestinationSelectionDialog.value = false
        pickupCount.value = 0
        dropoffCount.value = 0
        nextWaypointName.value = ""
    }
}