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
import com.gocavgo.validator.dataclass.SavePlaceResponse

class BookingConfirmationDialog : DialogFragment() {
    
    private var nfcId: String = ""
    private var fromLocation: String = ""
    private var toLocation: String = ""
    private var price: String = ""
    private var ticketNumber: String? = null
    private var qrCode: String? = null
    private var onNewBookingRequested: (() -> Unit)? = null
    
    private var resetHandler: Handler? = null
    private var resetRunnable: Runnable? = null
    private val RESET_DELAY = 3000L // 3 seconds
    
    companion object {
        fun newInstance(
            nfcId: String,
            fromLocation: String,
            toLocation: String,
            price: String,
            ticketNumber: String? = null,
            qrCode: String? = null,
            onNewBookingRequested: () -> Unit
        ): BookingConfirmationDialog {
            val dialog = BookingConfirmationDialog()
            dialog.nfcId = nfcId
            dialog.fromLocation = fromLocation
            dialog.toLocation = toLocation
            dialog.price = price
            dialog.ticketNumber = ticketNumber
            dialog.qrCode = qrCode
            dialog.onNewBookingRequested = onNewBookingRequested
            return dialog
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.booking_confirmation)
        
        // Make dialog fullscreen
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        setupViews(dialog)
        startResetTimer()
        
        return dialog
    }
    
    private fun setupViews(dialog: Dialog) {
        val routeInfoText = dialog.findViewById<TextView>(R.id.routeInfoText)
        val priceInfoText = dialog.findViewById<TextView>(R.id.priceInfoText)
        val nfcCardInfoText = dialog.findViewById<TextView>(R.id.nfcCardInfoText)
        
        routeInfoText.text = "From $fromLocation to $toLocation"
        priceInfoText.text = price
        nfcCardInfoText.text = "NFC Card: $nfcId"
        
        // Display ticket information if available
        ticketNumber?.let { ticketNum ->
            val ticketInfoText = dialog.findViewById<TextView>(R.id.ticketInfoText)
            if (ticketInfoText != null) {
                ticketInfoText.text = "Ticket #$ticketNum"
                ticketInfoText.visibility = View.VISIBLE
            }
        }
        
        qrCode?.let { qr ->
            val qrCodeText = dialog.findViewById<TextView>(R.id.qrCodeText)
            if (qrCodeText != null) {
                qrCodeText.text = "QR: $qr"
                qrCodeText.visibility = View.VISIBLE
            }
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
