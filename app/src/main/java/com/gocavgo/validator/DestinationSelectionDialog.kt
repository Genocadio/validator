package com.gocavgo.validator

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.gocavgo.validator.dataclass.SavePlaceResponse

class DestinationSelectionDialog : DialogFragment() {
    
    private var nfcId: String = ""
    private var currentLocation: String = ""
    private var availableDestinations: List<Navigator.AvailableDestination> = emptyList()
    private var onDestinationSelected: ((Navigator.AvailableDestination) -> Unit)? = null
    
    companion object {
        fun newInstance(
            nfcId: String,
            currentLocation: String,
            destinations: List<Navigator.AvailableDestination>,
            onSelected: (Navigator.AvailableDestination) -> Unit
        ): DestinationSelectionDialog {
            val dialog = DestinationSelectionDialog()
            dialog.nfcId = nfcId
            dialog.currentLocation = currentLocation
            dialog.availableDestinations = destinations
            dialog.onDestinationSelected = onSelected
            return dialog
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_destination_selection, null)
        
        setupViews(view)
        populateDestinations(view)
        
        builder.setView(view)
        return builder.create()
    }
    
    private fun setupViews(view: View) {
        val nfcInfoText = view.findViewById<TextView>(R.id.nfcInfoText)
        val currentLocationText = view.findViewById<TextView>(R.id.currentLocationText)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        
        nfcInfoText.text = "NFC Card: $nfcId"
        currentLocationText.text = "📍 $currentLocation"
        
        cancelButton.setOnClickListener {
            dismiss()
        }
    }
    
    private fun populateDestinations(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.destinationsContainer)
        container.removeAllViews()
        
        availableDestinations.forEach { destination ->
            val destinationView = createDestinationView(destination)
            container.addView(destinationView)
        }
    }
    
    private fun createDestinationView(destination: Navigator.AvailableDestination): View {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.item_destination_selection, null)
        
        val iconText = view.findViewById<TextView>(R.id.destinationIcon)
        val nameText = view.findViewById<TextView>(R.id.destinationName)
        val priceText = view.findViewById<TextView>(R.id.destinationPrice)
        val indicator = view.findViewById<TextView>(R.id.selectionIndicator)
        
        // Set destination icon
        iconText.text = if (destination.isFinalDestination) "🏁" else "🚩"
        
        // Set destination name
        val displayName = getLocationDisplayName(destination.location)
        nameText.text = displayName
        
        // Set price
        priceText.text = "${destination.price.toInt()} RWF"
        
        // Set click listener
        view.setOnClickListener {
            // Show selection indicator
            indicator.visibility = View.VISIBLE
            view.isSelected = true
            
            // Call the callback after a short delay to show the selection
            view.postDelayed({
                onDestinationSelected?.invoke(destination)
                dismiss()
            }, 200)
        }
        
        return view
    }
    
    private fun getLocationDisplayName(location: SavePlaceResponse): String {
        return location.custom_name?.takeIf { it.isNotBlank() } ?: location.google_place_name
    }
}
