package com.gocavgo.validator.service

import android.content.Context
import android.util.Log
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.database.BookingEntity
import com.gocavgo.validator.database.PaymentEntity
import com.gocavgo.validator.database.TicketEntity
import com.gocavgo.validator.dataclass.BookingStatus
import com.gocavgo.validator.dataclass.PaymentMethod
import com.gocavgo.validator.dataclass.PaymentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

class BookingService(private val context: Context) {
    
    private val database = AppDatabase.getDatabase(context)
    private val bookingDao = database.bookingDao()
    private val paymentDao = database.paymentDao()
    private val ticketDao = database.ticketDao()
    
    /**
     * Creates a complete booking with USED status, payment with CARD type and NFC tag ID,
     * and generates a ticket with 6-digit ticket number
     */
    suspend fun createBookingWithPaymentAndTicket(
        tripId: Int,
        nfcId: String,
        fromLocation: String,
        toLocation: String,
        price: Double,
        userPhone: String = "N/A",
        userName: String = "NFC User"
    ): BookingCreationResult = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // Generate unique IDs
            val bookingId = generateUniqueId()
            val paymentId = generateUniqueId()
            val ticketId = generateUniqueId()
            
            // Generate 6-digit ticket number
            val ticketNumber = generateTicketNumberSync()
            
            // Create booking with USED status
            val booking = BookingEntity(
                id = bookingId,
                trip_id = tripId,
                user_id = null,
                user_email = null,
                user_phone = userPhone,
                user_name = userName,
                pickup_location_id = fromLocation,
                dropoff_location_id = toLocation,
                number_of_tickets = 1,
                total_amount = price,
                status = BookingStatus.USED,
                booking_reference = generateBookingReference(),
                created_at = currentTime,
                updated_at = currentTime
            )
            
            // Create payment with CARD type and NFC tag ID in payment_data
            val paymentData = "{\"nfc_tag_id\":\"$nfcId\",\"card_type\":\"NFC_CARD\"}"
            val payment = PaymentEntity(
                id = paymentId,
                booking_id = bookingId,
                amount = price,
                payment_method = PaymentMethod.CARD,
                status = PaymentStatus.COMPLETED,
                transaction_id = generateTransactionId(),
                payment_data = paymentData,
                created_at = currentTime,
                updated_at = currentTime
            )
            
            // Create ticket with 6-digit ticket number
            val qrCode = generateQrCode(ticketNumber, bookingId)
            val ticket = TicketEntity(
                id = ticketId,
                booking_id = bookingId,
                ticket_number = ticketNumber,
                qr_code = qrCode,
                is_used = true, // Ticket is immediately used since booking is USED
                used_at = currentTime,
                validated_by = "NFC_VALIDATOR",
                created_at = currentTime,
                updated_at = currentTime,
                pickup_location_name = fromLocation,
                dropoff_location_name = toLocation,
                car_plate = "N/A", // Will be updated with actual vehicle info
                car_company = "N/A", // Will be updated with actual company info
                pickup_time = currentTime
            )
            
            // Insert all records
            bookingDao.insertBooking(booking)
            paymentDao.insertPayment(payment)
            ticketDao.insertTicket(ticket)

            // Publish booking bundle to MQTT
            try {
                val mqtt = MqttService.getInstance()
                mqtt?.publishBookingBundle(
                    tripId = tripId.toString(),
                    booking = booking,
                    payment = payment,
                    tickets = listOf(ticket)
                )
            } catch (e: Exception) {
                Log.w("BookingService", "Failed to publish booking bundle: ${e.message}")
            }
            
            Log.d("BookingService", "=== BOOKING CREATED SUCCESSFULLY ===")
            Log.d("BookingService", "Booking ID: $bookingId")
            Log.d("BookingService", "Payment ID: $paymentId")
            Log.d("BookingService", "Ticket ID: $ticketId")
            Log.d("BookingService", "Ticket Number: $ticketNumber")
            Log.d("BookingService", "NFC Tag ID: $nfcId")
            Log.d("BookingService", "Amount: $price RWF")
            Log.d("BookingService", "From: $fromLocation")
            Log.d("BookingService", "To: $toLocation")
            Log.d("BookingService", "=====================================")
            
