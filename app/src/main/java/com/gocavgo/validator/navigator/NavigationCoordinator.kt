package com.gocavgo.validator.navigator

import android.content.Context
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.VehicleSettings
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates navigation lifecycle operations.
 * Manages HERE SDK initialization, navigation start/stop, and App instance.
 */
class NavigationCoordinator(
    private val context: Context,
    private val tripStateManager: TripStateManager,
    private val databaseManager: DatabaseManager,
    private val settingsManager: VehicleSettingsManager,
    private val vehicleSecurityManager: com.gocavgo.validator.security.VehicleSecurityManager,
    private val lifecycleScope: CoroutineScope
) {
    companion object {
        private const val TAG = "NavigationCoordinator"
        
        // Synchronization lock for SDK initialization
        private val sdkInitLock = Any()
    }

    private var app: App? = null
    private var messageViewUpdater: MessageViewUpdater? = null
    private var isRouteCalculated = false
    private var isDestroyed = false
    private var pendingOfflineMode: Boolean? = null

    // Callbacks for UI updates
    var onMessageUpdate: ((String) -> Unit)? = null
    var onNotificationUpdate: ((String) -> Unit)? = null
    var onRouteCalculated: (() -> Unit)? = null
    var onNavigationComplete: (() -> Unit)? = null
    var onTripStatusUpdate: ((TripResponse) -> Unit)? = null

    /**
     * Initialize HERE SDK
     */
    fun initializeSDK(lowMem: Boolean = false) {
        if (isDestroyed) {
            Logging.w(TAG, "Coordinator is destroyed, cannot initialize HERE SDK")
            return
        }

        synchronized(sdkInitLock) {
            try {
                if (isDestroyed) {
                    Logging.w(TAG, "Coordinator was destroyed during SDK initialization check")
                    return
                }

                if (SDKNativeEngine.getSharedInstance() != null) {
                    Logging.d(TAG, "HERE SDK already initialized, skipping")
                    return
                }

                Logging.d(TAG, "Initializing HERE SDK...")
                val accessKeyID = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_ID
                val accessKeySecret = com.gocavgo.validator.BuildConfig.HERE_ACCESS_KEY_SECRET
                val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
                val options = SDKOptions(authenticationMode)
                if (lowMem) {
                    options.lowMemoryMode = true
                    Logging.d(TAG, "Initialised in Low memory mode")
                }

                SDKNativeEngine.makeSharedInstance(context, options)
                Logging.d(TAG, "HERE SDK initialized successfully")

                // Apply pending offline mode if any
                pendingOfflineMode?.let { offlineMode ->
                    try {
                        SDKNativeEngine.getSharedInstance()?.setOfflineMode(offlineMode)
                        Logging.d(TAG, "Applied pending HERE SDK offline mode: $offlineMode")
                        pendingOfflineMode = null
                    } catch (e: Exception) {
                        Logging.e(TAG, "Failed to apply pending offline mode: ${e.message}", e)
                    }
                }
            } catch (e: InstantiationErrorException) {
                Logging.e(TAG, "Initialization of HERE SDK failed: ${e.error.name}", e)
                throw RuntimeException("Initialization of HERE SDK failed: " + e.error.name)
            } catch (e: Exception) {
                Logging.e(TAG, "Unexpected error during HERE SDK initialization: ${e.message}", e)
                throw RuntimeException("Unexpected error during HERE SDK initialization: ${e.message}")
            }
        }
    }

    /**
     * Initialize navigation components (App instance)
     */
    fun initializeComponents(
        onTripDeleted: ((Int) -> Unit)? = null,
        onBookingNfcManagerReady: ((TripSectionValidator) -> Unit)? = null
    ) {
        try {
            Logging.d(TAG, "Initializing navigation components...")

            // Ensure HERE SDK is initialized
            if (SDKNativeEngine.getSharedInstance() == null) {
                Logging.e(TAG, "HERE SDK not initialized, initializing now...")
                initializeSDK()
            }

            // Create MessageViewUpdater
            messageViewUpdater = MessageViewUpdater()

            // Create App with null mapView for headless mode
            val currentTrip = tripStateManager.getState().tripResponse
            app = App(context.applicationContext, null, messageViewUpdater!!, currentTrip)
            Logging.d(TAG, "App instance created (headless mode): $app")

            // Set up route calculation callback
            app?.setOnRouteCalculatedCallback {
                isRouteCalculated = true
                onRouteCalculated?.invoke()
            }

            // Set up trip deletion callback
            app?.getTripSectionValidator()?.let { validator ->
                validator.setTripDeletedCallback { deletedTripId ->
                    Logging.w(TAG, "Trip $deletedTripId deleted during navigation")
                    onTripDeleted?.invoke(deletedTripId)
                }
                
                // Notify that TripSectionValidator is ready
                onBookingNfcManagerReady?.invoke(validator)
            }

            // Update App with trip data if available
            updateTripDataWhenReady()

            Logging.d(TAG, "Navigation components initialized successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize navigation components: ${e.message}", e)
            onMessageUpdate?.invoke("Error initializing navigation: ${e.message}")
        }
    }

    /**
     * Start navigation for a trip
     */
    fun startNavigation(
        trip: TripResponse,
        allowResume: Boolean = false,
        onBookingFetch: (() -> Unit)? = null,
        onPassengerCountUpdate: (() -> Unit)? = null
    ) {
        val currentState = tripStateManager.getState()
        if (currentState.isNavigating && !allowResume) {
            Logging.w(TAG, "Navigation already active, ignoring start request")
            return
        }

        Logging.d(TAG, "=== STARTING NAVIGATION ===")
        Logging.d(TAG, "Trip ID: ${trip.id}, Allow resume: $allowResume")

        // Update trip state
        tripStateManager.setNavigating(true)
        tripStateManager.updateTrip(trip)

        // Fetch bookings
        onBookingFetch?.invoke()

        // Update trip status to IN_PROGRESS
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentStatus = trip.status
                if (!currentStatus.equals("IN_PROGRESS", ignoreCase = true)) {
                    databaseManager.updateTripStatus(trip.id, "IN_PROGRESS")
                    Logging.d(TAG, "Trip ${trip.id} status updated to IN_PROGRESS")

                    val updatedTrip = trip.copy(status = "IN_PROGRESS")
                    tripStateManager.updateTrip(updatedTrip)
                    onTripStatusUpdate?.invoke(updatedTrip)
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to update trip status: ${e.message}", e)
            }
        }

        // Update UI
        val origin = trip.route.origin.custom_name ?: trip.route.origin.google_place_name
        onMessageUpdate?.invoke("Starting navigation: $origin")
        onNotificationUpdate?.invoke("Starting navigation...")

        // Update passenger counts
        onPassengerCountUpdate?.invoke()

        // Read settings and start navigation
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val dbSettings = settingsManager.getSettings(vehicleId.toInt())
                val simulate = dbSettings?.simulate ?: false

                withContext(Dispatchers.Main) {
                    startNavigationInternal(trip, simulate)
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to read settings: ${e.message}", e)
                // Start navigation anyway
                withContext(Dispatchers.Main) {
                    startNavigationInternal(trip, false)
                }
            }
        }
    }

    /**
     * Internal method to start navigation with App
     */
    private fun startNavigationInternal(trip: TripResponse, simulate: Boolean) {
        isRouteCalculated = false

        if (app != null) {
            Logging.d(TAG, "Starting navigation via App.updateTripData()")
            app?.updateTripData(trip, simulate)
        } else {
            Logging.e(TAG, "App not initialized, cannot start navigation")
        }
    }

    /**
     * Stop navigation and cleanup
     */
    fun stopNavigation() {
        try {
            isRouteCalculated = false

            // Stop navigation using App
            app?.getNavigationExample()?.stopHeadlessNavigation()

            tripStateManager.setNavigating(false)

            Logging.d(TAG, "Navigation stopped and cleaned up")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during navigation cleanup: ${e.message}", e)
        }
    }

    /**
     * Handle navigation completion
     */
    fun handleNavigationComplete() {
        Logging.d(TAG, "=== NAVIGATION COMPLETE ===")

        isRouteCalculated = false
        tripStateManager.setNavigating(false)
        tripStateManager.clearState()

        onNavigationComplete?.invoke()
        onMessageUpdate?.invoke("Auto Mode: Waiting for trip...")
        onNotificationUpdate?.invoke("Auto Mode: Waiting for trip...")

        Logging.d(TAG, "Returned to listening state")
    }

    /**
     * Update trip data when App is ready
     */
    private fun updateTripDataWhenReady() {
        val trip = tripStateManager.getState().tripResponse
        if (trip != null && app != null) {
            Logging.d(TAG, "Updating App with trip data after both are ready")
            // Get simulate from settings
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val vehicleId = vehicleSecurityManager.getVehicleId()
                    val dbSettings = settingsManager.getSettings(vehicleId.toInt())
                    val simulate = dbSettings?.simulate ?: false
                    withContext(Dispatchers.Main) {
                        app?.updateTripData(trip, simulate)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error reading settings for trip data update: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Get App instance
     */
    fun getApp(): App? = app

    /**
     * Get MessageViewUpdater
     */
    fun getMessageViewUpdater(): MessageViewUpdater? = messageViewUpdater

    /**
     * Check if route is calculated
     */
    fun isRouteCalculated(): Boolean = isRouteCalculated

    /**
     * Set route calculated status
     */
    fun setRouteCalculated(calculated: Boolean) {
        isRouteCalculated = calculated
    }

    /**
     * Detach and cleanup
     */
    fun detach() {
        isDestroyed = true
        app?.detach()
        app = null
        messageViewUpdater = null
        Logging.d(TAG, "NavigationCoordinator detached")
    }

    /**
     * Dispose HERE SDK
     */
    fun disposeSDK() {
        try {
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                Logging.d(TAG, "Disposing HERE SDK...")
                sdkNativeEngine.dispose()
                SDKNativeEngine.setSharedInstance(null)
                Logging.d(TAG, "HERE SDK disposed successfully")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error disposing HERE SDK: ${e.message}", e)
        }
    }

    /**
     * Set pending offline mode (will be applied when SDK is initialized)
     */
    fun setPendingOfflineMode(offlineMode: Boolean) {
        pendingOfflineMode = offlineMode
    }
}
