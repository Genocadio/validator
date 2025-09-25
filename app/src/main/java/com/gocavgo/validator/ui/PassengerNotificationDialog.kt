package com.gocavgo.validator.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import com.gocavgo.validator.R

class PassengerNotificationDialog(private val context: Context) {
    
    companion object {
        private const val TAG = "PassengerNotificationDialog"
        private const val NOTIFICATION_DURATION_MS = 3000L // 3 seconds
    }
    
    private var currentDialog: Dialog? = null
    
    /**
     * Show notification when approaching a waypoint
     */
    fun showApproachingNotification(passengerCount: Int, waypointName: String) {
        showNotification(
            title = "APPROACHING WAYPOINT",
            passengerCount = passengerCount,
            waypointName = waypointName,
            backgroundColor = "#FF9800", // Orange
            titleColor = "#FFFFFF"
        )
    }
    
    /**
     * Show notification when waypoint is reached/passed
     */
    fun showWaypointReachedNotification(passengerCount: Int, waypointName: String) {
        showNotification(
            title = "WAYPOINT REACHED",
            passengerCount = passengerCount,
            waypointName = waypointName,
            backgroundColor = "#4CAF50", // Green
            titleColor = "#FFFFFF"
        )
    }
    
    private fun showNotification(
        title: String,
        passengerCount: Int,
        waypointName: String,
        backgroundColor: String,
        titleColor: String
    ) {
        try {
            // Dismiss any existing notification
            dismissCurrentNotification()
            
            if (passengerCount <= 0) {
                Log.d(TAG, "No passengers to board at $waypointName, skipping notification")
                return
            }
            
            Log.d(TAG, "Showing passenger notification: $title - $passengerCount passengers at $waypointName")
            
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            
            // Inflate custom layout
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_passenger_notification_clean, null)
            
            // Configure views
            val titleText = view.findViewById<TextView>(R.id.notificationTitle)
            val countText = view.findViewById<TextView>(R.id.passengerCount)
            val messageText = view.findViewById<TextView>(R.id.notificationMessage)
            val waypointText = view.findViewById<TextView>(R.id.waypointName)
            
            titleText.text = title
            titleText.setTextColor(Color.parseColor(titleColor))
            
            countText.text = passengerCount.toString()
            
            messageText.text = if (passengerCount == 1) "PASSENGER TO BOARD" else "PASSENGERS TO BOARD"
            messageText.setTextColor(Color.parseColor(titleColor))
            
            waypointText.text = waypointName
            waypointText.setTextColor(Color.parseColor(titleColor))
            
            // Set background with rounded corners
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(Color.parseColor(backgroundColor))
            drawable.cornerRadius = 32f // 16dp in pixels (approximately)
            view.background = drawable
            
            dialog.setContentView(view)
            
            // Configure dialog window
            val window = dialog.window
            window?.let {
                val params = it.attributes
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.TOP
                params.y = 100 // Offset from top
                it.attributes = params
                
                // Keep screen on and show when locked
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                it.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
            
            currentDialog = dialog
            dialog.show()
            
            // Auto-dismiss after duration
            Handler(Looper.getMainLooper()).postDelayed({
                dismissCurrentNotification()
            }, NOTIFICATION_DURATION_MS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing passenger notification: ${e.message}", e)
        }
    }
    
    /**
     * Dismiss current notification if showing
     */
    fun dismissCurrentNotification() {
        try {
            currentDialog?.let { dialog ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                currentDialog = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification: ${e.message}", e)
        }
    }
}