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
}


