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

import android.content.Context
import android.util.Log
import com.here.sdk.core.LanguageCode
import com.here.sdk.core.UnitSystem
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.navigation.EventText
import com.here.sdk.navigation.EventTextListener
import com.here.sdk.navigation.ManeuverNotificationOptions
import com.here.sdk.navigation.MapMatchedLocation
import com.here.sdk.navigation.NavigableLocation
import com.here.sdk.navigation.NavigableLocationListener
import com.here.sdk.navigation.Navigator
import com.here.sdk.navigation.RouteDeviation
import com.here.sdk.navigation.RouteDeviationListener
import com.here.sdk.navigation.RouteProgress
import com.here.sdk.navigation.RouteProgressListener
import com.here.sdk.navigation.TextNotificationType
import com.here.sdk.navigation.VisualNavigator
import com.here.sdk.navigation.Milestone
import com.here.sdk.navigation.MilestoneStatus
import com.here.sdk.navigation.MilestoneStatusListener
import com.here.sdk.navigation.DestinationReachedListener
import com.here.sdk.routing.CalculateTrafficOnRouteCallback
import com.here.sdk.routing.Maneuver
import com.here.sdk.routing.ManeuverAction
import com.here.sdk.routing.RoadType
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.RoutingError
import com.here.sdk.routing.TrafficOnRoute
import com.here.sdk.routing.Waypoint
import com.here.sdk.routing.OfflineRoutingEngine
import com.here.sdk.routing.RoutingInterface
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine
import kotlinx.coroutines.launch
import java.util.Locale
import android.widget.Toast

