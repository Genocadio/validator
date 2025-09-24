package com.gocavgo.validator

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.gocavgo.validator.database.BookingEntity
import com.gocavgo.validator.database.PaymentEntity
import com.gocavgo.validator.database.TicketEntity

class TicketDisplayDialog : DialogFragment() {
    
    private var booking: BookingEntity? = null
    private var payment: PaymentEntity? = null
    private var ticket: TicketEntity? = null
    private var onNewBookingRequested: (() -> Unit)? = null
    
    private var resetHandler: Handler? = null
    private var resetRunnable: Runnable? = null
    private val RESET_DELAY = 5000L // 5 seconds for ticket display
    
    companion object {
        fun newInstance(
            booking: BookingEntity,
            payment: PaymentEntity,
            ticket: TicketEntity,
            onNewBookingRequested: () -> Unit
        ): TicketDisplayDialog {
            val dialog = TicketDisplayDialog()
            dialog.booking = booking
            dialog.payment = payment
            dialog.ticket = ticket
            dialog.onNewBookingRequested = onNewBookingRequested
            return dialog
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.ticket_display)
        
        // Make dialog fullscreen
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        setupViews(dialog)
        startResetTimer()
        
        return dialog
    }
    
    private fun setupViews(dialog: Dialog) {
        val routeInfoText = dialog.findViewById<TextView>(R.id.routeInfoText)
        val priceInfoText = dialog.findViewById<TextView>(R.id.priceInfoText)
        val ticketNumberText = dialog.findViewById<TextView>(R.id.ticketNumberText)
        val qrCodeText = dialog.findViewById<TextView>(R.id.qrCodeText)
        val nfcCardInfoText = dialog.findViewById<TextView>(R.id.nfcCardInfoText)
        val statusInfoText = dialog.findViewById<TextView>(R.id.statusInfoText)
        
        booking?.let { booking ->
            payment?.let { payment ->
                ticket?.let { ticket ->
                    // Display route information
                    routeInfoText.text = "From ${booking.pickup_location_id} to ${booking.dropoff_location_id}"
                    
                    // Display price
                    priceInfoText.text = "${booking.total_amount.toInt()} RWF"
                    
                    // Display ticket number
                    ticketNumberText.text = "Ticket #${ticket.ticket_number}"
                    
                    // Display QR code
                    qrCodeText.text = "QR: ${ticket.qr_code}"
                    
                    // Extract NFC tag ID from payment data
                    val nfcTagId = extractNfcTagId(payment.payment_data)
                    nfcCardInfoText.text = "NFC Card: $nfcTagId"
                    
                    // Display status
                    statusInfoText.text = "Status: ${booking.status.value}"
                    
                    // Set status color based on booking status
                    when (booking.status.value) {
                        "USED" -> statusInfoText.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green
                        "PENDING" -> statusInfoText.setTextColor(android.graphics.Color.parseColor("#FF9800")) // Orange
                        "CONFIRMED" -> statusInfoText.setTextColor(android.graphics.Color.parseColor("#2196F3")) // Blue
                        "CANCELED" -> statusInfoText.setTextColor(android.graphics.Color.parseColor("#F44336")) // Red
                        "EXPIRED" -> statusInfoText.setTextColor(android.graphics.Color.parseColor("#9E9E9E")) // Gray
                        else -> statusInfoText.setTextColor(android.graphics.Color.parseColor("#FFFFFF")) // White
                    }
                }
            }
        }
    }
    
    private fun extractNfcTagId(paymentData: String?): String {
        if (paymentData == null) return "Unknown"
        
        return try {
            // Extract NFC tag ID from JSON-like payment data
            val nfcTagIdPattern = "\"nfc_tag_id\":\"([^\"]+)\"".toRegex()
            val matchResult = nfcTagIdPattern.find(paymentData)
            matchResult?.groupValues?.get(1) ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun startResetTimer() {
        resetHandler = Handler(Looper.getMainLooper())
        resetRunnable = Runnable {
            dismiss()
        }
        resetHandler?.postDelayed(resetRunnable!!, RESET_DELAY)
    }
    
    fun handleNewBookingRequest() {
        // Cancel the reset timer
        resetHandler?.removeCallbacks(resetRunnable!!)
        
        // Dismiss current dialog and trigger new booking
        dismiss()
        onNewBookingRequested?.invoke()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        resetHandler?.removeCallbacks(resetRunnable!!)
    }
}


