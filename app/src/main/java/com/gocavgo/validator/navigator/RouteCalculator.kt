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
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.routing.CalculateRouteCallback
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.RoutingInterface
import com.here.sdk.routing.RoutingError
import com.here.sdk.routing.Waypoint

// A class that creates car Routes with the HERE SDK.
class RouteCalculator {
    private var onlineRoutingEngine: RoutingEngine? = null
    private var offlineRoutingEngine: OfflineRoutingEngine? = null
    private var isNetworkConnected = true

    init {
        try {
            onlineRoutingEngine = RoutingEngine()
            offlineRoutingEngine = OfflineRoutingEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of RoutingEngine failed: " + e.error.name)
        }
    }

    /**
     * Set network state for routing engine selection
     */
    fun setNetworkState(isConnected: Boolean) {
        val previousState = isNetworkConnected
        isNetworkConnected = isConnected
        
        if (previousState != isConnected) {
            Logging.d("RouteCalculator", "Network state changed: $previousState -> $isConnected")
        }
    }

    fun calculateRoute(
        startWaypoint: Waypoint,
        destinationWaypoint: Waypoint,
        calculateRouteCallback: CalculateRouteCallback?
    ) {
        val waypoints: List<Waypoint> = listOf(startWaypoint, destinationWaypoint)

        // A route handle is required for the DynamicRoutingEngine to get updates on traffic-optimized routes.
        val routingOptions = CarOptions()
        routingOptions.routeOptions.enableRouteHandle = true

        calculateRouteWithEngine(waypoints, routingOptions, calculateRouteCallback)
    }

    fun calculateRouteWithWaypoints(
        waypoints: List<Waypoint>,
        calculateRouteCallback: CalculateRouteCallback?
    ) {
        Logging.d("RouteCalculator", "=== calculateRouteWithWaypoints called ===")
        Logging.d("RouteCalculator", "Waypoints count: ${waypoints.size}")
        
        if (waypoints.size < 2) {
            Logging.e("RouteCalculator", "Need at least 2 waypoints, got ${waypoints.size}")
            return
        }

        waypoints.forEachIndexed { index, waypoint ->
            Logging.d("RouteCalculator", "Waypoint $index: ${waypoint.coordinates.latitude}, ${waypoint.coordinates.longitude}")
        }

        // A route handle is required for the DynamicRoutingEngine to get updates on traffic-optimized routes.
        val routingOptions = CarOptions()
        routingOptions.routeOptions.enableRouteHandle = true
        // Keep original waypoint order from trip data
        routingOptions.routeOptions.optimizeWaypointsOrder = false

        Logging.d("RouteCalculator", "Starting route calculation with ${waypoints.size} waypoints...")
        calculateRouteWithEngine(waypoints, routingOptions, calculateRouteCallback)
    }

    /**
     * Calculate route using appropriate engine with automatic fallback
     */
    private fun calculateRouteWithEngine(
        waypoints: List<Waypoint>,
        routingOptions: CarOptions,
        calculateRouteCallback: CalculateRouteCallback?
    ) {
        val primaryEngine = getSelectedRoutingEngine()
        val engineName = if (isNetworkConnected) "online" else "offline"
        
        Logging.d("RouteCalculator", "Using $engineName routing engine for calculation")

        primaryEngine.calculateRoute(waypoints, routingOptions) { routingError, routes ->
            if (routingError == null && routes != null && routes.isNotEmpty()) {
                // Success with primary engine
                Logging.d("RouteCalculator", "Route calculated successfully using $engineName engine")
                calculateRouteCallback?.onRouteCalculated(null, routes)
            } else if (isNetworkConnected && routingError != null) {
                // Online routing failed, try offline fallback
                Logging.w("RouteCalculator", "Online routing failed: ${routingError?.name}, trying offline fallback")
                offlineRoutingEngine?.calculateRoute(waypoints, routingOptions) { offlineError, offlineRoutes ->
                    if (offlineError == null && offlineRoutes != null && offlineRoutes.isNotEmpty()) {
                        Logging.d("RouteCalculator", "Route calculated successfully using offline engine (fallback)")
                        calculateRouteCallback?.onRouteCalculated(null, offlineRoutes)
                    } else {
                        Logging.e("RouteCalculator", "Both online and offline routing failed")
                        calculateRouteCallback?.onRouteCalculated(offlineError ?: routingError, null)
                    }
                }
            } else {
                // Offline routing failed or no fallback needed
                Logging.e("RouteCalculator", "Route calculation failed: ${routingError?.name}")
                calculateRouteCallback?.onRouteCalculated(routingError, null)
            }
        }
    }

    /**
     * Get the appropriate routing engine based on network state
     */
    private fun getSelectedRoutingEngine(): RoutingInterface {
        return if (isNetworkConnected) {
            onlineRoutingEngine!!
        } else {
            offlineRoutingEngine!!
        }
    }
}