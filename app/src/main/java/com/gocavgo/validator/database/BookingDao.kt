package com.gocavgo.validator.database

import androidx.room.*
import com.gocavgo.validator.dataclass.BookingStatus

@Dao
interface BookingDao {
    
    @Query("SELECT * FROM bookings")
    suspend fun getAllBookings(): List<BookingEntity>
    
    @Query("SELECT * FROM bookings WHERE id = :bookingId")
    suspend fun getBookingById(bookingId: String): BookingEntity?
    
    @Query("SELECT * FROM bookings WHERE trip_id = :tripId")
    suspend fun getBookingsByTripId(tripId: Int): List<BookingEntity>
    
    @Query("SELECT * FROM bookings WHERE user_phone = :userPhone")
    suspend fun getBookingsByUserPhone(userPhone: String): List<BookingEntity>
    
    @Query("SELECT * FROM bookings WHERE status = :status")
    suspend fun getBookingsByStatus(status: BookingStatus): List<BookingEntity>
    
    @Query("SELECT * FROM bookings WHERE booking_reference = :bookingReference")
    suspend fun getBookingByReference(bookingReference: String): BookingEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooking(booking: BookingEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookings(bookings: List<BookingEntity>)
    
    @Update
    suspend fun updateBooking(booking: BookingEntity)
    
    @Delete
    suspend fun deleteBooking(booking: BookingEntity)
    
    @Query("DELETE FROM bookings WHERE id = :bookingId")
    suspend fun deleteBookingById(bookingId: String)

    @Query("UPDATE bookings SET status = :status, updated_at = :updatedAt WHERE id = :bookingId")
    suspend fun updateBookingStatus(bookingId: String, status: BookingStatus, updatedAt: Long)
    
    /**
     * Count paid passengers for a specific trip and dropoff location
     * Counts bookings that have COMPLETED payment and valid tickets
     */
    @Query("""
        SELECT COUNT(b.id) FROM bookings b 
        INNER JOIN payments p ON b.id = p.booking_id 
        INNER JOIN tickets t ON b.id = t.booking_id 
        WHERE b.trip_id = :tripId 
        AND b.dropoff_location_id = :dropoffLocationName 
        AND p.status = 'COMPLETED'
        AND b.status != 'CANCELLED'
    """)
    suspend fun countPaidPassengersForLocation(tripId: Int, dropoffLocationName: String): Int

    @Query("""
        SELECT SUM(number_of_tickets) FROM bookings 
        WHERE trip_id = :tripId 
        AND pickup_location_id = :locationId 
        AND status = 'CONFIRMED'
    """)
    suspend fun countTicketsPickingUpAtLocation(tripId: Int, locationId: String): Int?

    @Query("""
        SELECT SUM(number_of_tickets) FROM bookings 
        WHERE trip_id = :tripId 
        AND dropoff_location_id = :locationId 
        AND status = 'CONFIRMED'
    """)
    suspend fun countTicketsDroppingOffAtLocation(tripId: Int, locationId: String): Int?

    @Query("""
        SELECT * FROM bookings 
        WHERE trip_id = :tripId 
        AND pickup_location_id = :locationId 
        AND status = 'CONFIRMED'
        ORDER BY user_name ASC
    """)
    suspend fun getBookingsPickingUpAtLocation(tripId: Int, locationId: String): List<BookingEntity>

    @Query("""
        SELECT * FROM bookings 
        WHERE trip_id = :tripId 
        AND dropoff_location_id = :locationId 
        AND status = 'CONFIRMED'
        ORDER BY user_name ASC
    """)
    suspend fun getBookingsDroppingOffAtLocation(tripId: Int, locationId: String): List<BookingEntity>
}
