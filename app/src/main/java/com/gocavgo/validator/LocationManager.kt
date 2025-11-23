package com.gocavgo.validator

import com.gocavgo.validator.util.Logging
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
            Logging.d(TAG, "LocationEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            Logging.e(TAG, "Initialization of LocationEngine failed: ${e.error.name}")
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
                Logging.d(TAG, "Setting up location source for real-time navigation")

                val locationStatusListener = object : com.here.sdk.location.LocationStatusListener {
                    override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
                        Logging.d(TAG, "Location engine status: ${locationEngineStatus.name}")
                    }

                    override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
                        for (feature in features) {
                            Logging.w(TAG, "Location feature not available: ${feature.name}")
                        }
                    }
                }

                try {
                    engine.addLocationListener(locationListener)
                    engine.addLocationStatusListener(locationStatusListener)
                    engine.confirmHEREPrivacyNoticeInclusion()
                    engine.start(LocationAccuracy.NAVIGATION)
                    Logging.d(TAG, "Added navigation location listener and started engine")
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to add/start location engine: ${e.message}", e)
                    onSimulatedFallback()
                }
            } ?: run {
                Logging.w(TAG, "Location engine not available, falling back to simulated navigation")
                onSimulatedFallback()
            }
        } else {
            try {
                locationSimulator = LocationSimulator(route, LocationSimulatorOptions())
            } catch (e: InstantiationErrorException) {
                Logging.e(TAG, "Initialization of LocationSimulator failed: ${e.error.name}")
                throw RuntimeException("Initialization of LocationSimulator failed: " + e.error.name)
            }

            locationSimulator?.let { simulator ->
                simulator.listener = locationListener
                simulator.start()
                Logging.d(TAG, "Location simulation started")
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



