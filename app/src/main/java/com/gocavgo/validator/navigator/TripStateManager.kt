package com.gocavgo.validator.navigator

import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages trip state using StateFlow as a single source of truth.
 * Eliminates state duplication between Activity and Service.
 */
data class TripState(
    val currentTrip: TripResponse? = null,
    val isNavigating: Boolean = false,
    val countdownText: String = "",
    val tripResponse: TripResponse? = null
)

class TripStateManager {
    companion object {
        private const val TAG = "TripStateManager"
        
        @Volatile
        private var INSTANCE: TripStateManager? = null
        
        fun getInstance(): TripStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TripStateManager().also { INSTANCE = it }
            }
        }
        
        fun resetInstance() {
            INSTANCE = null
        }
    }

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    /**
     * Get current state snapshot
     */
    fun getState(): TripState = _state.value

    /**
     * Update the current trip
     */
    fun updateTrip(trip: TripResponse?) {
        _state.value = _state.value.copy(
            currentTrip = trip,
            tripResponse = trip
        )
        Logging.d(TAG, "Trip updated: ${trip?.id}")
    }

    /**
     * Update trip response separately (for cases where it differs from currentTrip)
     */
    fun updateTripResponse(trip: TripResponse?) {
        _state.value = _state.value.copy(tripResponse = trip)
        Logging.d(TAG, "Trip response updated: ${trip?.id}")
    }

    /**
     * Set navigating state
     */
    fun setNavigating(navigating: Boolean) {
        _state.value = _state.value.copy(isNavigating = navigating)
        Logging.d(TAG, "Navigating state: $navigating")
    }

    /**
     * Update countdown text
     */
    fun updateCountdown(text: String) {
        _state.value = _state.value.copy(countdownText = text)
        Logging.d(TAG, "Countdown updated: $text")
    }

    /**
     * Handle trip cancellation
     */
    fun handleTripCancellation(tripId: Int) {
        val currentState = _state.value
        if (currentState.currentTrip?.id == tripId) {
            Logging.d(TAG, "Handling trip cancellation: $tripId")
            clearState()
        } else {
            Logging.d(TAG, "Cancelled trip $tripId is not current trip, ignoring")
        }
    }

    /**
     * Clear all trip state
     */
    fun clearState() {
        _state.value = TripState()
        Logging.d(TAG, "Trip state cleared")
    }

    /**
     * Update trip status (creates new copy with updated status)
     */
    fun updateTripStatus(status: String) {
        val currentState = _state.value
        val updatedTrip = currentState.currentTrip?.copy(status = status)
        val updatedTripResponse = currentState.tripResponse?.copy(status = status)
        
        _state.value = currentState.copy(
            currentTrip = updatedTrip,
            tripResponse = updatedTripResponse
        )
        Logging.d(TAG, "Trip status updated to: $status")
    }

    /**
     * Check if a specific trip is the current trip
     */
    fun isCurrentTrip(tripId: Int): Boolean {
        return _state.value.currentTrip?.id == tripId
    }

    /**
     * Get current trip ID
     */
    fun getCurrentTripId(): Int? {
        return _state.value.currentTrip?.id
    }
}
