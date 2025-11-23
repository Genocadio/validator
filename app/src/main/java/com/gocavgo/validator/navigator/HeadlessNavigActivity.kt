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

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gocavgo.validator.util.Logging
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.validator.BuildConfig
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.ui.components.NetworkStatusIndicator
import com.gocavgo.validator.ui.components.WaypointProgressOverlay
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.network.NetworkUtils
import com.gocavgo.validator.trip.ActiveTripListener
import com.gocavgo.validator.trip.ActiveTripCallback
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.units.core.utils.EnvironmentLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class HeadlessNavigActivity: ComponentActivity() {

    companion object {
        private val TAG: String = HeadlessNavigActivity::class.java.simpleName
        
        @Volatile
        private var isNavigationActive = java.util.concurrent.atomic.AtomicBoolean(false)
        
        fun isActive(): Boolean = isNavigationActive.get()
    }

    private val environmentLogger = EnvironmentLogger()
    private var permissionsRequestor: PermissionsRequestor? = null
    private var app: App? = null
    private var messageViewUpdater: MessageViewUpdater? = null
    

    // Trip data
    private var tripResponse: TripResponse? = null
    private var isSimulated: Boolean = true

    // Database manager
    private lateinit var databaseManager: DatabaseManager
    
    // Active trip listener
    private var activeTripListener: ActiveTripListener? = null
    
    // Security and settings managers
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var settingsManager: VehicleSettingsManager

    // Network monitoring
    private var networkMonitor: NetworkMonitor? = null
    private var isConnected by mutableStateOf(true)
    private var connectionType by mutableStateOf("UNKNOWN")
    private var isMetered by mutableStateOf(true)
    
    // HERE SDK offline mode state
    private var pendingOfflineMode: Boolean? = null

    // Coroutine scope
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Waypoint overlay state
    private var showWaypointOverlay by mutableStateOf(false)
    private var waypointProgressData by mutableStateOf<List<TripSectionValidator.WaypointProgressInfo>>(emptyList())
    
    // Route calculation state
    private var isRouteCalculated by mutableStateOf(false)
    
    // PassengerListType enum for compatibility with HeadlessNavigatorCompose.kt
    enum class PassengerListType {
        PICKUP, DROPOFF
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logging.d(TAG, "HeadlessNavigActivity started - listening for active trip")
        
        // Mark navigation as active
        isNavigationActive.set(true)

        // Log application and device details.
        environmentLogger.logEnvironment("Kotlin")
        permissionsRequestor = PermissionsRequestor(this)

        enableEdgeToEdge()

        messageViewUpdater = MessageViewUpdater()

        // Initialize managers
        databaseManager = DatabaseManager.getInstance(this)
        vehicleSecurityManager = VehicleSecurityManager(this)
        settingsManager = VehicleSettingsManager.getInstance(this)

        // Initialize network monitoring
        initializeNetworkMonitoring()

        // Setup UI first to prevent ANR
        setupUI()

        // Initialize ActiveTripListener
        activeTripListener = ActiveTripListener(this)
        
        // Start listening for active trips
        activeTripListener?.start(
            scope = coroutineScope,
            callback = object : ActiveTripCallback {
                override fun onActiveTripFound(trip: TripResponse) {
                    Logging.d(TAG, "Active trip found: ${trip.id}")
                    handleActiveTrip(trip)
                }
                
                override fun onActiveTripChanged(newTrip: TripResponse, oldTrip: TripResponse?) {
                    Logging.d(TAG, "Active trip changed: ${oldTrip?.id} → ${newTrip.id}")
                    handleActiveTrip(newTrip)
                }
                
                override fun onTripCancelled(tripId: Int) {
                    Logging.d(TAG, "Trip cancelled: $tripId")
                    if (tripResponse?.id == tripId) {
                        Logging.e(TAG, "Current trip was cancelled. Finishing activity.")
                        finish()
                    }
                }
                
                override fun onError(error: String) {
                    Logging.e(TAG, "Error from ActiveTripListener: $error")
                    messageViewUpdater?.updateText("Error: $error")
                }
                
                override fun onCountdownTextUpdate(countdownText: String) {
                    // Not used in HeadlessNavigActivity
                }
                
                override fun onCountdownComplete(trip: TripResponse) {
                    // Not used in HeadlessNavigActivity
                }
                
                override fun onNavigationStartRequested(trip: TripResponse, allowResume: Boolean) {
                    // Not used in HeadlessNavigActivity
                }
                
                override fun onTripScheduled(trip: TripResponse, departureTimeMillis: Long) {
                    // Not used in HeadlessNavigActivity
                }
                
                override fun onSettingsSynced(settings: com.gocavgo.validator.dataclass.VehicleSettings, isNavigating: Boolean): Boolean {
                    // Not used in HeadlessNavigActivity
                    return false
                }
                
                override fun onTripStateVerificationNeeded(trip: TripResponse) {
                    // Not used in HeadlessNavigActivity
                }
                
                override fun onImmediateBackendFetchComplete(trip: TripResponse?) {
                    // Not used in HeadlessNavigActivity
                }
                
                override fun onSilentBackendFetchFoundTrip(trip: TripResponse) {
                    // Not used in HeadlessNavigActivity
                }
            }
        )
    }
    
    private fun handleActiveTrip(trip: TripResponse) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Get simulate value from settings
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val settings = settingsManager.getSettings(vehicleId.toInt())
                isSimulated = settings?.simulate ?: false
                
                Logging.d(TAG, "Handling active trip: ${trip.id}, simulate: $isSimulated")
                
                // Load full trip from database to ensure we have all data
                tripResponse = databaseManager.getTripById(trip.id)
                if (tripResponse == null) {
                    Logging.e(TAG, "Trip with ID ${trip.id} not found in database. Cannot start navigation.")
                    withContext(Dispatchers.Main) {
                        messageViewUpdater?.updateText("Trip not found in database")
                        finish()
                    }
                    return@launch
                }

                // Switch to main thread for HERE SDK initialization
                withContext(Dispatchers.Main) {
                    // Initialize HERE SDK in background to prevent ANR
                    coroutineScope.launch(Dispatchers.IO) {
                        initializeHERESDK()
                    }
                    
                    // Update message to show trip data is loaded
                    messageViewUpdater?.updateText("Trip data loaded. Initializing navigation...")
                    
                    // Initialize App with null mapView for headless mode
                    initializeApp()
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error handling active trip: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    messageViewUpdater?.updateText("Error: ${e.message}")
                }
            }
        }
    }

    @Composable
    fun DialogScreen() {
        if (DialogManager.showDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (DialogManager.cancelable) {
                        DialogManager.hide()
                        DialogManager.onDismissCallback?.invoke()
                    }
                },
                title = { Text(DialogManager.dialogTitle) },
                text = {
                    DialogManager.dialogContent?.invoke()
                        ?: Text(DialogManager.dialogText.orEmpty())
                },
                confirmButton = {
                    TextButton(onClick = {
                        DialogManager.hide()
                        DialogManager.onDismissCallback?.invoke()
                    }) {
                        Text(DialogManager.dialogButtonText)
                    }
                }
            )
        }
    }

    // White space composable to replace map view
    @Composable
    private fun WhiteSpaceView() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        )
    }

    @Composable
    fun ButtonRows(messageViewUpdater: MessageViewUpdater, isRouteCalculated: Boolean) {
        val messageViewText by remember { messageViewUpdater.textState }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top row: Clear & Exit button (left aligned)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                CustomButton(
                    onClick = {
                        app?.clearMapAndExit()
                        finish() // Exit back to MainActivity
                    },
                    text = "Clear & Exit"
                )
            }
            
            // Middle row: Toggle buttons (centered)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera toggle disabled in headless mode (not applicable)
                ToggleButton(
                    "Camera: N/A", 
                    "Camera: N/A", 
                    enabled = false,
                    initialState = false
                ) { /* No-op in headless mode */ }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                ToggleButton(
                    "Waypoints: ON", 
                    "Waypoints: OFF", 
                    enabled = isRouteCalculated,
                    initialState = false // Waypoints overlay starts OFF
                ) { toggled ->
                    showWaypointOverlay = toggled
                    if (toggled) {
                        updateWaypointProgressData()
                        startWaypointProgressUpdates()
                    }
                }
            }
            
            // Bottom row: Message view (full width)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                MessageViewText(messageViewText)
            }
        }
    }

    @Composable
    fun MessageViewText(text: String) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .padding(8.dp)
        )
    }

    @Composable
    fun CustomButton(onClick: () -> Unit, text: String) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            )
        ) {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                softWrap = true
            )
        }
    }

    @Composable
    fun ToggleButton(
        textOn: String,
        textOff: String,
        enabled: Boolean = true,
        initialState: Boolean = false,
        onToggle: (Boolean) -> Unit
    ) {
        val isDark = isSystemInDarkTheme()
        val buttonColor = if (isDark) Color(0xCC007070) else Color(0xCC01B9B9)
        var isToggled by remember { mutableStateOf(initialState) }

        Button(
            onClick = {
                if (enabled) {
                    isToggled = !isToggled
                    onToggle(isToggled)
                }
            },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    !enabled -> Color.Gray.copy(alpha = 0.5f)
                    isToggled -> buttonColor
                    else -> Color.Gray
                },
            )
        ) {
            Text(
                color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                text = if (isToggled) textOn else textOff
            )
        }
    }

    private fun setupUI() {
        setContent {
            ValidatorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues)) {
                        WhiteSpaceView()
                        ButtonRows(messageViewUpdater!!, isRouteCalculated)
                        if (showWaypointOverlay) {
                            WaypointProgressOverlay(waypointProgressData)
                        }
                        DialogScreen()
                        
                        // Network status indicator overlay
                        NetworkStatusIndicator(
                            isConnected = isConnected,
                            connectionType = connectionType,
                            isMetered = isMetered
                        )
                    }
                }
            }
        }
    }

    private fun initializeApp() {
        try {
            // Ensure HERE SDK is initialized before creating App
            if (SDKNativeEngine.getSharedInstance() == null) {
                Logging.e(TAG, "HERE SDK not initialized, initializing now...")
                initializeHERESDK()
            }
            
            // Create App with null mapView for headless mode
            app = App(applicationContext, null, messageViewUpdater!!, tripResponse)
            Logging.d(TAG, "App instance created (headless mode): $app")

            // Set up route calculation callback
            app?.setOnRouteCalculatedCallback {
                updateRouteCalculationStatus(true)
            }

            // Now that App is created, update it with trip data if available
            updateTripDataWhenReady()
        } catch (e: Exception) {
            Logging.e(TAG, "Error initializing App: ${e.message}", e)
            messageViewUpdater?.updateText("Error initializing navigation: ${e.message}")
        }
    }

    private fun initializeHERESDK(lowMEm: Boolean = false) {
        try {
            // Check if SDK is already initialized to avoid duplicate initialization
            if (SDKNativeEngine.getSharedInstance() != null) {
                Logging.d(TAG, "HERE SDK already initialized, skipping")
                return
            }
            
            val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID
            val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET
            val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
            val options = SDKOptions(authenticationMode)
            if(lowMEm) {
                options.lowMemoryMode = true
                Logging.d(TAG, "Initialised in Low memory mode")
            }
            
            // Initialize SDK with timeout protection
            SDKNativeEngine.makeSharedInstance(this, options)
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
            // Don't throw RuntimeException to prevent ANR, just log and continue
            // The app can still function without HERE SDK in some cases
        } catch (e: Exception) {
            Logging.e(TAG, "Unexpected error during HERE SDK initialization: ${e.message}", e)
        }
    }

    private fun initializeNetworkMonitoring() {
        Logging.d(TAG, "Initializing network monitoring...")

        // Check if we have network permissions
        if (!NetworkUtils.hasNetworkPermissions(this)) {
            Logging.w(TAG, "Network permissions not available, using basic monitoring")
            return
        }

        networkMonitor = NetworkMonitor(this) { connected, type, metered ->
            Logging.d(TAG, "Network state changed: connected=$connected, type=$type, metered=$metered")
            
            // Update UI state
            isConnected = connected
            connectionType = type
            isMetered = metered
            
            // Update HERE SDK offline mode
            updateHERESDKOfflineMode(connected)
        }

        networkMonitor?.startMonitoring()
        Logging.d(TAG, "Network monitoring started")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentActivity.TRIM_MEMORY_RUNNING_CRITICAL) {
            handleLowMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        handleLowMemory()
    }
    
    fun handleLowMemory() {
        SDKNativeEngine.getSharedInstance()?.purgeMemoryCaches(SDKNativeEngine.PurgeMemoryStrategy.FULL)
        initializeHERESDK(true)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        Logging.d(TAG, "HeadlessNavigActivity onDestroy - starting cleanup")
        
        // Mark navigation as inactive
        isNavigationActive.set(false)
        
        // Stop active trip listener
        activeTripListener?.stop()
        activeTripListener = null
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        
        // First, stop all location services and navigation
        stopAllLocationServices()
        
        // Then detach the app (which stops navigation)
        app?.detach()
        
        // Dispose HERE SDK resources
        disposeHERESDK()
        
        // Cancel coroutines
        coroutineScope.cancel()
        
        Logging.d(TAG, "HeadlessNavigActivity onDestroy - cleanup completed")
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    private fun updateHERESDKOfflineMode(isConnected: Boolean) {
        try {
            // Check if HERE SDK is initialized before accessing it
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                sdkNativeEngine.setOfflineMode(!isConnected)
                Logging.d(TAG, "HERE SDK offline mode set to: ${!isConnected}")
            } else {
                Logging.d(TAG, "HERE SDK not yet initialized, will set offline mode when ready")
                // Store the desired offline mode state for when SDK is ready
                pendingOfflineMode = !isConnected
            }
        } catch (e: UnsatisfiedLinkError) {
            Logging.d(TAG, "HERE SDK native library not loaded yet, will set offline mode when ready")
            // Store the desired offline mode state for when SDK is ready
            pendingOfflineMode = !isConnected
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to update HERE SDK offline mode: ${e.message}", e)
        }
    }

    private fun disposeHERESDK() {
        // Free HERE SDK resources before the application shuts down.
        // Usually, this should be called only on application termination.
        // Afterwards, the HERE SDK is no longer usable unless it is initialized again.
        try {
            val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            if (sdkNativeEngine != null) {
                Logging.d(TAG, "Disposing HERE SDK...")
                
                // Additional cleanup to ensure all service connections are released
                try {
                    // Force stop any remaining location services
                    val locationEngine = com.here.sdk.location.LocationEngine()
                    if (locationEngine.isStarted) {
                        locationEngine.stop()
                        Logging.d(TAG, "Stopped remaining location engine before disposal")
                    }
                } catch (e: Exception) {
                    Logging.w(TAG, "Error stopping location engine before disposal: ${e.message}")
                }
                
                // Dispose the SDK
                sdkNativeEngine.dispose()
                Logging.d(TAG, "HERE SDK disposed successfully")
                
                // For safety reasons, we explicitly set the shared instance to null to avoid situations,
                // where a disposed instance is accidentally reused.
                SDKNativeEngine.setSharedInstance(null)
                Logging.d(TAG, "HERE SDK shared instance cleared")
            } else {
                Logging.d(TAG, "HERE SDK not initialized, nothing to dispose")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error disposing HERE SDK: ${e.message}", e)
        }
    }
    
    /**
     * Stop all location services and clean up connections to prevent service leaks
     */
    private fun stopAllLocationServices() {
        try {
            Logging.d(TAG, "Stopping all location services...")
            
            // Set shutdown flag first to prevent starting new services during cleanup
            app?.getNavigationExample()?.setShuttingDown(true)
            
            // Stop navigation after location services are stopped
            app?.getNavigationExample()?.let { navExample ->
                try {
                    // Stop headless navigation first (this will skip enabling device positioning due to shutdown flag)
                    navExample.stopHeadlessNavigation()
                    Logging.d(TAG, "Headless navigation stopped")
                } catch (e: Exception) {
                    Logging.w(TAG, "Error stopping headless navigation: ${e.message}")
                }
                
                try {
                    // Stop location services
                    navExample.stopLocating()
                    Logging.d(TAG, "Location services stopped")
                } catch (e: Exception) {
                    Logging.w(TAG, "Error stopping location services: ${e.message}")
                }
                
                try {
                    // Force disconnect HERE SDK location services to prevent leaks
                    navExample.getHerePositioningProvider().forceDisconnect()
                    Logging.d(TAG, "HERE SDK location services force disconnected")
                } catch (e: Exception) {
                    Logging.w(TAG, "Error force disconnecting location services: ${e.message}")
                }
                
                try {
                    // Stop rendering (should be no-op in headless mode, but safe to call)
                    navExample.stopRendering()
                    Logging.d(TAG, "Rendering stopped")
                } catch (e: Exception) {
                    Logging.w(TAG, "Error stopping rendering: ${e.message}")
                }
            }
            
            // Finally, stop and disconnect HERE SDK location services to prevent leaks
            try {
                val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
                if (sdkNativeEngine != null) {
                    // Stop location engine first to disconnect services
                    val locationEngine = com.here.sdk.location.LocationEngine()
                    if (locationEngine.isStarted) {
                        locationEngine.stop()
                        Logging.d(TAG, "HERE SDK LocationEngine stopped")
                    }
                }
            } catch (e: Exception) {
                Logging.w(TAG, "Error stopping HERE SDK LocationEngine: ${e.message}")
            }
            
            Logging.d(TAG, "All location services stopped successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during location services cleanup: ${e.message}", e)
        }
    }
    
    /**
     * Updates waypoint progress data from the validator
     */
    private fun updateWaypointProgressData() {
        app?.getTripSectionValidator()?.let { validator ->
            waypointProgressData = validator.getCurrentWaypointProgress()
        }
    }
    
    /**
     * Updates route calculation status
     */
    fun updateRouteCalculationStatus(calculated: Boolean) {
        isRouteCalculated = calculated
        Logging.d(TAG, "Route calculation status updated: $calculated")
    }
    
    /**
     * Updates trip data when it becomes available
     */
    fun updateTripDataWhenReady() {
        if (tripResponse != null && app != null) {
            Logging.d(TAG, "Updating App with trip data after both are ready")
            app?.updateTripData(tripResponse, isSimulated)
        }
    }
    
    /**
     * Starts periodic updates for waypoint progress data
     */
    private fun startWaypointProgressUpdates() {
        coroutineScope.launch {
            while (isActive && showWaypointOverlay) {
                updateWaypointProgressData()
                delay(2000) // Update every 2 seconds
            }
        }
    }
    

}
