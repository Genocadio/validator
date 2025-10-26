package com.gocavgo.validator.database

import androidx.room.*

@Dao
interface TicketDao {
    
    @Query("SELECT * FROM tickets")
    suspend fun getAllTickets(): List<TicketEntity>
    
    @Query("SELECT * FROM tickets WHERE id = :ticketId")
    suspend fun getTicketById(ticketId: String): TicketEntity?
    
    @Query("SELECT * FROM tickets WHERE booking_id = :bookingId")
    suspend fun getTicketsByBookingId(bookingId: String): List<TicketEntity>
    
    @Query("SELECT * FROM tickets WHERE ticket_number = :ticketNumber")
    suspend fun getTicketByNumber(ticketNumber: String): TicketEntity?
    
    @Query("SELECT * FROM tickets WHERE qr_code = :qrCode")
    suspend fun getTicketByQrCode(qrCode: String): TicketEntity?
    
    @Query("SELECT * FROM tickets WHERE is_used = :isUsed")
    suspend fun getTicketsByUsageStatus(isUsed: Boolean): List<TicketEntity>
    
    @Query("SELECT * FROM tickets WHERE validated_by = :validatedBy")
    suspend fun getTicketsByValidator(validatedBy: String): List<TicketEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTicket(ticket: TicketEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTickets(tickets: List<TicketEntity>)
    
    @Update
    suspend fun updateTicket(ticket: TicketEntity)
    
    @Delete
    suspend fun deleteTicket(ticket: TicketEntity)
    
    @Query("DELETE FROM tickets WHERE id = :ticketId")
    suspend fun deleteTicketById(ticketId: String)
    
    @Query("UPDATE tickets SET is_used = :isUsed, used_at = :usedAt, validated_by = :validatedBy, updated_at = :updatedAt WHERE id = :ticketId")
    suspend fun markTicketAsUsed(ticketId: String, isUsed: Boolean, usedAt: Long?, validatedBy: String?, updatedAt: Long)
}


