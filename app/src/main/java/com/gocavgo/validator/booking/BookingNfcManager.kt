package com.gocavgo.validator.booking

import android.app.Activity
import android.content.Context
import android.os.Handler
import kotlinx.coroutines.withContext
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.navigator.TripSectionValidator
import com.gocavgo.validator.service.BookingService
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.gocavgo.validator.dataclass.ApiBooking
import com.gocavgo.validator.dataclass.ApiPayment
import com.gocavgo.validator.dataclass.ApiTicket
import com.gocavgo.validator.dataclass.BookingStatus
import com.gocavgo.validator.dataclass.PaymentStatus
import com.gocavgo.validator.dataclass.PaymentMethod
import com.gocavgo.validator.database.BookingEntity
import com.gocavgo.validator.database.PaymentEntity
import com.gocavgo.validator.database.TicketEntity
import com.gocavgo.validator.service.RemoteDataManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
    
    data class ValidationSuccessData(
        val ticketNumber: String,
        val passengerName: String,
        val pickupLocation: String,
        val dropoffLocation: String
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
    val validationSuccessData = mutableStateOf(ValidationSuccessData("", "", "", ""))
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
    private val database = AppDatabase.getDatabase(context)
    private val remoteDataManager = RemoteDataManager.getInstance()
    
    // Reference to App's TripSectionValidator for waypoint progress
    private var tripSectionValidator: TripSectionValidator? = null
    
    // Booking polling job
    private var bookingPollingJob: Job? = null
    
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
        
        // Start polling if trip is active
        if (trip != null) {
            startBookingPolling()
        }
    }
    
    /**
     * Update trip data and refresh waypoint name and passenger counts
     */
    fun updateTrip(trip: TripResponse) {
        currentTrip = trip
        updateWaypointName()
        updatePassengerCounts()
        
        // Start polling if trip is active
        startBookingPolling()
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
        // Prevent adding digits if validation is in progress
        if (isValidationInProgress.value) {
            Logging.d(TAG, "Validation in progress, ignoring digit input")
            return
        }
        
        // Limit input to 10 digits max
        if (currentInput.value.length < 10) {
            currentInput.value += digit
            
            // Auto-validate when input reaches exactly 6 digits
            if (currentInput.value.length == 6) {
                Logging.d(TAG, "Input reached 6 digits, auto-triggering validation")
                validateTicket(currentInput.value)
            }
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
    
    /**
     * Get current waypoint location ID for validation
     * Returns the location ID of the next waypoint that hasn't been passed
     */
    private fun getCurrentWaypointLocationId(): Int? {
        val trip = currentTrip ?: return null
        
        // Find next waypoint that hasn't been passed
        val nextWaypoint = trip.waypoints
            .firstOrNull { !it.is_passed && it.is_next }
            ?: trip.waypoints.firstOrNull { !it.is_passed }
        
        return nextWaypoint?.location_id
    }
    
    /**
     * Validate ticket when 6-digit ticket number is entered
     * Checks ticket existence, trip match, usage status, and location match
     */
    private fun validateTicket(ticketNumber: String) {
        val trip = currentTrip
        if (trip == null) {
            Logging.w(TAG, "Cannot validate ticket: no active trip")
            handler.post {
                validationFailureMessage.value = "No active trip"
                showValidationFailure.value = true
                // Auto-dismiss failure dialog after 2.5 seconds
                handler.postDelayed({
                    showValidationFailure.value = false
                    Logging.d(TAG, "Auto-dismissed validation failure dialog")
                }, 2500)
                // Clear input after delay
                handler.postDelayed({
                    forceClearInput()
                }, 2000)
            }
            return
        }
        
        // Set validation in progress
        isValidationInProgress.value = true
        Logging.d(TAG, "Starting ticket validation for ticket number: $ticketNumber")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get ticket from database
                val ticket = databaseManager.getTicketByNumber(ticketNumber)
                
                if (ticket == null) {
                    Logging.w(TAG, "Ticket not found: $ticketNumber")
                    handler.post {
                        isValidationInProgress.value = false
                        validationFailureMessage.value = "Ticket not found"
                        showValidationFailure.value = true
                        // Auto-dismiss failure dialog after 2.5 seconds
                        handler.postDelayed({
                            showValidationFailure.value = false
                            Logging.d(TAG, "Auto-dismissed validation failure dialog")
                        }, 2500)
                        // Clear input after delay
                        handler.postDelayed({
                            forceClearInput()
                        }, 2000)
                    }
                    return@launch
                }
                
                // Get booking to check trip_id
                val booking = databaseManager.getBookingById(ticket.booking_id)
                if (booking == null) {
                    Logging.w(TAG, "Booking not found for ticket: $ticketNumber")
                    handler.post {
                        isValidationInProgress.value = false
                        validationFailureMessage.value = "Booking not found"
                        showValidationFailure.value = true
                        // Auto-dismiss failure dialog after 2.5 seconds
                        handler.postDelayed({
                            showValidationFailure.value = false
                            Logging.d(TAG, "Auto-dismissed validation failure dialog")
                        }, 2500)
                        // Clear input after delay
                        handler.postDelayed({
                            forceClearInput()
                        }, 2000)
                    }
                    return@launch
                }
                
                // Validate ticket belongs to current trip
                if (booking.trip_id != trip.id) {
                    Logging.w(TAG, "Ticket belongs to different trip: ticket trip=${booking.trip_id}, current trip=${trip.id}")
                    handler.post {
                        isValidationInProgress.value = false
                        validationFailureMessage.value = "Ticket not valid for this trip"
                        showValidationFailure.value = true
                        // Auto-dismiss failure dialog after 2.5 seconds
                        handler.postDelayed({
                            showValidationFailure.value = false
                            Logging.d(TAG, "Auto-dismissed validation failure dialog")
                        }, 2500)
                        // Clear input after delay
                        handler.postDelayed({
                            forceClearInput()
                        }, 2000)
                    }
                    return@launch
                }
                
                // Check if ticket is already used
                if (ticket.is_used) {
                    Logging.w(TAG, "Ticket already used: $ticketNumber")
                    handler.post {
                        isValidationInProgress.value = false
                        validationFailureMessage.value = "Ticket already used"
                        showValidationFailure.value = true
                        // Auto-dismiss failure dialog after 2.5 seconds
                        handler.postDelayed({
                            showValidationFailure.value = false
                            Logging.d(TAG, "Auto-dismissed validation failure dialog")
                        }, 2500)
                        // Clear input after delay
                        handler.postDelayed({
                            forceClearInput()
                        }, 2000)
                    }
                    return@launch
                }
                
                // Validate ticket location matches current waypoint
                val currentLocationId = getCurrentWaypointLocationId()
                val pickupLocationId = booking.pickup_location_id.toIntOrNull()
                val dropoffLocationId = booking.dropoff_location_id.toIntOrNull()
                
                val isValidLocation = when {
                    currentLocationId == null -> {
                        // No current waypoint - allow validation (might be at origin or destination)
                        Logging.d(TAG, "No current waypoint, allowing validation")
                        true
                    }
                    currentLocationId == pickupLocationId -> {
                        // Current location matches pickup location
                        Logging.d(TAG, "Ticket pickup location matches current waypoint")
                        true
                    }
                    currentLocationId == dropoffLocationId -> {
                        // Current location matches dropoff location
                        Logging.d(TAG, "Ticket dropoff location matches current waypoint")
                        true
                    }
                    else -> {
                        // Location mismatch
                        Logging.w(TAG, "Ticket location mismatch: current=$currentLocationId, pickup=$pickupLocationId, dropoff=$dropoffLocationId")
                        false
                    }
                }
                
                if (!isValidLocation) {
                    handler.post {
                        isValidationInProgress.value = false
                        validationFailureMessage.value = "Ticket not valid for current location"
                        showValidationFailure.value = true
                        // Auto-dismiss failure dialog after 2.5 seconds
                        handler.postDelayed({
                            showValidationFailure.value = false
                            Logging.d(TAG, "Auto-dismissed validation failure dialog")
                        }, 2500)
                        // Clear input after delay
                        handler.postDelayed({
                            forceClearInput()
                        }, 2000)
                    }
                    return@launch
                }
                
                // Ticket is valid - mark as used
                val currentTime = System.currentTimeMillis()
                val vehicleId = trip.vehicle_id.toString()
                
                try {
                    database.ticketDao().markTicketAsUsed(
                        ticketId = ticket.id,
                        isUsed = true,
                        usedAt = currentTime,
                        validatedBy = vehicleId,
                        updatedAt = currentTime
                    )
                    Logging.d(TAG, "Ticket marked as used: $ticketNumber, validated by vehicle: $vehicleId")
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to mark ticket as used: ${e.message}", e)
                    handler.post {
                        isValidationInProgress.value = false
                        validationFailureMessage.value = "Failed to validate ticket"
                        showValidationFailure.value = true
                        // Auto-dismiss failure dialog after 2.5 seconds
                        handler.postDelayed({
                            showValidationFailure.value = false
                            Logging.d(TAG, "Auto-dismissed validation failure dialog")
                        }, 2500)
                        // Clear input after delay
                        handler.postDelayed({
                            forceClearInput()
                        }, 2000)
                    }
                    return@launch
                }
                
                // Validation successful - prepare success data with passenger name and locations
                val passengerName = booking.user_name
                val pickupLocation = ticket.pickup_location_name
                val dropoffLocation = ticket.dropoff_location_name
                
                handler.post {
                    isValidationInProgress.value = false
                    validationSuccessData.value = ValidationSuccessData(
                        ticketNumber = ticketNumber,
                        passengerName = passengerName,
                        pickupLocation = pickupLocation,
                        dropoffLocation = dropoffLocation
                    )
                    showValidationSuccess.value = true
                    Logging.d(TAG, "Ticket validation successful: $ticketNumber, passenger: $passengerName, route: $pickupLocation → $dropoffLocation")
                    
                    // Refresh passenger counts after validation
                    updatePassengerCounts()
                    
                    // Auto-dismiss success dialog after 3 seconds (longer to read the info)
                    handler.postDelayed({
                        showValidationSuccess.value = false
                        Logging.d(TAG, "Auto-dismissed validation success dialog")
                    }, 3000)
                    
                    // Clear input after short delay
                    handler.postDelayed({
                        forceClearInput()
                    }, 1500)
                }
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error validating ticket: ${e.message}", e)
                handler.post {
                    isValidationInProgress.value = false
                    validationFailureMessage.value = "Validation error: ${e.message}"
                    showValidationFailure.value = true
                    // Auto-dismiss failure dialog after 2.5 seconds
                    handler.postDelayed({
                        showValidationFailure.value = false
                        Logging.d(TAG, "Auto-dismissed validation failure dialog")
                    }, 2500)
                    // Clear input after delay
                    handler.postDelayed({
                        forceClearInput()
                    }, 2000)
                }
            }
        }
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
        // Stop polling when activity pauses
        stopBookingPolling()
    }
    
    /**
     * Parse ISO 8601 date string to Long timestamp
     * Handles formats: "2025-11-14T08:02:56.165024Z" and "2025-11-18T07:05:00Z"
     */
    private fun parseIso8601Date(dateString: String): Long {
        return try {
            // Try with milliseconds first
            val formatWithMillis = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            try {
                formatWithMillis.parse(dateString)?.time ?: 0L
            } catch (e: Exception) {
                // Try without milliseconds
                val formatWithoutMillis = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                formatWithoutMillis.parse(dateString)?.time ?: 0L
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to parse date: $dateString", e)
            0L
        }
    }
    
    /**
     * Convert ApiBooking to BookingEntity
     */
    private fun convertApiBookingToEntity(apiBooking: ApiBooking): BookingEntity? {
        return try {
            BookingEntity(
                id = apiBooking.id,
                trip_id = apiBooking.trip_id,
                user_id = apiBooking.user_id,
                user_email = apiBooking.user_email,
                user_phone = apiBooking.user_phone ?: "N/A",
                user_name = apiBooking.user_name ?: "Unknown",
                pickup_location_id = apiBooking.pickup_location_id ?: "",
                dropoff_location_id = apiBooking.dropoff_location_id ?: "",
                number_of_tickets = apiBooking.number_of_tickets ?: 1,
                total_amount = apiBooking.total_amount ?: 0.0,
                status = BookingStatus.fromString(apiBooking.status),
                booking_reference = apiBooking.booking_reference ?: "",
                created_at = parseIso8601Date(apiBooking.created_at),
                updated_at = parseIso8601Date(apiBooking.updated_at)
            )
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to convert ApiBooking to BookingEntity: ${e.message}", e)
            null
        }
    }
    
    /**
     * Convert ApiPayment to PaymentEntity
     */
    private fun convertApiPaymentToEntity(apiPayment: ApiPayment): PaymentEntity? {
        return try {
            PaymentEntity(
                id = apiPayment.id,
                booking_id = apiPayment.booking_id,
                amount = apiPayment.amount,
                payment_method = PaymentMethod.fromString(apiPayment.payment_method),
                status = PaymentStatus.fromString(apiPayment.status),
                transaction_id = apiPayment.transaction_id,
                payment_data = apiPayment.payment_data,
                created_at = parseIso8601Date(apiPayment.created_at),
                updated_at = parseIso8601Date(apiPayment.updated_at)
            )
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to convert ApiPayment to PaymentEntity: ${e.message}", e)
            null
        }
    }
    
    /**
     * Convert ApiTicket to TicketEntity
     */
    private fun convertApiTicketToEntity(apiTicket: ApiTicket): TicketEntity? {
        return try {
            TicketEntity(
                id = apiTicket.id,
                booking_id = apiTicket.booking_id,
                ticket_number = apiTicket.ticket_number,
                qr_code = apiTicket.qr_code,
                is_used = apiTicket.is_used,
                used_at = null, // API doesn't provide this, will be set when validated
                validated_by = null, // API doesn't provide this, will be set when validated
                created_at = parseIso8601Date(apiTicket.created_at),
                updated_at = parseIso8601Date(apiTicket.updated_at),
                pickup_location_name = apiTicket.pickup_location_name ?: "",
                dropoff_location_name = apiTicket.dropoff_location_name ?: "",
                car_plate = apiTicket.car_plate ?: "",
                car_company = apiTicket.car_company ?: "",
                pickup_time = apiTicket.pickup_time?.let { parseIso8601Date(it) } ?: parseIso8601Date(apiTicket.created_at)
            )
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to convert ApiTicket to TicketEntity: ${e.message}", e)
            null
        }
    }
    
    /**
     * Determine if booking should be updated based on business logic
     */
    private suspend fun shouldUpdateBooking(existing: BookingEntity, incoming: BookingEntity): Boolean {
        // Always update if timestamps are different (incoming is newer)
        if (incoming.updated_at > existing.updated_at) {
            Logging.d(TAG, "Booking ${existing.id} has newer timestamp, updating")
            return true
        }
        
        // Don't update if incoming is older
        if (incoming.updated_at < existing.updated_at) {
            Logging.d(TAG, "Booking ${existing.id} incoming data is older, skipping update")
            return false
        }
        
        // If timestamps are equal, check for meaningful changes
        val hasChanges = existing.status != incoming.status ||
                        existing.total_amount != incoming.total_amount ||
                        existing.pickup_location_id != incoming.pickup_location_id ||
                        existing.dropoff_location_id != incoming.dropoff_location_id
        
        if (hasChanges) {
            Logging.d(TAG, "Booking ${existing.id} has meaningful changes, updating")
            return true
        }
        
        Logging.d(TAG, "Booking ${existing.id} no changes detected, skipping update")
        return false
    }
    
    /**
     * Determine if payment should be updated based on business logic
     */
    private suspend fun shouldUpdatePayment(existing: PaymentEntity, incoming: PaymentEntity): Boolean {
        // Always update if timestamps are different (incoming is newer)
        if (incoming.updated_at > existing.updated_at) {
            Logging.d(TAG, "Payment ${existing.id} has newer timestamp, updating")
            return true
        }
        
        // Don't update if incoming is older
        if (incoming.updated_at < existing.updated_at) {
            Logging.d(TAG, "Payment ${existing.id} incoming data is older, skipping update")
            return false
        }
        
        // If timestamps are equal, check for meaningful changes
        val hasChanges = existing.status != incoming.status ||
                        existing.amount != incoming.amount ||
                        existing.payment_method != incoming.payment_method ||
                        existing.payment_data != incoming.payment_data
        
        if (hasChanges) {
            Logging.d(TAG, "Payment ${existing.id} has meaningful changes, updating")
            return true
        }
        
        Logging.d(TAG, "Payment ${existing.id} no changes detected, skipping update")
        return false
    }
    
    /**
     * Determine if ticket should be updated based on business logic
     */
    private suspend fun shouldUpdateTicket(existing: TicketEntity, incoming: TicketEntity): Boolean {
        // Always update if timestamps are different (incoming is newer)
        if (incoming.updated_at > existing.updated_at) {
            Logging.d(TAG, "Ticket ${existing.id} has newer timestamp, updating")
            return true
        }
        
        // Don't update if incoming is older
        if (incoming.updated_at < existing.updated_at) {
            Logging.d(TAG, "Ticket ${existing.id} incoming data is older, skipping update")
            return false
        }
        
        // If timestamps are equal, check for meaningful changes
        // Note: Don't overwrite is_used, used_at, validated_by if they were set locally (local validation takes precedence)
        val hasChanges = (existing.is_used == false && incoming.is_used == true) || // Allow setting to used
                        (existing.pickup_location_name != incoming.pickup_location_name) ||
                        (existing.dropoff_location_name != incoming.dropoff_location_name) ||
                        (existing.car_plate != incoming.car_plate) ||
                        (existing.car_company != incoming.car_company)
        
        if (hasChanges) {
            Logging.d(TAG, "Ticket ${existing.id} has meaningful changes, updating")
            return true
        }
        
        Logging.d(TAG, "Ticket ${existing.id} no changes detected, skipping update")
        return false
    }
    
    /**
     * Handle booking conflict resolution
     */
    private suspend fun handleBookingConflict(bookingDao: com.gocavgo.validator.database.BookingDao, newBooking: BookingEntity): String {
        return try {
            val existingBooking = bookingDao.getBookingById(newBooking.id)
            
            if (existingBooking == null) {
                // New booking - insert it
                bookingDao.insertBooking(newBooking)
                "INSERTED_NEW"
            } else {
                // Existing booking - check if update is needed
                val shouldUpdate = shouldUpdateBooking(existingBooking, newBooking)
                
                if (shouldUpdate) {
                    bookingDao.updateBooking(newBooking)
                    "UPDATED_EXISTING"
                } else {
                    "SKIPPED_NO_CHANGES"
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling booking conflict: ${e.message}", e)
            "ERROR: ${e.message}"
        }
    }
    
    /**
     * Handle payment conflict resolution
     */
    private suspend fun handlePaymentConflict(paymentDao: com.gocavgo.validator.database.PaymentDao, newPayment: PaymentEntity): String {
        return try {
            val existingPayment = paymentDao.getPaymentById(newPayment.id)
            
            if (existingPayment == null) {
                // New payment - insert it
                paymentDao.insertPayment(newPayment)
                "INSERTED_NEW"
            } else {
                // Existing payment - check if update is needed
                val shouldUpdate = shouldUpdatePayment(existingPayment, newPayment)
                
                if (shouldUpdate) {
                    paymentDao.updatePayment(newPayment)
                    "UPDATED_EXISTING"
                } else {
                    "SKIPPED_NO_CHANGES"
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling payment conflict: ${e.message}", e)
            "ERROR: ${e.message}"
        }
    }
    
    /**
     * Handle ticket conflicts resolution
     */
    private suspend fun handleTicketConflicts(ticketDao: com.gocavgo.validator.database.TicketDao, newTickets: List<TicketEntity>): Map<String, String> {
        val results = mutableMapOf<String, String>()
        
        for (newTicket in newTickets) {
            try {
                val existingTicket = ticketDao.getTicketById(newTicket.id)
                
                if (existingTicket == null) {
                    // New ticket - insert it
                    ticketDao.insertTicket(newTicket)
                    results[newTicket.id] = "INSERTED_NEW"
                } else {
                    // Existing ticket - check if update is needed
                    // Preserve local validation state (is_used, used_at, validated_by) if validated locally
                    val ticketToUpdate = if (existingTicket.is_used && existingTicket.validated_by != null) {
                        // Local validation exists (validated by this vehicle), preserve it but update other fields
                        newTicket.copy(
                            is_used = existingTicket.is_used,
                            used_at = existingTicket.used_at,
                            validated_by = existingTicket.validated_by
                        )
                    } else {
                        // No local validation, use API value
                        newTicket
                    }
                    
                    val shouldUpdate = shouldUpdateTicket(existingTicket, ticketToUpdate)
                    
                    if (shouldUpdate) {
                        ticketDao.updateTicket(ticketToUpdate)
                        results[newTicket.id] = "UPDATED_EXISTING"
                    } else {
                        results[newTicket.id] = "SKIPPED_NO_CHANGES"
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error handling ticket conflict for ${newTicket.id}: ${e.message}", e)
                results[newTicket.id] = "ERROR: ${e.message}"
            }
        }
        
        return results
    }
    
    /**
     * Fetch bookings from API and sync to database
     */
    fun fetchAndSyncBookings(tripId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Logging.d(TAG, "=== FETCHING BOOKINGS FOR TRIP $tripId FROM API ===")
                
                val result = remoteDataManager.getTripBookings(tripId)
                
                when (result) {
                    is com.gocavgo.validator.service.RemoteResult.Success -> {
                        val apiBookings = result.data
                        Logging.d(TAG, "Fetched ${apiBookings.size} bookings from API")
                        
                        val bookingDao = database.bookingDao()
                        val paymentDao = database.paymentDao()
                        val ticketDao = database.ticketDao()
                        
                        var insertedBookings = 0
                        var updatedBookings = 0
                        var skippedBookings = 0
                        var insertedPayments = 0
                        var updatedPayments = 0
                        var skippedPayments = 0
                        var insertedTickets = 0
                        var updatedTickets = 0
                        var skippedTickets = 0
                        
                        // Process each booking
                        for (apiBooking in apiBookings) {
                            try {
                                // Convert ApiBooking to BookingEntity
                                val bookingEntity = convertApiBookingToEntity(apiBooking)
                                if (bookingEntity == null) {
                                    Logging.w(TAG, "Skipping booking ${apiBooking.id} - conversion failed")
                                    continue
                                }
                                
                                // Handle booking conflict
                                val bookingResult = handleBookingConflict(bookingDao, bookingEntity)
                                when (bookingResult) {
                                    "INSERTED_NEW" -> insertedBookings++
                                    "UPDATED_EXISTING" -> updatedBookings++
                                    "SKIPPED_NO_CHANGES" -> skippedBookings++
                                }
                                
                                // Process payment if available
                                apiBooking.payment?.let { apiPayment ->
                                    val paymentEntity = convertApiPaymentToEntity(apiPayment)
                                    if (paymentEntity != null) {
                                        val paymentResult = handlePaymentConflict(paymentDao, paymentEntity)
                                        when (paymentResult) {
                                            "INSERTED_NEW" -> insertedPayments++
                                            "UPDATED_EXISTING" -> updatedPayments++
                                            "SKIPPED_NO_CHANGES" -> skippedPayments++
                                        }
                                    }
                                }
                                
                                // Process tickets if available
                                apiBooking.tickets?.let { apiTickets ->
                                    val ticketEntities = apiTickets.mapNotNull { convertApiTicketToEntity(it) }
                                    if (ticketEntities.isNotEmpty()) {
                                        val ticketResults = handleTicketConflicts(ticketDao, ticketEntities)
                                        ticketResults.values.forEach { result ->
                                            when (result) {
                                                "INSERTED_NEW" -> insertedTickets++
                                                "UPDATED_EXISTING" -> updatedTickets++
                                                "SKIPPED_NO_CHANGES" -> skippedTickets++
                                            }
                                        }
                                    }
                                }
                                
                            } catch (e: Exception) {
                                Logging.e(TAG, "Error processing booking ${apiBooking.id}: ${e.message}", e)
                            }
                        }
                        
                        Logging.d(TAG, "=== BOOKING SYNC COMPLETE ===")
                        Logging.d(TAG, "Bookings: $insertedBookings inserted, $updatedBookings updated, $skippedBookings skipped")
                        Logging.d(TAG, "Payments: $insertedPayments inserted, $updatedPayments updated, $skippedPayments skipped")
                        Logging.d(TAG, "Tickets: $insertedTickets inserted, $updatedTickets updated, $skippedTickets skipped")
                        Logging.d(TAG, "===============================")
                        
                        // Refresh passenger counts after sync
                        withContext(Dispatchers.Main) {
                            updatePassengerCounts()
                        }
                    }
                    is com.gocavgo.validator.service.RemoteResult.Error -> {
                        Logging.e(TAG, "Failed to fetch bookings from API: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error fetching and syncing bookings: ${e.message}", e)
            }
        }
    }
    
    /**
     * Start periodic booking polling (every 5 minutes)
     */
    private fun startBookingPolling() {
        // Stop existing polling if any
        stopBookingPolling()
        
        val trip = currentTrip ?: return
        
        // Only poll if trip is active (IN_PROGRESS or SCHEDULED)
        val isActiveTrip = trip.status.equals("IN_PROGRESS", ignoreCase = true) ||
                          trip.status.equals("SCHEDULED", ignoreCase = true)
        
        if (!isActiveTrip) {
            Logging.d(TAG, "Trip ${trip.id} is not active (status: ${trip.status}), skipping polling")
            return
        }
        
        Logging.d(TAG, "Starting booking polling for trip ${trip.id} (every 5 minutes)")
        
        bookingPollingJob = lifecycleScope.launch(Dispatchers.IO) {
            // Initial fetch immediately
            fetchAndSyncBookings(trip.id)
            
            // Then poll every 5 minutes
            while (isActive && currentTrip?.id == trip.id) {
                delay(5 * 60 * 1000) // 5 minutes
                
                // Check if trip is still active
                val currentTripSnapshot = currentTrip
                if (currentTripSnapshot == null || currentTripSnapshot.id != trip.id) {
                    Logging.d(TAG, "Trip changed or cleared, stopping polling")
                    break
                }
                
                val stillActive = currentTripSnapshot.status.equals("IN_PROGRESS", ignoreCase = true) ||
                                 currentTripSnapshot.status.equals("SCHEDULED", ignoreCase = true)
                
                if (!stillActive) {
                    Logging.d(TAG, "Trip ${trip.id} is no longer active, stopping polling")
                    break
                }
                
                // Fetch and sync bookings
                fetchAndSyncBookings(trip.id)
            }
        }
    }
    
    /**
     * Stop periodic booking polling
     */
    private fun stopBookingPolling() {
        bookingPollingJob?.cancel()
        bookingPollingJob = null
        Logging.d(TAG, "Stopped booking polling")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopBookingPolling()
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
        validationSuccessData.value = ValidationSuccessData("", "", "", "")
    }
}