            BookingCreationResult.Success(
                bookingId = bookingId,
                paymentId = paymentId,
                ticketId = ticketId,
                ticketNumber = ticketNumber,
                qrCode = qrCode
            )
            
        } catch (e: Exception) {
            Log.e("BookingService", "Error creating booking: ${e.message}", e)
            BookingCreationResult.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    /**
     * Generates a unique 6-digit ticket number (synchronous version)
     */
    private fun generateTicketNumberSync(): String {
        // Generate 6-digit number (100000 to 999999)
        val number = Random.nextInt(100000, 1000000)
        val ticketNumber = number.toString()
        
        // Add timestamp to ensure uniqueness
        val timestamp = System.currentTimeMillis()
        val uniqueSuffix = (timestamp % 1000).toString().padStart(3, '0')
        
        return ticketNumber
    }
    
    /**
     * Generates a unique 6-digit ticket number (asynchronous version with database check)
     */
    private suspend fun generateTicketNumber(): String {
        var ticketNumber: String
        var attempts = 0
        val maxAttempts = 100
        
        do {
            // Generate 6-digit number (100000 to 999999)
            val number = Random.nextInt(100000, 1000000)
            ticketNumber = number.toString()
            attempts++
        } while (isTicketNumberExists(ticketNumber) && attempts < maxAttempts)
        
        if (attempts >= maxAttempts) {
            // Fallback: use timestamp-based number
            val timestamp = System.currentTimeMillis()
            ticketNumber = (timestamp % 1000000).toString().padStart(6, '0')
        }
        
        return ticketNumber
    }
    
    /**
     * Checks if ticket number already exists
     */
    private suspend fun isTicketNumberExists(ticketNumber: String): Boolean {
        return try {
            ticketDao.getTicketByNumber(ticketNumber) != null
        } catch (e: Exception) {
            Log.w("BookingService", "Error checking ticket number existence: ${e.message}")
            false
        }
    }
    
    /**
     * Generates a unique ID using UUID
     */
    private fun generateUniqueId(): String {
        return UUID.randomUUID().toString()
    }
    
    /**
     * Generates a booking reference
     */
    private fun generateBookingReference(): String {
        val timestamp = System.currentTimeMillis()
        val random = Random.nextInt(1000, 9999)
        return "BK${timestamp}${random}"
    }
    
    /**
     * Generates a transaction ID
     */
    private fun generateTransactionId(): String {
        val timestamp = System.currentTimeMillis()
        val random = Random.nextInt(1000, 9999)
        return "TXN${timestamp}${random}"
    }
    
    /**
     * Generates a QR code for the ticket
     */
    private fun generateQrCode(ticketNumber: String, bookingId: String): String {
        return "TICKET:$ticketNumber:BOOKING:$bookingId"
    }
    
    /**
     * Gets booking by ID
     */
    suspend fun getBookingById(bookingId: String): BookingEntity? = withContext(Dispatchers.IO) {
        try {
            bookingDao.getBookingById(bookingId)
        } catch (e: Exception) {
            Log.e("BookingService", "Error getting booking by ID: ${e.message}", e)
            null
        }
    }
    
    /**
     * Gets tickets by booking ID
     */
    suspend fun getTicketsByBookingId(bookingId: String): List<TicketEntity> = withContext(Dispatchers.IO) {
        try {
            ticketDao.getTicketsByBookingId(bookingId)
        } catch (e: Exception) {
            Log.e("BookingService", "Error getting tickets by booking ID: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Gets payments by booking ID
     */
    suspend fun getPaymentsByBookingId(bookingId: String): List<PaymentEntity> = withContext(Dispatchers.IO) {
        try {
            paymentDao.getPaymentsByBookingId(bookingId)
        } catch (e: Exception) {
            Log.e("BookingService", "Error getting payments by booking ID: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Gets ticket by ticket number
     */
    suspend fun getTicketByNumber(ticketNumber: String): com.gocavgo.validator.database.TicketEntity? = withContext(Dispatchers.IO) {
        try {
            ticketDao.getTicketByNumber(ticketNumber)
        } catch (e: Exception) {
            Log.e("BookingService", "Error getting ticket by number: ${e.message}", e)
            null
        }
    }
    
    /**
     * Count paid passengers for a waypoint location
     */
    suspend fun countPaidPassengersForWaypoint(tripId: Int, waypointLocationName: String): Int = withContext(Dispatchers.IO) {
        try {
            val count = bookingDao.countPaidPassengersForLocation(tripId, waypointLocationName)
            Log.d("BookingService", "Paid passengers for waypoint '$waypointLocationName' in trip $tripId: $count")
            return@withContext count
        } catch (e: Exception) {
            Log.e("BookingService", "Error counting paid passengers for waypoint: ${e.message}", e)
            return@withContext 0
        }
    }
    
    /**
     * Checks if there's an existing booking with CARD payment and matching NFC tag ID
     */
    suspend fun getExistingBookingByNfcTag(nfcTagId: String): ExistingBookingResult = withContext(Dispatchers.IO) {
        try {
            // Get all payments with CARD method
            val allPayments = paymentDao.getAllPayments()
            
            // Find payment with matching NFC tag ID in payment_data
            val matchingPayment = allPayments.find { payment ->
                payment.payment_method == PaymentMethod.CARD && 
                payment.payment_data?.contains("\"nfc_tag_id\":\"$nfcTagId\"") == true
            }
            
            if (matchingPayment != null) {
                // Get the booking for this payment
                val booking = bookingDao.getBookingById(matchingPayment.booking_id)
                if (booking != null) {
                    // Get the ticket for this booking
                    val tickets = ticketDao.getTicketsByBookingId(booking.id)
                    val ticket = tickets.firstOrNull()
                    
                    if (ticket != null) {
                        Log.d("BookingService", "Found existing booking for NFC tag: $nfcTagId")
                        Log.d("BookingService", "Booking ID: ${booking.id}")
                        Log.d("BookingService", "Ticket Number: ${ticket.ticket_number}")
                        
                        ExistingBookingResult.Found(
                            booking = booking,
                            payment = matchingPayment,
                            ticket = ticket
                        )
                    } else {
                        Log.w("BookingService", "No ticket found for existing booking: ${booking.id}")
                        ExistingBookingResult.NotFound
                    }
                } else {
                    Log.w("BookingService", "No booking found for payment: ${matchingPayment.id}")
                    ExistingBookingResult.NotFound
                }
            } else {
                Log.d("BookingService", "No existing booking found for NFC tag: $nfcTagId")
                ExistingBookingResult.NotFound
            }
        } catch (e: Exception) {
            Log.e("BookingService", "Error checking existing booking by NFC tag: ${e.message}", e)
            ExistingBookingResult.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    companion object {
        @Volatile
        private var INSTANCE: BookingService? = null
        
        fun getInstance(context: Context): BookingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BookingService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

/**
 * Result class for booking creation
 */
sealed class BookingCreationResult {
    data class Success(
        val bookingId: String,
        val paymentId: String,
        val ticketId: String,
        val ticketNumber: String,
        val qrCode: String
    ) : BookingCreationResult()
    
    data class Error(val message: String) : BookingCreationResult()
}

/**
 * Result class for existing booking check
 */
sealed class ExistingBookingResult {
    data class Found(
        val booking: BookingEntity,
        val payment: PaymentEntity,
        val ticket: TicketEntity
    ) : ExistingBookingResult()
    
    object NotFound : ExistingBookingResult()
    data class Error(val message: String) : ExistingBookingResult()
}
