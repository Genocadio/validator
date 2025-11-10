/*
 * Copyright (C) 2025 HERE Europe B.V.
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

import android.content.Context
import android.util.Log
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.mapview.MapView
import com.here.sdk.navigation.DynamicCameraBehavior
import com.here.sdk.navigation.SpeedBasedCameraBehavior
import com.here.sdk.navigation.VisualNavigator
import com.here.sdk.prefetcher.RoutePrefetcher
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.Route
import com.here.sdk.routing.RoutingError
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine.StartException
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngineOptions
import com.here.sdk.trafficawarenavigation.DynamicRoutingListener
import com.here.time.Duration
import com.here.sdk.navigation.Navigator
import com.gocavgo.validator.network.NetworkMonitor
import android.widget.Toast
import com.gocavgo.validator.util.Logging

// Shows how to start and stop turn-by-turn navigation on a car route.
// By default, tracking mode is enabled. When navigation is stopped, tracking mode is enabled again.
// The preferred device language determines the language for voice notifications used for TTS.
// (Make sure to set language + region in device settings.)
class NavigationExample(
    private val context: Context?,
    mapView: MapView?,
    private val messageView: MessageViewUpdater,
    private val tripSectionValidator: TripSectionValidator
) {
    private var visualNavigator: VisualNavigator
    private var navigator: Navigator
    private var isRenderingStarted: Boolean = false
    private val isHeadlessMode: Boolean = mapView == null
    // A class to receive real location events.
    private val herePositioningProvider: HEREPositioningProvider = HEREPositioningProvider()
    // A class to receive simulated location events.
    private val herePositioningSimulator: HEREPositioningSimulator = HEREPositioningSimulator()
    private var dynamicRoutingEngine: DynamicRoutingEngine? = null
    private var offlineRoutingEngine: OfflineRoutingEngine? = null
    
    // Network monitoring for routing engine switching
    private var networkMonitor: NetworkMonitor? = null
    private var isNetworkConnected = true
    private var currentRoutingMode = "online" // Track current mode
    // The RoutePrefetcher downloads map data in advance into the map cache.
    // This is not mandatory, but can help to improve the guidance experience.
    private val routePrefetcher: RoutePrefetcher = RoutePrefetcher(SDKNativeEngine.getSharedInstance()!!)
    private val navigationHandler: NavigationHandler

    init {

        try {
            // Without a route set, this starts tracking mode.
            visualNavigator = VisualNavigator()
            navigator = Navigator()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of VisualNavigator failed: " + e.error.name)
        }

        // By default, the MapView renders at 60 frames per second (fps).
        // When turn-by-turn navigation is enabled via the VisualNavigator,
        // the frame rate is reduced to 30 fps. This value can be customized;
        // for example, it is set to 60 fps below.
        visualNavigator.guidanceFrameRate = 60

        // This enables a navigation view including a rendered navigation arrow.
        // Only render if mapView is available (headless mode doesn't need rendering)
        if (mapView != null) {
            visualNavigator.startRendering(mapView)
            isRenderingStarted = true
        }

        createDynamicRoutingEngine()
        initializeOfflineRoutingEngine()
        initializeNetworkMonitoring(context)

        // A class to handle various kinds of guidance events.
        navigationHandler = NavigationHandler(context, messageView, tripSectionValidator)
        // Only setup visual navigator listeners if mapView is available (non-headless mode)
        // For headless mode, listeners will be setup separately via setupHeadlessListeners
        if (mapView != null) {
            dynamicRoutingEngine?.let { navigationHandler.setupListeners(visualNavigator, it) }
        }

        messageView.updateText("Initialization completed.")
    }
    

    fun startLocationProvider() {
        // Set navigator as listener to receive locations from HERE Positioning
        // and choose a suitable accuracy for the tbt navigation use case.
        // Start immediately to begin GPS acquisition
        Log.d(TAG, "=== STARTING LOCATION PROVIDER ===")
        Log.d(TAG, "Headless mode: $isHeadlessMode")
        Log.d(TAG, "Location provider already running: ${herePositioningProvider.isLocating()}")
        
        // Use navigator for headless mode, visualNavigator for visual mode
        if (isHeadlessMode) {
            herePositioningProvider.startLocating(navigator, LocationAccuracy.NAVIGATION)
            Log.d(TAG, "Location provider started with headless navigator")
        } else {
            herePositioningProvider.startLocating(visualNavigator, LocationAccuracy.NAVIGATION)
            Log.d(TAG, "Location provider started with visual navigator")
        }
        
        Log.d(TAG, "Location provider status after start: ${herePositioningProvider.isLocating()}")
        Log.d(TAG, "================================")
    }

    private fun prefetchMapData(currentGeoCoordinates: GeoCoordinates) {
        // Prefetches map data around the provided location with a radius of 2 km into the map cache.
        // For the best experience, prefetchAroundLocationWithRadius() should be called as early as possible.
        val radiusInMeters = 2000.0
        routePrefetcher.prefetchAroundLocationWithRadius(currentGeoCoordinates, radiusInMeters)
        // Prefetches map data within a corridor along the route that is currently set to the provided Navigator instance.
        // This happens continuously in discrete intervals.
        // If no route is set, no data will be prefetched.
        routePrefetcher.prefetchAroundRouteOnIntervals(visualNavigator)
    }

    // Use this engine to periodically search for better routes during guidance, ie. when the traffic
    // situation changes.
    //
    // Note: This code initiates periodic calls to the HERE Routing backend. Depending on your contract,
    // each call may be charged separately. It is the application's responsibility to decide how
    // often this code should be executed.
    private fun createDynamicRoutingEngine() {
        val dynamicRoutingOptions = DynamicRoutingEngineOptions()
        // Both, minTimeDifference and minTimeDifferencePercentage, will be checked:
        // When the poll interval is reached, the smaller difference will win.
        dynamicRoutingOptions.minTimeDifference = Duration.ofSeconds(1)
        dynamicRoutingOptions.minTimeDifferencePercentage = 0.1
        // Below, we use 10 minutes. A common range is between 5 and 15 minutes.
        dynamicRoutingOptions.pollInterval = Duration.ofMinutes(10)

        try {
            // With the dynamic routing engine you can poll the HERE backend services to search for routes with less traffic.
            // This can happen during guidance - or you can periodically update a route that is shown in a route planner.
            //
            // Make sure to call dynamicRoutingEngine.updateCurrentLocation(...) to trigger execution. If this is not called,
            // no events will be delivered even if the next poll interval has been reached.
            dynamicRoutingEngine = DynamicRoutingEngine(dynamicRoutingOptions)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of DynamicRoutingEngine failed: " + e.error.name)
        }
    }

    private fun initializeOfflineRoutingEngine() {
        try {
            offlineRoutingEngine = OfflineRoutingEngine()
            Log.d(TAG, "OfflineRoutingEngine initialized successfully")
        } catch (e: InstantiationErrorException) {
            Log.e(TAG, "Initialization of OfflineRoutingEngine failed: ${e.error.name}")
            // Don't throw exception, offline routing is optional
        }
    }

    private fun initializeNetworkMonitoring(context: Context?) {
        if (context == null) {
            Log.w(TAG, "Context is null, skipping network monitoring initialization")
            return
        }

        try {
            networkMonitor = NetworkMonitor(context) { connected, type, metered ->
                handleNetworkChange(connected, type, metered)
            }
            networkMonitor?.startMonitoring()
            Log.d(TAG, "Network monitoring initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize network monitoring: ${e.message}", e)
        }
    }

    private fun handleNetworkChange(connected: Boolean, type: String, metered: Boolean) {
        Log.d(TAG, "=== NETWORK STATE CHANGED ===")
        Log.d(TAG, "Connected: $connected, Type: $type, Metered: $metered")
        Log.d(TAG, "Previous mode: $currentRoutingMode")

        val shouldUseOnline = determineRoutingMode(connected, type, metered)
        val newMode = if (shouldUseOnline) "online" else "offline"

        // Only switch if mode actually changed
        if (newMode != currentRoutingMode) {
            Log.d(TAG, "Switching routing mode: $currentRoutingMode -> $newMode")
            switchRoutingMode(shouldUseOnline)
            currentRoutingMode = newMode
        } else {
            Log.d(TAG, "Routing mode unchanged: $currentRoutingMode")
        }

        Log.d(TAG, "==============================")
    }

    private fun determineRoutingMode(connected: Boolean, type: String, metered: Boolean): Boolean {
        return when {
            !connected -> false
            type == "WIFI" && !metered -> true
            type.startsWith("CELLULAR") && metered -> false
            type.startsWith("CELLULAR") && !metered -> true
            else -> connected
        }
    }

    private fun switchRoutingMode(useOnline: Boolean) {
        Log.d(TAG, "=== SWITCHING ROUTING MODE ===")
        Log.d(TAG, "New mode: ${if (useOnline) "Online" else "Offline"}")

        if (useOnline) {
            // Switching to online - resume DynamicRoutingEngine if we have a route
            visualNavigator.route?.let { route ->
                try {
                    startDynamicSearchForBetterRoutes(route)
                    Log.d(TAG, "DynamicRoutingEngine resumed for online mode")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resume DynamicRoutingEngine: ${e.message}")
                }
            }
            showRoutingModeToast(true)
        } else {
            // Switching to offline - stop DynamicRoutingEngine polling
            try {
                dynamicRoutingEngine?.stop()
                Log.d(TAG, "DynamicRoutingEngine stopped for offline mode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop DynamicRoutingEngine: ${e.message}")
            }
            showRoutingModeToast(false)
        }

        Log.d(TAG, "==============================")
    }

    private fun showRoutingModeToast(isOnline: Boolean) {
        val message = if (isOnline) {
            "Switched to Online Routing (Live Traffic)"
        } else {
            "Switched to Offline Routing (Cached Maps)"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun startNavigation(route: Route, isSimulated: Boolean, isCameraTrackingEnabled: Boolean) {
        val startGeoCoordinates = route.geometry.vertices[0]
        prefetchMapData(startGeoCoordinates)

        // Switches to navigation mode when no route was set before, otherwise navigation mode is kept.
        visualNavigator.route = route

        // Enable auto-zoom during guidance.
        visualNavigator.cameraBehavior = DynamicCameraBehavior()

        if (isSimulated) {
            enableRoutePlayback(route)
            messageView.updateText("Starting simulated navgation.")
        } else {
            enableDevicePositioning()
            messageView.updateText("Starting navgation.")
        }

        startDynamicSearchForBetterRoutes(route)

        // Synchronize with the toggle button state.
        updateCameraTracking(isCameraTrackingEnabled)
    }

    fun startHeadlessNavigation(route: Route, isSimulated: Boolean) {
        val startGeoCoordinates = route.geometry.vertices[0]
        prefetchMapData(startGeoCoordinates)

        Logging.d(TAG, "=== STARTING HEADLESS NAVIGATION ===")
        Logging.d(TAG, "Simulated: $isSimulated")
        
        // CRITICAL FIX: For non-simulation, ensure location provider is active BEFORE setting route
        // RouteProgressListener requires Navigator to receive location updates to fire
        // If route is set before location provider is active, RouteProgressListener won't fire until GPS provides updates
        if (!isSimulated) {
            Logging.d(TAG, "Non-simulation mode - verifying location provider before setting route")
            
            // Check if location provider is already active
            val isProviderActive = herePositioningProvider.isLocating()
            val hasLocation = hasValidLocation()
            
            Logging.d(TAG, "Location provider status: active=$isProviderActive, has location=$hasLocation")
            
            if (!isProviderActive) {
                Logging.w(TAG, "⚠️ Location provider not active - starting now before setting route")
                enableDevicePositioning()
                
                // Wait briefly for location provider to initialize and provide first update
                // This ensures Navigator receives location updates when route is set
                try {
                    Thread.sleep(300) // 300ms for location provider to start
                    Logging.d(TAG, "Location provider started, waiting for initialization")
                } catch (e: InterruptedException) {
                    Logging.w(TAG, "Interrupted while waiting for location provider: ${e.message}")
                }
            } else {
                Logging.d(TAG, "✅ Location provider already active")
                if (hasLocation) {
                    val location = getLastKnownLocation()
                    Logging.d(TAG, "✅ Navigator has location: lat=${location?.coordinates?.latitude}, lng=${location?.coordinates?.longitude}")
                } else {
                    Logging.w(TAG, "⚠️ Location provider active but no location available yet")
                }
            }
        }

        // Set the route for headless navigation only
        // NOW Navigator will have location updates ready (if non-simulation)
        navigator.route = route
        Logging.d(TAG, "Route set on Navigator")
        
        if (isSimulated) {
            enableRoutePlayback(route)
            messageView.updateText("Starting simulated headless navigation.")
        } else {
            // Location provider should already be active (checked above)
            // But ensure it's still active after route is set
            val isProviderStillActive = herePositioningProvider.isLocating()
            val hasLocationAfterRoute = hasValidLocation()
            
            Logging.d(TAG, "After route set - provider active: $isProviderStillActive, has location: $hasLocationAfterRoute")
            
            if (!isProviderStillActive) {
                Logging.w(TAG, "⚠️ Location provider stopped unexpectedly, restarting...")
                enableDevicePositioning()
            } else if (!hasLocationAfterRoute) {
                Logging.w(TAG, "⚠️ Location provider active but Navigator has no location yet")
                Logging.w(TAG, "RouteProgressListener will fire once Navigator receives map-matched location")
            } else {
                Logging.d(TAG, "✅ Navigator ready: route set ✅, location provider active ✅, has location ✅")
                Logging.d(TAG, "RouteProgressListener should fire once location is map-matched to route")
            }
            
            messageView.updateText("Starting headless navigation.")
        }

        startDynamicSearchForBetterRoutes(route)
        Logging.d(TAG, "=== HEADLESS NAVIGATION STARTED ===")
    }

    private fun startDynamicSearchForBetterRoutes(route: Route) {
        try {
            // Note that the engine will be internally stopped, if it was started before.
            // Therefore, it is not necessary to stop the engine before starting it again.
            dynamicRoutingEngine!!.start(route, object : DynamicRoutingListener {
                // Notifies on traffic-optimized routes that are considered better than the current route.
                override fun onBetterRouteFound(
                    newRoute: Route,
                    etaDifferenceInSeconds: Int,
                    distanceDifferenceInMeters: Int
                ) {
                    Log.d(TAG, "DynamicRoutingEngine: Calculated a new route.")
                    Log.d(
                        TAG,
                        "DynamicRoutingEngine: etaDifferenceInSeconds: $etaDifferenceInSeconds."
                    )
                    Log.d(
                        TAG,
                        "DynamicRoutingEngine: distanceDifferenceInMeters: $distanceDifferenceInMeters."
                    )

                    val logMessage =
                        "Calculated a new route. etaDifferenceInSeconds: " + etaDifferenceInSeconds +
                                " distanceDifferenceInMeters: " + distanceDifferenceInMeters
                    messageView.updateText("DynamicRoutingEngine update: $logMessage")

                    // An implementation needs to decide when to switch to the new route based
                    // on above criteria.
                }

                override fun onRoutingError(routingError: RoutingError) {
                    Log.d(
                        TAG,
                        "Error while dynamically searching for a better route: " + routingError.name
                    )
                }
            })
        } catch (e: StartException) {
            throw RuntimeException("Start of DynamicRoutingEngine failed. Is the RouteHandle missing?")
        }
    }

    fun stopNavigation(isCameraTrackingEnabled: Boolean) {
        // Switches to tracking mode when a route was set before, otherwise tracking mode is kept.
        // Note that tracking mode means that the visual navigator will continue to run, but without
        // turn-by-turn instructions - this can be done with or without camera tracking.
        // Without a route the navigator will only notify on the current map-matched location
        // including info such as speed and current street name.
        visualNavigator.route = null
        // SpeedBasedCameraBehavior is recommended for tracking mode.
        visualNavigator.cameraBehavior = SpeedBasedCameraBehavior()
        
        // Only enable device positioning if we're not shutting down
        // This prevents starting location services during cleanup
        if (!isShuttingDown) {
            enableDevicePositioning()
            messageView.updateText("Tracking device's location.")
        } else {
            Log.d(TAG, "Skipping device positioning enable during shutdown")
            messageView.updateText("Navigation stopped.")
        }

        dynamicRoutingEngine!!.stop()
        routePrefetcher.stopPrefetchAroundRoute()
        // Synchronize with the toggle button state.
        updateCameraTracking(isCameraTrackingEnabled)
        
        // Cleanup network monitoring
        cleanupNetworkMonitoring()
    }

    fun stopHeadlessNavigation() {
        // Clear all listeners FIRST to prevent accessing disposed Navigator
        // This must be done before setting route to null, as setting route to null
        // might trigger listener callbacks
        clearNavigatorListeners()
        
        // Stop headless navigation (set route to null)
        navigator.route = null
        
        // Only enable device positioning if we're not shutting down
        if (!isShuttingDown) {
            enableDevicePositioning()
        } else {
            Log.d(TAG, "Skipping device positioning enable during shutdown")
        }
        
        messageView.updateText("Stopped headless navigation.")

        dynamicRoutingEngine!!.stop()
        routePrefetcher.stopPrefetchAroundRoute()
        
        // Cleanup network monitoring
        cleanupNetworkMonitoring()
    }
    
    /**
     * Clear all Navigator listeners to prevent accessing disposed Navigator
     */
    fun clearNavigatorListeners() {
        try {
            navigator.routeProgressListener = null
            navigator.navigableLocationListener = null
            navigator.routeDeviationListener = null
            navigator.milestoneStatusListener = null
            navigator.destinationReachedListener = null
            Log.d(TAG, "Navigator listeners cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Navigator listeners: ${e.message}", e)
        }
    }

    fun getHeadlessNavigator(): Navigator = navigator

    private fun updateCameraTracking(isCameraTrackingEnabled: Boolean) {
        if (isCameraTrackingEnabled) {
            startCameraTracking()
        } else {
            stopCameraTracking()
        }
    }

    // Provides simulated location updates based on the given route.
    private fun enableRoutePlayback(route: Route?) {
        herePositioningProvider.stopLocating()
        // Use navigator for headless mode, visualNavigator for visual mode
        if (isHeadlessMode) {
            herePositioningSimulator.startLocating(navigator, route!!)
        } else {
            herePositioningSimulator.startLocating(visualNavigator, route!!)
        }
    }

    // Provides location updates based on the device's GPS sensor.
    private fun enableDevicePositioning() {
        Log.d(TAG, "=== ENABLING DEVICE POSITIONING ===")
        Log.d(TAG, "Headless mode: $isHeadlessMode")
        Log.d(TAG, "Shutting down: $isShuttingDown")
        Log.d(TAG, "Location provider running: ${herePositioningProvider.isLocating()}")
        Log.d(TAG, "Has valid location: ${hasValidLocation()}")
        
        // Don't start location services if we're shutting down
        if (isShuttingDown) {
            Log.d(TAG, "Skipping device positioning enable during shutdown")
            return
        }
        
        // CRITICAL FIX: Check if location provider is already running
        // This prevents double starts that cause GPS instability and delays
        if (herePositioningProvider.isLocating()) {
            val lastLocation = getLastKnownLocation()
            Log.d(TAG, "✅ Location provider already running, skipping restart to prevent GPS instability")
            if (lastLocation != null) {
                Log.d(TAG, "Current location: lat=${lastLocation.coordinates.latitude}, lng=${lastLocation.coordinates.longitude}, speed=${lastLocation.speedInMetersPerSecond ?: 0.0} m/s")
            } else {
                Log.d(TAG, "Location provider running but no location available yet")
            }
            Log.d(TAG, "================================")
            return
        }
        
        // Stop simulator if running (only if we're actually starting real GPS)
        herePositioningSimulator.stopLocating()
        Log.d(TAG, "Simulator stopped (if was running)")
        
        // IMPROVE LOCATION PROVIDER CONNECTION: Verify Navigator is properly connected
        // Navigator must be set as listener to receive location updates for RouteProgressListener to fire
        if (isHeadlessMode) {
            Log.d(TAG, "Starting headless device positioning with Navigator")
            Log.d(TAG, "Connecting Navigator to location provider...")
            herePositioningProvider.startLocating(navigator, LocationAccuracy.NAVIGATION)
            
            // Verify connection: Navigator should now receive location updates
            Log.d(TAG, "Navigator connected to location provider")
            Log.d(TAG, "Navigator will receive location updates → NavigableLocationListener → RouteProgressListener")
        } else {
            Log.d(TAG, "Starting visual device positioning with VisualNavigator")
            Log.d(TAG, "Connecting VisualNavigator to location provider...")
            herePositioningProvider.startLocating(visualNavigator, LocationAccuracy.NAVIGATION)
            
            // Verify connection: VisualNavigator should now receive location updates
            Log.d(TAG, "VisualNavigator connected to location provider")
        }
        
        // Verify location provider is actually running
        val isProviderActive = herePositioningProvider.isLocating()
        Log.d(TAG, "Device positioning started - provider active: $isProviderActive")
        
        if (!isProviderActive) {
            Log.e(TAG, "❌ CRITICAL: Location provider failed to start!")
            Log.e(TAG, "RouteProgressListener will NOT fire until location provider is active")
        } else {
            Log.d(TAG, "✅ Location provider active - Navigator should receive updates")
        }
        
        Log.d(TAG, "================================")
    }

    fun startCameraTracking() {
        visualNavigator.cameraBehavior = DynamicCameraBehavior()
    }

    fun stopCameraTracking() {
        visualNavigator.cameraBehavior = null
    }

    fun getLastKnownLocation(): Location? {
        return herePositioningProvider.getLastKnownLocation()
    }
    
    fun hasValidLocation(): Boolean {
        val location = herePositioningProvider.getLastKnownLocation()
        return location != null
    }
    
    fun isLocationProviderActive(): Boolean {
        return herePositioningProvider.isLocating()
    }

    fun stopLocating() {
        herePositioningProvider.stopLocating()
    }

    fun stopRendering() {
        // It is recommended to stop rendering before leaving an activity.
        // This also removes the current location marker.
        // Only stop if rendering was started (i.e., not in headless mode)
        if (isRenderingStarted) {
            visualNavigator.stopRendering()
            isRenderingStarted = false
        }
    }
    
    /**
     * Set shutdown flag to prevent starting services during cleanup
     */
    fun setShuttingDown(shuttingDown: Boolean) {
        isShuttingDown = shuttingDown
        Log.d(TAG, "Shutdown flag set to: $shuttingDown")
    }
    
    /**
     * Gets the HERE positioning provider for cleanup operations
     */
    fun getHerePositioningProvider(): HEREPositioningProvider = herePositioningProvider
    
    /**
     * Cleanup network monitoring resources
     */
    private fun cleanupNetworkMonitoring() {
        try {
            networkMonitor?.stopMonitoring()
            networkMonitor = null
            Log.d(TAG, "Network monitoring cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up network monitoring: ${e.message}", e)
        }
    }
    
    /**
     * Get current routing mode for external access
     */
    fun getCurrentRoutingMode(): String = currentRoutingMode
    
    /**
     * Get offline routing engine for external access
     */
    fun getOfflineRoutingEngine(): OfflineRoutingEngine? = offlineRoutingEngine
    
    /**
     * Get navigation handler for external access
     */
    fun getNavigationHandler(): NavigationHandler = navigationHandler
    
    /**
     * Get dynamic routing engine for external access
     */
    fun getDynamicRoutingEngine(): DynamicRoutingEngine? = dynamicRoutingEngine

    companion object {
        private val TAG: String = NavigationExample::class.java.name
    }
    
    // Flag to track if we're shutting down to prevent starting services during cleanup
    private var isShuttingDown = false
}