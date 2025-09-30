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
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.viewinterop.AndroidView
import com.gocavgo.validator.BuildConfig
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.mapview.MapError
import com.here.sdk.mapview.MapFeatureModes
import com.here.sdk.mapview.MapFeatures
import com.here.sdk.mapview.MapScene
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import com.here.sdk.units.core.utils.EnvironmentLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class NavigActivity: ComponentActivity() {

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_SHOW_MAP = "show_map"
        const val EXTRA_IS_SIMULATED = "is_simulated"
        private val TAG: String = NavigActivity::class.java.simpleName
    }

    private val environmentLogger = EnvironmentLogger()
    private var permissionsRequestor: PermissionsRequestor? = null
    private var mapView: MapView? = null
    private var app: App? = null
    private var messageViewUpdater: MessageViewUpdater? = null

    // Trip data
    private var tripResponse: TripResponse? = null
    private var tripId: Int = -1
    private var showMap: Boolean = true
    private var isSimulated: Boolean = true

    // Database manager
    private lateinit var databaseManager: DatabaseManager

    // Coroutine scope
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get trip data from intent
        tripId = intent.getIntExtra(EXTRA_TRIP_ID, -1)
        showMap = intent.getBooleanExtra(EXTRA_SHOW_MAP, true)
        isSimulated = intent.getBooleanExtra(EXTRA_IS_SIMULATED, true)

        Log.d(TAG, "NavigActivity started with tripId: $tripId, showMap: $showMap, isSimulated: $isSimulated")

        if (tripId == -1) {
            Log.e(TAG, "No trip ID provided. Cannot start navigation.")
            finish()
            return
        }

        // Initialize HERE SDK first, before any UI setup
        initializeHERESDK()

        // Log application and device details.
        environmentLogger.logEnvironment("Kotlin")
        permissionsRequestor = PermissionsRequestor(this)

        enableEdgeToEdge()

        messageViewUpdater = MessageViewUpdater()

        // Initialize database manager
        databaseManager = DatabaseManager.getInstance(this)

        // Fetch trip data from database
        coroutineScope.launch {
            Log.d(TAG, "=== Starting trip data fetch ===")
            Log.d(TAG, "Fetching trip with ID: $tripId")

            tripResponse = databaseManager.getTripById(tripId)
            if (tripResponse == null) {
                Log.e(TAG, "Trip with ID $tripId not found in database. Cannot start navigation.")
                finish()
                return@launch
            }

            Log.d(TAG, "=== Trip data loaded successfully ===")
            Log.d(TAG, "Trip loaded: ${tripResponse?.id}")
            Log.d(TAG, "Origin: ${tripResponse?.route?.origin?.google_place_name}")
            Log.d(TAG, "Destination: ${tripResponse?.route?.destination?.google_place_name}")
            Log.d(TAG, "Waypoints: ${tripResponse?.waypoints?.size}")
            Log.d(TAG, "App instance: $app")

            // Update the App with the loaded trip data if it's available
            Log.d(TAG, "About to call app?.updateTripData...")
            if (app == null) {
                Log.d(TAG, "App instance not yet created. Trip data will be passed when App is ready.")
                messageViewUpdater?.updateText("Trip data loaded. Waiting for map...")
            } else {
                app?.updateTripData(tripResponse, isSimulated)
                Log.d(TAG, "app?.updateTripData called")
            }
        }

        setContent {
            ValidatorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues)) {
                        HereMapView(savedInstanceState)
                        ButtonRows(messageViewUpdater!!)
                        DialogScreen()
                    }
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

    // Wrap the MapView into a Composable in order to use it with Jetpack Compose.
    @Composable
    private fun HereMapView(savedInstanceState: Bundle?) {
        AndroidView(factory = { context ->
            MapView(context).apply {
                mapView = this
                mapView?.onCreate(savedInstanceState)

                // Shows an example of how to present application terms and a privacy policy dialog as
                // required by legal requirements when using HERE Positioning.
                // See the Positioning section in our Developer Guide for more details.
                // Afterwards, Android permissions need to be checked to allow using the device's sensors.
                val privacyHelper = HEREPositioningTermsAndPrivacyHelper(context)
                privacyHelper.showAppTermsAndPrivacyPolicyDialogIfNeeded(object :
                    HEREPositioningTermsAndPrivacyHelper.OnAgreedListener {
                    override fun onAgreed() {
                        handleAndroidPermissions()
                    }
                })
            }
        })
    }

    @Composable
    fun ButtonRows(messageViewUpdater: MessageViewUpdater) {
        val messageViewText by remember { messageViewUpdater.textState }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CustomButton(
                    onClick = {
                        app?.clearMapAndExit()
                        finish() // Exit back to MainActivity
                    },
                    text = "Clear & Exit"
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                ToggleButton("Camera Tracking: ON", "Camera Tracking: OFF") { toggled ->
                    if (toggled) app?.toggleTrackingButtonOnClicked() else app?.toggleTrackingButtonOffClicked()
                }
            }
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
        onToggle: (Boolean) -> Unit
    ) {
        val isDark = isSystemInDarkTheme()
        val buttonColor = if (isDark) Color(0xCC007070) else Color(0xCC01B9B9)
        var isToggled by remember { mutableStateOf(true) }

        Button(
            onClick = {
                isToggled = !isToggled
                onToggle(isToggled)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isToggled) buttonColor else Color.Gray,
            )
        ) {
            Text(
                color = MaterialTheme.colorScheme.onBackground,
                text = if (isToggled) textOn else textOff
            )
        }
    }

    private fun initializeHERESDK() {
        // Set your credentials for the HERE SDK.
        val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID
        val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET
        val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
        val options = SDKOptions(authenticationMode)
        try {
            val context: Context = this
            SDKNativeEngine.makeSharedInstance(context, options)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of HERE SDK failed: " + e.error.name)
        }
    }

    private fun handleAndroidPermissions() {
        permissionsRequestor = PermissionsRequestor(this)
        permissionsRequestor!!.request(object :
            PermissionsRequestor.ResultListener {
            override fun permissionsGranted() {
                loadMapScene()
            }

            override fun permissionsDenied() {
                Log.e(TAG, "Permissions denied by user.")
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsRequestor!!.onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun loadMapScene() {
        mapView!!.mapScene.loadScene(
            MapScheme.NORMAL_DAY,
            MapScene.LoadSceneCallback { mapError: MapError? ->
                if (mapError == null) {
                    // Start the app that contains the logic to calculate routes & start TBT guidance.
                    app = App(applicationContext, mapView!!, messageViewUpdater!!, tripResponse)
                    Log.d(TAG, "App instance created: $app")

                    // Enable traffic flows and 3D landmarks, by default.
                    val mapFeatures: MutableMap<String, String> = HashMap()
                    mapFeatures[MapFeatures.TRAFFIC_FLOW] = MapFeatureModes.TRAFFIC_FLOW_WITH_FREE_FLOW
                    mapFeatures[MapFeatures.LOW_SPEED_ZONES] = MapFeatureModes.LOW_SPEED_ZONES_ALL
                    mapFeatures[MapFeatures.LANDMARKS] = MapFeatureModes.LANDMARKS_TEXTURED
                    mapView!!.mapScene.enableFeatures(mapFeatures)

                    // Now that App is created, update it with trip data if available
                    if (tripResponse != null) {
                        Log.d(TAG, "Updating App with trip data after map scene loaded")
                        app?.updateTripData(tripResponse, isSimulated)
                    }
                } else {
                    Log.d(
                        TAG,
                        "Loading map failed: " + mapError.name
                    )
                }
            }
        )
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        mapView?.onResume()
        super.onResume()
    }

    override fun onDestroy() {
        app?.detach()
        mapView?.onDestroy()
        disposeHERESDK()
        coroutineScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun disposeHERESDK() {
        // Free HERE SDK resources before the application shuts down.
        // Usually, this should be called only on application termination.
        // Afterwards, the HERE SDK is no longer usable unless it is initialized again.
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine != null) {
            sdkNativeEngine.dispose()
            // For safety reasons, we explicitly set the shared instance to null to avoid situations,
            // where a disposed instance is accidentally reused.
            SDKNativeEngine.setSharedInstance(null)
        }
    }

}