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

import android.util.Log
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
            Log.d(LOG_TAG, "Location engine status: " + locationEngineStatus.name)
        }

        override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
            for (feature in features) {
                Log.d(LOG_TAG, "Location feature not available: " + feature.name)
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
        Log.d(LOG_TAG, "startLocating called - engine initialized: ${this::locationEngine.isInitialized}, isStarted: ${if (this::locationEngine.isInitialized) locationEngine.isStarted else "N/A"}")
        
        if (!this::locationEngine.isInitialized) {
            Log.e(LOG_TAG, "Location engine not initialized, cannot start")
            return
        }
        
        if (locationEngine.isStarted) {
            Log.d(LOG_TAG, "Location engine already running, skipping start")
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
            Log.d(LOG_TAG, "Location engine started successfully with accuracy: ${accuracy?.name}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to start location engine: ${e.message}", e)
            // Clean up listeners if start failed
            try {
                locationEngine.removeLocationListener(updateListener!!)
                locationEngine.removeLocationStatusListener(locationStatusListener)
            } catch (cleanupException: Exception) {
                Log.w(LOG_TAG, "Error during cleanup after failed start: ${cleanupException.message}")
            }
        }
    }

    // Does nothing when engine is already stopped.
    fun stopLocating() {
        Log.d(LOG_TAG, "stopLocating called - engine initialized: ${this::locationEngine.isInitialized}, isStarted: ${if (this::locationEngine.isInitialized) locationEngine.isStarted else "N/A"}")
        
        if (!this::locationEngine.isInitialized) {
            Log.d(LOG_TAG, "Location engine not initialized, nothing to stop")
            return
        }
        
        if (!locationEngine.isStarted) {
            Log.d(LOG_TAG, "Location engine not running, nothing to stop")
            return
        }

        try {
            // Remove listeners and stop location engine.
            updateListener?.let { locationEngine.removeLocationListener(it) }
            locationEngine.removeLocationStatusListener(locationStatusListener)
            locationEngine.stop()
            Log.d(LOG_TAG, "Location engine stopped successfully")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error stopping location engine: ${e.message}", e)
        } finally {
            updateListener = null
        }
    }

    companion object {
        private val LOG_TAG: String = HEREPositioningProvider::class.java.name
    }
}
