package com.gocavgo.validator

import android.util.Log
import android.os.Handler
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.core.Location
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.location.LocationEngine
import com.here.sdk.location.LocationEngineStatus
import com.here.sdk.location.LocationFeature
import com.here.sdk.core.LocationListener
import com.here.sdk.navigation.LocationSimulator
import com.here.sdk.navigation.LocationSimulatorOptions
import com.here.sdk.routing.Route

class LocationManager(private val handler: Handler) {

    companion object {
        private const val TAG = "LocationManager"
    }

    private var locationEngine: LocationEngine? = null
    private var locationSimulator: LocationSimulator? = null

    fun initialize() {
        try {
            locationEngine = LocationEngine()
            Log.d(TAG, "LocationEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of LocationEngine failed: ${e.error.name}")
        }
    }

    fun getCurrentLocation(): Location? {
        return locationEngine?.lastKnownLocation
    }

    fun setupLocationSource(
        locationListener: LocationListener,
        route: Route,
        isSimulator: Boolean,
        onSimulatedFallback: () -> Unit
    ) {
        if (!isSimulator) {
            locationEngine?.let { engine ->
                Log.d(TAG, "Setting up location source for real-time navigation")

                val locationStatusListener = object : com.here.sdk.location.LocationStatusListener {
                    override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
                        Log.d(TAG, "Location engine status: ${locationEngineStatus.name}")
                    }

                    override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
                        for (feature in features) {
                            Log.w(TAG, "Location feature not available: ${feature.name}")
                        }
                    }
                }

                try {
                    engine.addLocationListener(locationListener)
                    engine.addLocationStatusListener(locationStatusListener)
                    engine.confirmHEREPrivacyNoticeInclusion()
                    engine.start(LocationAccuracy.NAVIGATION)
                    Log.d(TAG, "Added navigation location listener and started engine")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add/start location engine: ${e.message}", e)
                    onSimulatedFallback()
                }
            } ?: run {
                Log.w(TAG, "Location engine not available, falling back to simulated navigation")
                onSimulatedFallback()
            }
        } else {
            try {
                locationSimulator = LocationSimulator(route, LocationSimulatorOptions())
            } catch (e: InstantiationErrorException) {
                Log.e(TAG, "Initialization of LocationSimulator failed: ${e.error.name}")
                throw RuntimeException("Initialization of LocationSimulator failed: " + e.error.name)
            }

            locationSimulator?.let { simulator ->
                simulator.listener = locationListener
                simulator.start()
                Log.d(TAG, "Location simulation started")
            }
        }
    }

    fun stop() {
        try {
            locationEngine?.stop()
        } catch (_: Exception) { }
        locationSimulator?.stop()
        locationSimulator?.listener = null
        locationSimulator = null
    }
}



