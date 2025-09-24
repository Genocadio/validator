package com.gocavgo.validator.database

import androidx.room.*
import com.gocavgo.validator.dataclass.PaymentStatus

@Dao
interface PaymentDao {
    
    @Query("SELECT * FROM payments")
    suspend fun getAllPayments(): List<PaymentEntity>
    
    @Query("SELECT * FROM payments WHERE id = :paymentId")
    suspend fun getPaymentById(paymentId: String): PaymentEntity?
    
    @Query("SELECT * FROM payments WHERE booking_id = :bookingId")
    suspend fun getPaymentsByBookingId(bookingId: String): List<PaymentEntity>
    
    @Query("SELECT * FROM payments WHERE status = :status")
    suspend fun getPaymentsByStatus(status: PaymentStatus): List<PaymentEntity>
    
    @Query("SELECT * FROM payments WHERE transaction_id = :transactionId")
    suspend fun getPaymentByTransactionId(transactionId: String): PaymentEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: PaymentEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayments(payments: List<PaymentEntity>)
    
    @Update
    suspend fun updatePayment(payment: PaymentEntity)
    
    @Delete
    suspend fun deletePayment(payment: PaymentEntity)
    
    @Query("DELETE FROM payments WHERE id = :paymentId")
    suspend fun deletePaymentById(paymentId: String)
    
    @Query("UPDATE payments SET status = :status, updated_at = :updatedAt WHERE id = :paymentId")
    suspend fun updatePaymentStatus(paymentId: String, status: PaymentStatus, updatedAt: Long)
}