// This class combines the various events that can be emitted during turn-by-turn navigation.
// Note that this class does not show an exhaustive list of all possible events.
class NavigationHandler(
    private val context: Context?,
    private val messageView: MessageViewUpdater,
    private val tripSectionValidator: TripSectionValidator
) {
    private var previousManeuverIndex = -1
    private var lastMapMatchedLocation: MapMatchedLocation? = null
    private var currentSpeedInMetersPerSecond: Double = 0.0

    private val timeUtils = TimeUtils()
    private val routingEngine: RoutingEngine
    private val offlineRoutingEngine: OfflineRoutingEngine
    private var isNetworkConnected = true
    private var lastTrafficUpdateInMilliseconds = 0L
    private lateinit var voiceAssistant: VoiceAssistant
    
    // Route deviation handling
    private var isReturningToRoute = false
    private var deviationCounter = 0
    private val DEVIATION_THRESHOLD_METERS = 50
    private val MIN_DEVIATION_EVENTS = 3

    init {
        try {
            routingEngine = RoutingEngine()
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
            Log.d(TAG, "Network state changed: $previousState -> $isConnected")
            showRoutingModeToast(isConnected)
        }
    }

    fun setupListeners(
        visualNavigator: VisualNavigator,
        dynamicRoutingEngine: DynamicRoutingEngine
    ) {
        // A helper class for TTS.
        voiceAssistant = VoiceAssistant(context, object : VoiceAssistant.VoiceAssistantListener {
            override fun onInitialized() {
                setupVoiceGuidance(visualNavigator)
            }
        })

        // Notifies on the progress along the route including maneuver instructions.
        visualNavigator.routeProgressListener =
            RouteProgressListener { routeProgress: RouteProgress ->
                // Get current location from lastMapMatchedLocation if available
                // This ensures we have location data before processing section progress
                lastMapMatchedLocation?.let { mapMatched ->
                    val lat = mapMatched.coordinates.latitude
                    val lng = mapMatched.coordinates.longitude
                    val bearing = mapMatched.bearingInDegrees
                    
                    // Update location data in validator BEFORE processing section progress
                    tripSectionValidator.updateLocationData(
                        lat, lng, 
                        currentSpeedInMetersPerSecond, 
                        0.0, // accuracy not available here, will be updated by navigableLocationListener
                        bearing
                    )
                }
                
                // CRITICAL: Process section progress FIRST, before checking maneuver progress
                // Section progress is always available and needed for TripSectionValidator tracking
                // This ensures progress tracking works even when maneuver progress isn't available yet (e.g., real GPS before movement)
                val sectionProgressList = routeProgress.sectionProgress
                if (sectionProgressList.isNotEmpty()) {
                    val totalSections = sectionProgressList.size
                    tripSectionValidator.processSectionProgress(sectionProgressList, totalSections)
                    Log.d(TAG, "Processed section progress: ${sectionProgressList.size} sections")
                }
                
                // Contains the progress for the next maneuver ahead and the next-next maneuvers, if any.
                val nextManeuverList = routeProgress.maneuverProgress

                val nextManeuverProgress = nextManeuverList[0]
                if (nextManeuverProgress == null) {
                    Log.d(TAG, "No next maneuver available (but section progress was processed).")
                    return@RouteProgressListener
                }

                val nextManeuverIndex = nextManeuverProgress.maneuverIndex
                val nextManeuver = visualNavigator.getManeuver(nextManeuverIndex)
                    ?: // Should never happen as we retrieved the next maneuver progress above.
                    return@RouteProgressListener

                val action = nextManeuver.action
                val roadName = getRoadName(nextManeuver)
                val logMessage = action.name + " on " + roadName + " in " + nextManeuverProgress.remainingDistanceInMeters + " meters."

                // Angle is null for some maneuvers like Depart, Arrive and Roundabout.
                val turnAngle = nextManeuver.turnAngleInDegrees
                if (turnAngle != null) {
                    if (turnAngle > 10) {
                        Log.d(TAG, "At the next maneuver: Make a right turn of $turnAngle degrees.")
                    } else if (turnAngle < -10) {
                        Log.d(TAG, "At the next maneuver: Make a left turn of $turnAngle degrees.")
                    } else {
                        Log.d(TAG, "At the next maneuver: Go straight.")
                    }
                }

                // Angle is null when the roundabout maneuver is not an enter, exit or keep maneuver.
                val roundaboutAngle = nextManeuver.roundaboutAngleInDegrees
                if (roundaboutAngle != null) {
                    // Note that the value is negative only for left-driving countries such as UK.
                    Log.d(TAG, "At the next maneuver: Follow the roundabout for " + roundaboutAngle + " degrees to reach the exit."
                    )
                }

                var currentETAString = getETA(routeProgress)

                currentETAString = if (previousManeuverIndex != nextManeuverIndex) {
                    "$currentETAString\nNew maneuver: $logMessage"
                } else {
                    // A maneuver update contains a different distance to reach the next maneuver.
                    "$currentETAString\nManeuver update: $logMessage"
                }
                messageView.updateText(currentETAString)

                previousManeuverIndex = nextManeuverIndex

                if (lastMapMatchedLocation != null) {
                    // Update the route based on the current location of the driver.
                    // We periodically want to search for better traffic-optimized routes.
                    dynamicRoutingEngine.updateCurrentLocation(
                        lastMapMatchedLocation!!,
                        routeProgress.sectionIndex
                    )
                }
                updateTrafficOnRoute(routeProgress, visualNavigator)
            }

        // Notifies on the current map-matched location and other useful information while driving or walking.
        visualNavigator.navigableLocationListener =
            NavigableLocationListener { currentNavigableLocation: NavigableLocation ->
                Log.d(TAG, "Received navigable location update")
                lastMapMatchedLocation = currentNavigableLocation.mapMatchedLocation
                if (lastMapMatchedLocation == null) {
                    Log.d(TAG, "The currentNavigableLocation could not be map-matched. Are you off-road?")
                    return@NavigableLocationListener
                }
                Log.d(TAG, "Location successfully map-matched")

                if (lastMapMatchedLocation!!.isDrivingInTheWrongWay) {
                    // For two-way streets, this value is always false. This feature is supported in tracking mode and when deviating from a route.
                    Log.d(
                        TAG,
                        "This is a one way road. User is driving against the allowed traffic direction."
                    )
                }

                val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond
                val accuracy =
                    currentNavigableLocation.originalLocation.speedAccuracyInMetersPerSecond
                currentSpeedInMetersPerSecond = speed ?: 0.0
                Log.d(
                    TAG,
                    "Driving speed (m/s): " + speed + "plus/minus an accuracy of: " + accuracy
                )
                
                // Extract location data from map-matched location
                val lat = lastMapMatchedLocation!!.coordinates.latitude
                val lng = lastMapMatchedLocation!!.coordinates.longitude
                val bearing = lastMapMatchedLocation!!.bearingInDegrees
                
                // Store location data for use by TripSectionValidator
                // The actual database update will be called from TripSectionValidator.writeProgressToDatabase()
                // to ensure it happens at the same frequency as waypoint progress updates
                tripSectionValidator.updateLocationData(lat, lng, speed ?: 0.0, accuracy ?: 0.0, bearing)
            }

        // Notifies on route deviation events
        visualNavigator.routeDeviationListener = RouteDeviationListener { routeDeviation ->
            try {
                handleRouteDeviation(routeDeviation, visualNavigator)
            } catch (e: Exception) {
                Log.e(TAG, "Error in route deviation listener: ${e.message}", e)
            }
        }

        // Notifies on messages that can be fed into TTS engines to guide the user with audible instructions.
        // The texts can be maneuver instructions or warn on certain obstacles, such as speed cameras.
        visualNavigator.eventTextListener =
            EventTextListener { eventText: EventText ->
                // We use the built-in TTS engine to synthesize the localized text as audio.
                voiceAssistant.speak(eventText.text)
                // We can optionally retrieve the associated maneuver. The details will be null if the text contains
                // non-maneuver related information, such as for speed camera warnings.
                if (eventText.type == TextNotificationType.MANEUVER && eventText.maneuverNotificationDetails != null) {
                    eventText.maneuverNotificationDetails!!.maneuver
                }
            }

        // Notifies when a waypoint on the route is reached or missed.
        visualNavigator.milestoneStatusListener =
            MilestoneStatusListener { milestone: Milestone, milestoneStatus: MilestoneStatus ->
                if (milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.REACHED) {
                    // milestoneIndex 0 = first trip waypoint (order=1), milestoneIndex 1 = second trip waypoint (order=2)
                    val waypointOrder = milestone.waypointIndex!!
                    val waypoiCord = milestone.originalCoordinates!!
                    Log.d(TAG, "Waypoint reached via milestone: milestoneIndex=${milestone.waypointIndex}, waypointOrder=$waypointOrder")
                    tripSectionValidator.markWaypointAsPassedByMilestone(waypointOrder, waypoiCord)
                } else if (milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.MISSED) {
                    Log.w(TAG, "Waypoint missed: index=${milestone.waypointIndex}")
                } else if (milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.REACHED) {
                    Log.d(TAG, "System waypoint reached at: ${milestone.mapMatchedCoordinates}")
                } else if (milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.MISSED) {
                    Log.w(TAG, "System waypoint missed at: ${milestone.mapMatchedCoordinates}")
                }
            }

        // Notifies when the destination of the route is reached.
        visualNavigator.destinationReachedListener = DestinationReachedListener {
            Log.d(TAG, "Destination reached!")
            tripSectionValidator.handleDestinationReached()
        }
    }

    fun setupHeadlessListeners(
        navigator: Navigator,
        dynamicRoutingEngine: DynamicRoutingEngine
    ) {
        // Notifies on the progress along the route including maneuver instructions.
        navigator.routeProgressListener =
            RouteProgressListener { routeProgress: RouteProgress ->
                // Get current location from lastMapMatchedLocation if available
                // This ensures we have location data before processing section progress
                lastMapMatchedLocation?.let { mapMatched ->
                    val lat = mapMatched.coordinates.latitude
                    val lng = mapMatched.coordinates.longitude
                    val bearing = mapMatched.bearingInDegrees
                    
                    // Update location data in validator BEFORE processing section progress
                    tripSectionValidator.updateLocationData(
                        lat, lng, 
                        currentSpeedInMetersPerSecond, 
                        0.0, 
                        bearing
                    )
                }
                
                // GPS STATUS LOGGING: Track RouteProgress updates for headless navigation
                Log.d(TAG, "=== HEADLESS ROUTE PROGRESS UPDATE ===")
                Log.d(TAG, "Section index: ${routeProgress.sectionIndex}")
                Log.d(TAG, "Current speed: ${currentSpeedInMetersPerSecond} m/s (${currentSpeedInMetersPerSecond * 3.6} km/h)")
                
                // CRITICAL: Process section progress FIRST, before checking maneuver progress
                // Section progress is always available and needed for TripSectionValidator tracking
                // This ensures progress tracking works even when maneuver progress isn't available yet (e.g., real GPS before movement)
                val sectionProgressList = routeProgress.sectionProgress
                if (sectionProgressList.isNotEmpty()) {
                    val totalSections = sectionProgressList.size
                    val lastSection = sectionProgressList.lastOrNull()
                    val remainingDistance = lastSection?.remainingDistanceInMeters ?: 0
                    val remainingDuration = lastSection?.remainingDuration?.toSeconds() ?: 0
                    Log.d(TAG, "Section progress: ${sectionProgressList.size} sections, remaining: ${remainingDistance}m, ETA: ${remainingDuration}s")
                    tripSectionValidator.processSectionProgress(sectionProgressList, totalSections)
                } else {
                    Log.w(TAG, "⚠️ No section progress available in headless navigation")
                }
                Log.d(TAG, "======================================")
                
                // Contains the progress for the next maneuver ahead and the next-next maneuvers, if any.
                val nextManeuverList = routeProgress.maneuverProgress

                val nextManeuverProgress = nextManeuverList[0]
                if (nextManeuverProgress == null) {
                    Log.d(TAG, "No next maneuver available (but section progress was processed).")
                    return@RouteProgressListener
                }

                val nextManeuverIndex = nextManeuverProgress.maneuverIndex
                val nextManeuver = navigator.getManeuver(nextManeuverIndex)
                    ?: // Should never happen as we retrieved the next maneuver progress above.
                    return@RouteProgressListener

                val action = nextManeuver.action
                val roadName = getRoadName(nextManeuver)
                val logMessage = action.name + " on " + roadName + " in " + nextManeuverProgress.remainingDistanceInMeters + " meters."

                // Angle is null for some maneuvers like Depart, Arrive and Roundabout.
                val turnAngle = nextManeuver.turnAngleInDegrees
                if (turnAngle != null) {
                    if (turnAngle > 10) {
                        Log.d(TAG, "At the next maneuver: Make a right turn of $turnAngle degrees.")
                    } else if (turnAngle < -10) {
                        Log.d(TAG, "At the next maneuver: Make a left turn of $turnAngle degrees.")
                    } else {
                        Log.d(TAG, "At the next maneuver: Go straight.")
                    }
                }

                // Angle is null when the roundabout maneuver is not an enter, exit or keep maneuver.
                val roundaboutAngle = nextManeuver.roundaboutAngleInDegrees
                if (roundaboutAngle != null) {
                    // Note that the value is negative only for left-driving countries such as UK.
                    Log.d(TAG, "At the next maneuver: Follow the roundabout for " + roundaboutAngle + " degrees to reach the exit."
                    )
                }

                var currentETAString = getETA(routeProgress)

                currentETAString = if (previousManeuverIndex != nextManeuverIndex) {
                    "$currentETAString\nNew maneuver: $logMessage"
                } else {
                    // A maneuver update contains a different distance to reach the next maneuver.
                    "$currentETAString\nManeuver update: $logMessage"
                }
                messageView.updateText(currentETAString)

                previousManeuverIndex = nextManeuverIndex

                if (lastMapMatchedLocation != null) {
                    // Update the route based on the current location of the driver.
                    // We periodically want to search for better traffic-optimized routes.
                    dynamicRoutingEngine.updateCurrentLocation(
                        lastMapMatchedLocation!!,
                        routeProgress.sectionIndex
                    )
                }
                updateTrafficOnRoute(routeProgress, navigator)
            }

        // Notifies on the current map-matched location and other useful information while driving or walking.
        navigator.navigableLocationListener =
            NavigableLocationListener { currentNavigableLocation: NavigableLocation ->
                // GPS STATUS LOGGING: Track location updates and speed
                val originalLocation = currentNavigableLocation.originalLocation
                val speed = originalLocation.speedInMetersPerSecond ?: 0.0
                val accuracy = originalLocation.speedAccuracyInMetersPerSecond ?: 0.0
                val mapMatched = currentNavigableLocation.mapMatchedLocation
                
                Log.d(TAG, "=== GPS LOCATION UPDATE ===")
                Log.d(TAG, "Speed: ${speed} m/s (${speed * 3.6} km/h), Accuracy: ±${accuracy} m/s")
                if (mapMatched != null) {
                    Log.d(TAG, "Map-matched: lat=${mapMatched.coordinates.latitude}, lng=${mapMatched.coordinates.longitude}")
                    Log.d(TAG, "Bearing: ${mapMatched.bearingInDegrees}°, Wrong way: ${mapMatched.isDrivingInTheWrongWay}")
                } else {
                    Log.w(TAG, "⚠️ Location not map-matched - may be off-road or GPS issue")
                }
                Log.d(TAG, "==========================")
                
                // Update stored location and speed
                lastMapMatchedLocation = currentNavigableLocation.mapMatchedLocation
                currentSpeedInMetersPerSecond = speed
                
                if (lastMapMatchedLocation == null) {
                    Log.w(TAG, "⚠️ Location could not be map-matched - may be off-road or GPS issue")
                    return@NavigableLocationListener
                }

                if (lastMapMatchedLocation!!.isDrivingInTheWrongWay) {
                    // For two-way streets, this value is always false. This feature is supported in tracking mode and when deviating from a route.
                    Log.w(
                        TAG,
                        "⚠️ Driving against traffic direction on one-way road"
                    )
                }
            }

        // Notifies on route deviation events for headless navigation
        navigator.routeDeviationListener = RouteDeviationListener { routeDeviation ->
            try {
                handleRouteDeviationHeadless(routeDeviation, navigator)
            } catch (e: Exception) {
                Log.e(TAG, "Error in route deviation listener: ${e.message}", e)
            }
        }

        // Notifies when a waypoint on the route is reached or missed (headless).
        navigator.milestoneStatusListener =
            MilestoneStatusListener { milestone: Milestone, milestoneStatus: MilestoneStatus ->
                if (milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.REACHED) {
                    // milestoneIndex 0 = first trip waypoint (order=1), milestoneIndex 1 = second trip waypoint (order=2)
                    val waypointOrder = milestone.waypointIndex!!
                    val waypoiCord = milestone.originalCoordinates!!
                    Log.d(TAG, "Headless waypoint reached via milestone: milestoneIndex=${milestone.waypointIndex}, waypointOrder=$waypointOrder")
                    tripSectionValidator.markWaypointAsPassedByMilestone(waypointOrder, waypoiCord)
                } else if (milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.MISSED) {
                    Log.w(TAG, "Headless waypoint missed: index=${milestone.waypointIndex}")
                } else if (milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.REACHED) {
                    Log.d(TAG, "Headless system waypoint reached at: ${milestone.mapMatchedCoordinates}")
                } else if (milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.MISSED) {
                    Log.w(TAG, "Headless system waypoint missed at: ${milestone.mapMatchedCoordinates}")
                }
            }

        // Notifies when the destination of the route is reached (headless).
        navigator.destinationReachedListener = DestinationReachedListener {
            Log.d(TAG, "Headless destination reached!")
            tripSectionValidator.handleDestinationReached()
        }
    }

    private fun getETA(routeProgress: RouteProgress): String {
        val sectionProgressList = routeProgress.sectionProgress
        // sectionProgressList is guaranteed to be non-empty.
        val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
        val totalSections = sectionProgressList.size

        // NOTE: Section progress is already processed earlier in the routeProgressListener
        // This method only extracts ETA string and waypoint data from the processed progress

        // Update waypoint with first section's remaining time and distance
        if (sectionProgressList.isNotEmpty()) {
            val firstSectionProgress = sectionProgressList[0]
            val remainingTimeToNextWaypoint = firstSectionProgress.remainingDuration.toSeconds()
            val remainingDistanceToNextWaypoint = firstSectionProgress.remainingDistanceInMeters.toDouble()

            
            // Update the corresponding waypoint with first section data
            updateWaypointWithFirstSectionData(remainingTimeToNextWaypoint, remainingDistanceToNextWaypoint)
            
            // Get calculated waypoint data from TripSectionValidator
            val calculatedWaypointData = tripSectionValidator.getCurrentWaypointProgress()

        }

        for (i in sectionProgressList.indices) {
            val sectionProgress = sectionProgressList[i]

            val sectionNumber = i + 1
            // Check if we're close to reaching this waypoint
            if (sectionProgress.remainingDistanceInMeters < 10) {
                for (j in sectionProgressList.indices) {
                    val section = sectionProgressList[j]
                    Log.d(TAG, "Section $j/$totalSections:")
                    Log.d(TAG, "  Remaining distance: ${section.remainingDistanceInMeters} meters")
                    Log.d(TAG, "  Remaining duration: ${section.remainingDuration.toSeconds()} seconds")
                    Log.d(TAG, "  Traffic delay: ${section.trafficDelay.seconds} seconds")
                }

                Log.d(TAG, "  *** Reached waypoint for section $sectionNumber! ***")
            }
        }

        val speedInKmh = currentSpeedInMetersPerSecond * 3.6
        val currentSpeedString = "Speed: ${String.format("%.1f", speedInKmh)} km/h"

        Log.d(
            TAG,
            "Distance to destination in meters: " + lastSectionProgress.remainingDistanceInMeters
        )
        Log.d(TAG, "Traffic delay ahead in seconds: " + lastSectionProgress.trafficDelay.seconds)
        // Logs current speed.
        Log.d(TAG, currentSpeedString)
        return currentSpeedString
    }

    private fun setupVoiceGuidance(visualNavigator: VisualNavigator) {
        val ttsLanguageCode =
            getLanguageCodeForDevice(VisualNavigator.getAvailableLanguagesForManeuverNotifications())
        val maneuverNotificationOptions = ManeuverNotificationOptions()
        // Set the language in which the notifications will be generated.
        maneuverNotificationOptions.language = ttsLanguageCode
        // Set the measurement system used for distances.
        maneuverNotificationOptions.unitSystem = UnitSystem.METRIC
        visualNavigator.maneuverNotificationOptions = maneuverNotificationOptions
        Log.d(
            TAG,
            "LanguageCode for maneuver notifications: $ttsLanguageCode"
        )

        // Set language to our TextToSpeech engine.
        val locale = LanguageCodeConverter.getLocale(ttsLanguageCode)
        if (voiceAssistant.setLanguage(locale)) {
            Log.d(
                TAG,
                "TextToSpeech engine uses this language: $locale"
            )
        } else {
            Log.e(
                TAG,
                "TextToSpeech engine does not support this language: $locale"
            )
        }
    }

    // Get the language preferably used on this device.
    private fun getLanguageCodeForDevice(supportedVoiceSkins: List<LanguageCode>): LanguageCode {
        // 1. Determine if preferred device language is supported by our TextToSpeech engine.

        var localeForCurrenDevice = Locale.getDefault()
        if (!voiceAssistant.isLanguageAvailable(localeForCurrenDevice)) {
            Log.e(
                TAG,
                "TextToSpeech engine does not support: $localeForCurrenDevice, falling back to EN_US."
            )
            localeForCurrenDevice = Locale("en", "US")
        }

        // 2. Determine supported voice skins from HERE SDK.
        var languageCodeForCurrenDevice =
            LanguageCodeConverter.getLanguageCode(localeForCurrenDevice)
        if (!supportedVoiceSkins.contains(languageCodeForCurrenDevice)) {
            Log.e(
                TAG,
                "No voice skins available for $languageCodeForCurrenDevice, falling back to EN_US."
            )
            languageCodeForCurrenDevice = LanguageCode.EN_US
        }

        return languageCodeForCurrenDevice
    }

    private fun getRoadName(maneuver: Maneuver): String {
        val currentRoadTexts = maneuver.roadTexts
        val nextRoadTexts = maneuver.nextRoadTexts

        val currentRoadName = currentRoadTexts.names.defaultValue
        val currentRoadNumber = currentRoadTexts.numbersWithDirection.defaultValue
        val nextRoadName = nextRoadTexts.names.defaultValue
        val nextRoadNumber = nextRoadTexts.numbersWithDirection.defaultValue

        var roadName = nextRoadName ?: nextRoadNumber

        // On highways, we want to show the highway number instead of a possible road name,
        // while for inner city and urban areas road names are preferred over road numbers.
        if (maneuver.nextRoadType == RoadType.HIGHWAY) {
            roadName = nextRoadNumber ?: nextRoadName
        }

        if (maneuver.action == ManeuverAction.ARRIVE) {
            // We are approaching the destination, so there's no next road.
            roadName = currentRoadName ?: currentRoadNumber
        }

        if (roadName == null) {
            // Happens only in rare cases, when also the fallback is null.
            roadName = "unnamed road"
        }

        return roadName
    }

    // Periodically updates the traffic information for the current route.
    // This method checks whether the last traffic update occurred within the specified interval and skips the update if not.
    // Then it calculates the current traffic conditions along the route using the `RoutingEngine`.
    // Lastly, it updates the `VisualNavigator` with the newly calculated `TrafficOnRoute` object,
    // which affects the `RouteProgress` duration without altering the route geometry or distance.
    //
    // Note: This code initiates periodic calls to the HERE Routing backend. Depending on your contract,
    // each call may be charged separately. It is the application's responsibility to decide how and how
    // often this code should be executed.
    private fun updateTrafficOnRoute(
        routeProgress: RouteProgress,
        visualNavigator: VisualNavigator
    ) {
        val currentRoute = visualNavigator.route
            ?: // Should never happen.
            return

        // Below, we use 10 minutes. A common range is between 5 and 15 minutes.
        val trafficUpdateIntervalInMilliseconds = (10 * 60000).toLong() // 10 minutes.
        val now = System.currentTimeMillis()
        if ((now - lastTrafficUpdateInMilliseconds) < trafficUpdateIntervalInMilliseconds) {
            return
        }
        // Store the current time when we update trafficOnRoute.
        lastTrafficUpdateInMilliseconds = now

        val sectionProgressList = routeProgress.sectionProgress
        val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
        val traveledDistanceOnLastSectionInMeters =
            currentRoute.lengthInMeters - lastSectionProgress.remainingDistanceInMeters
        val lastTraveledSectionIndex = routeProgress.sectionIndex

        routingEngine.calculateTrafficOnRoute(
            currentRoute,
            lastTraveledSectionIndex,
            traveledDistanceOnLastSectionInMeters,
            CalculateTrafficOnRouteCallback { routingError: RoutingError?, trafficOnRoute: TrafficOnRoute? ->
                if (routingError != null) {
                    Log.d(TAG, "CalculateTrafficOnRoute error: " + routingError.name)
                    return@CalculateTrafficOnRouteCallback
                }
                // Sets traffic data for the current route, affecting RouteProgress duration in SectionProgress,
                // while preserving route distance and geometry.
                visualNavigator.trafficOnRoute = trafficOnRoute
                Log.d(TAG, "Updated traffic on route.")
            })
    }

    /**
     * Updates the corresponding waypoint with first section's remaining time and distance data
     */
    private fun updateWaypointWithFirstSectionData(
        remainingTimeToNextWaypoint: Long,
        remainingDistanceToNextWaypoint: Double
    ) {
        try {
            // Get current waypoint progress from trip section validator
            val waypointProgressList = tripSectionValidator.getCurrentWaypointProgress()
            
            if (waypointProgressList.isNotEmpty()) {
                // Find the next unpassed waypoint (the one we're currently navigating to)
                val nextWaypoint = waypointProgressList.find { it.isNext }
                
                if (nextWaypoint != null) {
                    Log.d(TAG, "Updating next waypoint: ${nextWaypoint.waypointName}")
                    Log.d(TAG, "  - Remaining time: ${remainingTimeToNextWaypoint}s")
                    Log.d(TAG, "  - Remaining distance: ${String.format("%.1f", remainingDistanceToNextWaypoint)}m")
                    
                    // Here you can add any additional waypoint update logic
                    // For example, updating UI, sending MQTT messages, etc.
                    
                } else {
                    Log.d(TAG, "No next waypoint found - all waypoints may be passed")
                }
            } else {
                Log.d(TAG, "No waypoint progress data available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating waypoint with first section data: ${e.message}", e)
        }
    }

    // Overloaded version for Navigator (headless)
    private fun updateTrafficOnRoute(
        routeProgress: RouteProgress,
        navigator: Navigator
    ) {
        val currentRoute = navigator.route
            ?: // Should never happen.
            return

        // Below, we use 10 minutes. A common range is between 5 and 15 minutes.
        val trafficUpdateIntervalInMilliseconds = (10 * 60000).toLong() // 10 minutes.
        val now = System.currentTimeMillis()
        if ((now - lastTrafficUpdateInMilliseconds) < trafficUpdateIntervalInMilliseconds) {
            return
        }
        // Store the current time when we update trafficOnRoute.
        lastTrafficUpdateInMilliseconds = now

        val sectionProgressList = routeProgress.sectionProgress
        val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
        val traveledDistanceOnLastSectionInMeters =
            currentRoute.lengthInMeters - lastSectionProgress.remainingDistanceInMeters
        val lastTraveledSectionIndex = routeProgress.sectionIndex

        routingEngine.calculateTrafficOnRoute(
            currentRoute,
            lastTraveledSectionIndex,
            traveledDistanceOnLastSectionInMeters,
            CalculateTrafficOnRouteCallback { routingError: RoutingError?, trafficOnRoute: TrafficOnRoute? ->
                if (routingError != null) {
                    Log.d(TAG, "CalculateTrafficOnRoute error: " + routingError.name)
                    return@CalculateTrafficOnRouteCallback
                }
                // Sets traffic data for the current route, affecting RouteProgress duration in SectionProgress,
                // while preserving route distance and geometry.
                navigator.trafficOnRoute = trafficOnRoute
                Log.d(TAG, "Updated traffic on route.")
            })
    }

    private fun handleRouteDeviation(routeDeviation: RouteDeviation, visualNavigator: VisualNavigator) {
        val route = visualNavigator.route ?: return
        
        // Get current coordinates
        val currentMapMatchedLocation = routeDeviation.currentLocation.mapMatchedLocation
        val currentGeoCoordinates = currentMapMatchedLocation?.coordinates
            ?: routeDeviation.currentLocation.originalLocation.coordinates
        
        // Get last coordinates on route
        val lastGeoCoordinatesOnRoute = if (routeDeviation.lastLocationOnRoute != null) {
            val lastMapMatched = routeDeviation.lastLocationOnRoute!!.mapMatchedLocation
            lastMapMatched?.coordinates ?: routeDeviation.lastLocationOnRoute!!.originalLocation.coordinates
        } else {
            route.sections[0].departurePlace.originalCoordinates
        }
        
        val distanceInMeters = currentGeoCoordinates.distanceTo(lastGeoCoordinatesOnRoute!!).toInt()
        deviationCounter++
        
        if (isReturningToRoute) {
            Log.d(TAG, "Rerouting already in progress")
            return
        }
        
        if (distanceInMeters > DEVIATION_THRESHOLD_METERS && deviationCounter >= MIN_DEVIATION_EVENTS) {
            Log.d(TAG, "Route deviation: ${distanceInMeters}m - starting reroute")
            isReturningToRoute = true
            
            val newStartingPoint = Waypoint(currentGeoCoordinates)
            currentMapMatchedLocation?.bearingInDegrees?.let { 
newStartingPoint.headingInDegrees = it 
            }
            
            val selectedEngine = getRoutingEngineForReroute()
            selectedEngine.returnToRoute(
                route,
                newStartingPoint,
                routeDeviation.lastTraveledSectionIndex,
                routeDeviation.traveledDistanceOnLastSectionInMeters
            ) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val newRoute = routes[0]
                    visualNavigator.route = newRoute
                    Log.d(TAG, "Rerouting successful using ${if (isNetworkConnected) "online" else "offline"} engine")
                } else {
                    Log.e(TAG, "Rerouting failed: ${routingError?.name}")
                }
                isReturningToRoute = false
                deviationCounter = 0
            }
        }
    }

    private fun handleRouteDeviationHeadless(routeDeviation: RouteDeviation, navigator: Navigator) {
        val route = navigator.route ?: return
        
        // Get current coordinates
        val currentMapMatchedLocation = routeDeviation.currentLocation.mapMatchedLocation
        val currentGeoCoordinates = currentMapMatchedLocation?.coordinates
            ?: routeDeviation.currentLocation.originalLocation.coordinates
        
        // Get last coordinates on route
        val lastGeoCoordinatesOnRoute = if (routeDeviation.lastLocationOnRoute != null) {
            val lastMapMatched = routeDeviation.lastLocationOnRoute!!.mapMatchedLocation
            lastMapMatched?.coordinates ?: routeDeviation.lastLocationOnRoute!!.originalLocation.coordinates
        } else {
            route.sections[0].departurePlace.originalCoordinates
        }
        
        val distanceInMeters = currentGeoCoordinates.distanceTo(lastGeoCoordinatesOnRoute!!).toInt()
        deviationCounter++
        
        if (isReturningToRoute) {
            Log.d(TAG, "Rerouting already in progress")
            return
        }
        
        if (distanceInMeters > DEVIATION_THRESHOLD_METERS && deviationCounter >= MIN_DEVIATION_EVENTS) {
            Log.d(TAG, "Route deviation: ${distanceInMeters}m - starting reroute")
            isReturningToRoute = true
            
            val newStartingPoint = Waypoint(currentGeoCoordinates)
            currentMapMatchedLocation?.bearingInDegrees?.let { 
                newStartingPoint.headingInDegrees = it 
            }
            
            val selectedEngine = getRoutingEngineForReroute()
            selectedEngine.returnToRoute(
                route,
                newStartingPoint,
                routeDeviation.lastTraveledSectionIndex,
                routeDeviation.traveledDistanceOnLastSectionInMeters
            ) { routingError, routes ->
                if (routingError == null && routes != null && routes.isNotEmpty()) {
                    val newRoute = routes[0]
                    navigator.route = newRoute
                    Log.d(TAG, "Headless rerouting successful using ${if (isNetworkConnected) "online" else "offline"} engine")
                } else {
                    Log.e(TAG, "Headless rerouting failed: ${routingError?.name}")
                }
                isReturningToRoute = false
                deviationCounter = 0
            }
        }
    }

    /**
     * Get the appropriate routing engine based on network state
     */
    private fun getRoutingEngineForReroute(): RoutingInterface {
        return if (isNetworkConnected) {
            routingEngine
        } else {
            offlineRoutingEngine
        }
    }

    /**
     * Show toast notification for routing mode changes
     */
    private fun showRoutingModeToast(isOnline: Boolean) {
        val message = if (isOnline) {
            "Switched to Online Routing (Live Traffic)"
        } else {
            "Switched to Offline Routing (Cached Maps)"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val TAG: String = NavigationHandler::class.java.name
    }
}