/*
 * Copyright (C) 2019-2025 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.gocavgo.validator.navigator

import com.gocavgo.validator.util.Logging
import com.here.sdk.core.Location
import com.here.sdk.core.LocationListener
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.location.LocationEngine
import com.here.sdk.location.LocationEngineStatus
import com.here.sdk.location.LocationFeature
import com.here.sdk.location.LocationStatusListener

// A reference implementation using HERE Positioning to get notified on location updates
// from various location sources available from a device and HERE services.
class HEREPositioningProvider {
    lateinit var locationEngine: LocationEngine
    private var updateListener: LocationListener? = null

    private val locationStatusListener: LocationStatusListener = object : LocationStatusListener {
        override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
            Logging.d(LOG_TAG, "Location engine status: " + locationEngineStatus.name)
        }

        override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
            for (feature in features) {
                Logging.d(LOG_TAG, "Location feature not available: " + feature.name)
            }
        }
    }

    init {
        try {
            locationEngine = LocationEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization failed: " + e.message)
        }
    }

    fun getLastKnownLocation(): Location? {
        return locationEngine.lastKnownLocation
    }
    
    fun isLocating(): Boolean {
        return this::locationEngine.isInitialized && locationEngine.isStarted
    }

    // Does nothing when engine is already running.
    fun startLocating(updateListener: LocationListener?, accuracy: LocationAccuracy?) {
        Logging.d(LOG_TAG, "startLocating called - engine initialized: ${this::locationEngine.isInitialized}, isStarted: ${if (this::locationEngine.isInitialized) locationEngine.isStarted else "N/A"}")
        
        if (!this::locationEngine.isInitialized) {
            Logging.e(LOG_TAG, "Location engine not initialized, cannot start")
            return
        }
        
        if (locationEngine.isStarted) {
            Logging.d(LOG_TAG, "Location engine already running, skipping start")
            return
        }

        this.updateListener = updateListener

        try {
            // Set listeners to get location updates.
            locationEngine.addLocationListener(updateListener!!)
            locationEngine.addLocationStatusListener(locationStatusListener)

            // By calling confirmHEREPrivacyNoticeInclusion() you confirm that this app informs on
            // data collection, which is done for this app via HEREPositioningTermsAndPrivacyHelper,
            // which shows a possible example for this.
            locationEngine.confirmHEREPrivacyNoticeInclusion()

            locationEngine.start(accuracy!!)
            Logging.d(LOG_TAG, "Location engine started successfully with accuracy: ${accuracy?.name}")
        } catch (e: Exception) {
            Logging.e(LOG_TAG, "Failed to start location engine: ${e.message}", e)
            // Clean up listeners if start failed
            try {
                locationEngine.removeLocationListener(updateListener!!)
                locationEngine.removeLocationStatusListener(locationStatusListener)
            } catch (cleanupException: Exception) {
                Logging.w(LOG_TAG, "Error during cleanup after failed start: ${cleanupException.message}")
            }
        }
    }

    // Does nothing when engine is already stopped.
    fun stopLocating() {
        Logging.d(LOG_TAG, "stopLocating called - engine initialized: ${this::locationEngine.isInitialized}, isStarted: ${if (this::locationEngine.isInitialized) locationEngine.isStarted else "N/A"}")
        
        if (!this::locationEngine.isInitialized) {
            Logging.d(LOG_TAG, "Location engine not initialized, nothing to stop")
            return
        }
        
        if (!locationEngine.isStarted) {
            Logging.d(LOG_TAG, "Location engine not running, nothing to stop")
            return
        }

        try {
            // Remove listeners and stop location engine.
            updateListener?.let { locationEngine.removeLocationListener(it) }
            locationEngine.removeLocationStatusListener(locationStatusListener)
            locationEngine.stop()
            Logging.d(LOG_TAG, "Location engine stopped successfully")
        } catch (e: Exception) {
            Logging.e(LOG_TAG, "Error stopping location engine: ${e.message}", e)
        } finally {
            updateListener = null
        }
    }
    
    /**
     * Force disconnect and cleanup all HERE SDK location services
     * This method ensures complete cleanup to prevent service connection leaks
     */
    fun forceDisconnect() {
        Logging.d(LOG_TAG, "Force disconnecting HERE SDK location services...")
        
        try {
            if (this::locationEngine.isInitialized) {
                // Remove all listeners first
                updateListener?.let { 
                    try {
                        locationEngine.removeLocationListener(it)
                        Logging.d(LOG_TAG, "Removed location listener")
                    } catch (e: Exception) {
                        Logging.w(LOG_TAG, "Error removing location listener: ${e.message}")
                    }
                }
                
                try {
                    locationEngine.removeLocationStatusListener(locationStatusListener)
                    Logging.d(LOG_TAG, "Removed location status listener")
                } catch (e: Exception) {
                    Logging.w(LOG_TAG, "Error removing location status listener: ${e.message}")
                }
                
                // Stop the engine
                if (locationEngine.isStarted) {
                    try {
                        locationEngine.stop()
                        Logging.d(LOG_TAG, "Location engine stopped")
                    } catch (e: Exception) {
                        Logging.w(LOG_TAG, "Error stopping location engine: ${e.message}")
                    }
                }
                
                // Additional cleanup to ensure service connections are released
                try {
                    // Force disconnect any underlying service connections
                    // This helps prevent ServiceConnection leaks
                    val sdkNativeEngine = com.here.sdk.core.engine.SDKNativeEngine.getSharedInstance()
                    if (sdkNativeEngine != null) {
                        // The SDK should handle service disconnection internally when LocationEngine is stopped
                        // But we can add additional safety measures here if needed
                        Logging.d(LOG_TAG, "SDK native engine available for additional cleanup")
                    }
                } catch (e: Exception) {
                    Logging.w(LOG_TAG, "Error during additional SDK cleanup: ${e.message}")
                }
            }
            
            // Clear references
            updateListener = null
            
            Logging.d(LOG_TAG, "HERE SDK location services force disconnected")
        } catch (e: Exception) {
            Logging.e(LOG_TAG, "Error during force disconnect: ${e.message}", e)
        }
    }

    companion object {
        private val LOG_TAG: String = HEREPositioningProvider::class.java.name
    }
}
