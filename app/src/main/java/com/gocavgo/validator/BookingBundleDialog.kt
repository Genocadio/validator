package com.gocavgo.validator

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.fragment.app.DialogFragment

class BookingBundleDialog : DialogFragment() {
    
    private var passengerName: String = ""
    private var pickupLocation: String = ""
    private var dropoffLocation: String = ""
    private var numTickets: Int = 1
    private var isPaid: Boolean = false
    
    private var resetHandler: Handler? = null
    private var resetRunnable: Runnable? = null
    private val RESET_DELAY = 4000L // 4 seconds
    
    companion object {
        fun newInstance(
            passengerName: String,
            pickupLocation: String,
            dropoffLocation: String,
            numTickets: Int,
            isPaid: Boolean
        ): BookingBundleDialog {
            val dialog = BookingBundleDialog()
            dialog.passengerName = passengerName
            dialog.pickupLocation = pickupLocation
            dialog.dropoffLocation = dropoffLocation
            dialog.numTickets = numTickets
            dialog.isPaid = isPaid
            return dialog
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.booking_bundle_notification)
        
        // Make dialog fullscreen
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        setupViews(dialog)
        startResetTimer()
        
        return dialog
    }
    
    private fun setupViews(dialog: Dialog) {
        // Set background color based on payment status
        val backgroundLayout = dialog.findViewById<View>(R.id.backgroundLayout)
        val backgroundColor = if (isPaid) {
            requireContext().resources.getColor(R.color.blue, null)
        } else {
            requireContext().resources.getColor(R.color.purple_500, null)
        }
        backgroundLayout.setBackgroundColor(backgroundColor)
        
        // Set title based on payment status
        val titleText = dialog.findViewById<TextView>(R.id.bookingTitleText)
        titleText.text = if (isPaid) "Booking Confirmed" else "Booking Received (Unpaid)"
        
        // Set passenger information
        val passengerText = dialog.findViewById<TextView>(R.id.passengerInfoText)
        passengerText.text = "Passenger: $passengerName"
        
        // Set route information
        val routeText = dialog.findViewById<TextView>(R.id.routeInfoText)
        routeText.text = "From $pickupLocation to $dropoffLocation"
        
        // Set tickets information
        val ticketsText = dialog.findViewById<TextView>(R.id.ticketsInfoText)
        val ticketsLabel = if (numTickets == 1) "ticket" else "tickets"
        ticketsText.text = "$numTickets $ticketsLabel"
        
        // Set payment status
        val paymentStatusText = dialog.findViewById<TextView>(R.id.paymentStatusText)
        paymentStatusText.text = if (isPaid) "✅ PAID" else "⏳ UNPAID"
        
        // Set status icon
        val statusIcon = dialog.findViewById<TextView>(R.id.statusIcon)
        statusIcon.text = if (isPaid) "✅" else "⏳"
    }
    
    private fun startResetTimer() {
        resetHandler = Handler(Looper.getMainLooper())
        resetRunnable = Runnable {
            dismiss()
        }
        resetHandler?.postDelayed(resetRunnable!!, RESET_DELAY)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        resetHandler?.removeCallbacks(resetRunnable!!)
        resetHandler = null
        resetRunnable = null
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        resetHandler?.removeCallbacks(resetRunnable!!)
    }
}