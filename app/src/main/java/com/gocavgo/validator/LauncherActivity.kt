package com.gocavgo.validator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.security.VehicleAuthActivity
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.service.MqttHealthCheckWorker
import com.gocavgo.validator.service.NetworkMonitorWorker
import com.gocavgo.validator.service.SettingsTimeoutWorker
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LauncherActivity"
        private const val PERMISSIONS_REQUEST_CODE = 42
        private const val SETTINGS_STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
    }
    
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var settingsManager: VehicleSettingsManager
    private var permissionsRequestor: PermissionsRequestor? = null
    
    private var isLoading by mutableStateOf(true)
    private var locationPermissionDenied by mutableStateOf(false)
    private var errorMessage by mutableStateOf("")
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Set this activity as active and disable its logging
        Logging.setActivityLoggingEnabled(TAG, false)
        Logging.d(TAG, "=== LAUNCHER ACTIVITY STARTED ===")
        
        // Initialize managers
        vehicleSecurityManager = VehicleSecurityManager(this)
        settingsManager = VehicleSettingsManager.getInstance(this)
        permissionsRequestor = PermissionsRequestor(this)
        
        // Initialize UI
        setContent {
            ValidatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SplashScreen(
                        isLoading = isLoading,
                        locationPermissionDenied = locationPermissionDenied,
                        errorMessage = errorMessage,
                        onRetryPermissions = { requestPermissions() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        
        // Request permissions
        requestPermissions()
    }
    
    private fun requestPermissions() {
        Logging.d(TAG, "Requesting permissions")
        isLoading = true
        locationPermissionDenied = false
        errorMessage = ""
        
        permissionsRequestor?.request(object : PermissionsRequestor.ResultListener {
            override fun permissionsGranted() {
                Logging.d(TAG, "All permissions granted")
                checkLocationAndProceed()
            }

            override fun permissionsDenied() {
                Logging.w(TAG, "Some permissions denied")
                checkLocationAndProceed()
            }
        })
    }
    
    private fun checkLocationAndProceed() {
        val hasLocation = checkLocationPermission()
        
        Logging.d(TAG, "Location permission status: $hasLocation")
        
        if (!hasLocation) {
            Logging.w(TAG, "Location permission denied - showing retry UI")
            locationPermissionDenied = true
            isLoading = false
            return
        }
        
        // Location granted, proceed with settings evaluation
        evaluateSettingsAndNavigate()
    }
    
    private fun checkLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val coarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        return fineLocation || coarseLocation
    }
    
    private fun evaluateSettingsAndNavigate() {
        isLoading = true
        locationPermissionDenied = false
        
        Logging.d(TAG, "=== EVALUATING SETTINGS AND NAVIGATING ===")
        
        // Check if vehicle is registered
        if (!vehicleSecurityManager.isVehicleRegistered()) {
            Logging.d(TAG, "Vehicle not registered - navigating to VehicleAuthActivity")
            navigateToActivity(VehicleAuthActivity::class.java)
            return
        }
        
        val vehicleId = vehicleSecurityManager.getVehicleId()
        Logging.d(TAG, "Vehicle registered: ID $vehicleId")
        
        // Initialize WorkManager services
        initializeWorkManagerServices()
        
        // Fetch settings and route
        // ALWAYS check database first (source of truth)
        // Only fetch from API if database is stale (older than 10 minutes) or doesn't exist
        lifecycleScope.launch {
            try {
                // STEP 1: Always check database first (settings are always saved to DB)
                val savedSettings = withContext(Dispatchers.IO) {
                    settingsManager.getSettings(vehicleId.toInt())
                }
                
                val dbLastUpdated = withContext(Dispatchers.IO) {
                    settingsManager.getSettingsLastUpdated(vehicleId.toInt())
                }
                
                val currentTime = System.currentTimeMillis()
                val isDbStale = dbLastUpdated == null || (currentTime - dbLastUpdated) > SETTINGS_STALE_THRESHOLD_MS
                
                Logging.d(TAG, "=== SETTINGS EVALUATION ===")
                Logging.d(TAG, "Database settings exist: ${savedSettings != null}")
                Logging.d(TAG, "Database last updated: ${dbLastUpdated?.let { "${(currentTime - it) / 1000}s ago" } ?: "Never"}")
                Logging.d(TAG, "Database is stale: $isDbStale")
                
                // STEP 2: If database has recent settings (within 10 minutes), use them immediately
                if (savedSettings != null && !isDbStale) {
                    Logging.d(TAG, "Using fresh settings from database (updated ${(currentTime - dbLastUpdated!!) / 1000}s ago)")
                    Logging.d(TAG, "logout: ${savedSettings.logout}")
                    Logging.d(TAG, "devmode: ${savedSettings.devmode}")
                    Logging.d(TAG, "deactivate: ${savedSettings.deactivate}")
                    Logging.d(TAG, "appmode: ${savedSettings.appmode}")
                    Logging.d(TAG, "simulate: ${savedSettings.simulate}")
                    
                    // Apply settings (check logout/deactivate)
                    settingsManager.applySettings(this@LauncherActivity, savedSettings)
                    
                    // Route based on settings
                    routeBasedOnSettings(savedSettings)
                    
                    // STEP 3: Still fetch from API in background to keep database fresh (but don't wait)
                    // This ensures database stays up-to-date for next time
                    launch(Dispatchers.IO) {
                        try {
                            val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                            result.onSuccess { freshSettings ->
                                Logging.d(TAG, "Background API fetch successful - database updated")
                                // Database is automatically updated by fetchSettingsFromApi
                            }.onFailure { error ->
                                Logging.w(TAG, "Background API fetch failed: ${error.message} - using existing database settings")
                                // Continue with existing database settings
                            }
                        } catch (e: Exception) {
                            Logging.w(TAG, "Background API fetch exception: ${e.message}")
                            // Continue with existing database settings
                        }
                    }
                } 
                // STEP 4: If database is stale or doesn't exist, fetch from API
                else {
                    if (savedSettings == null) {
                        Logging.d(TAG, "No settings in database - fetching from API")
                    } else {
                        Logging.d(TAG, "Database settings are stale (${(currentTime - dbLastUpdated!!) / 1000}s old) - fetching from API")
                    }
                    
                    val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                    
                    result.onSuccess { settings ->
                        Logging.d(TAG, "Settings fetched successfully from API")
                        Logging.d(TAG, "logout: ${settings.logout}")
                        Logging.d(TAG, "devmode: ${settings.devmode}")
                        Logging.d(TAG, "deactivate: ${settings.deactivate}")
                        Logging.d(TAG, "appmode: ${settings.appmode}")
                        Logging.d(TAG, "simulate: ${settings.simulate}")
                        
                        // Settings are automatically saved to database by fetchSettingsFromApi
                        
                        // Apply settings (check logout/deactivate)
                        settingsManager.applySettings(this@LauncherActivity, settings)
                        
                        // Route based on settings
                        routeBasedOnSettings(settings)
                    }.onFailure { error ->
                        Logging.e(TAG, "Failed to fetch settings from API: ${error.message}")
                        
                        // Try using saved settings from database (even if stale)
                        if (savedSettings != null) {
                            Logging.d(TAG, "Using existing database settings as fallback (may be stale)")
                            settingsManager.applySettings(this@LauncherActivity, savedSettings)
                            routeBasedOnSettings(savedSettings)
                        } else {
                            errorMessage = "Failed to load settings: ${error.message}"
                            isLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception evaluating settings: ${e.message}", e)
                errorMessage = "Error: ${e.message}"
                isLoading = false
            }
        }
    }
    
    private fun routeBasedOnSettings(settings: com.gocavgo.validator.dataclass.VehicleSettings) {
        Logging.d(TAG, "=== ROUTING BASED ON SETTINGS ===")
        
        // Check logout first
        if (settings.logout) {
            Logging.d(TAG, "logout=true - navigating to VehicleAuthActivity")
            navigateToActivity(VehicleAuthActivity::class.java)
            return
        }
        
        // Check if all settings are false
        if (settingsManager.areAllSettingsFalse(settings)) {
            Logging.d(TAG, "All settings false - navigating to AutoModeHeadlessActivity")
            navigateToActivity(AutoModeHeadlessActivity::class.java)
            return
        }
        
        // Check devmode
        if (!settings.devmode) {
            Logging.d(TAG, "devmode=false - navigating to AutoModeHeadlessActivity")
            navigateToActivity(AutoModeHeadlessActivity::class.java)
            return
        }
        
        // devmode=true - navigate to MainActivity
        Logging.d(TAG, "devmode=true - navigating to MainActivity")
        navigateToActivity(MainActivity::class.java)
    }
    
    private fun navigateToActivity(activityClass: Class<*>) {
        Logging.d(TAG, "Navigating to ${activityClass.simpleName}")
        
        val intent = Intent(this, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun initializeWorkManagerServices() {
        try {
            Logging.d(TAG, "Initializing WorkManager services")
            
            // Schedule WorkManager health checks
            MqttHealthCheckWorker.schedule(this)
            
            // Schedule network monitoring worker
            NetworkMonitorWorker.schedule(this)
            
            // Schedule settings timeout worker (checks for 4-day timeout)
            SettingsTimeoutWorker.schedule(this)
            
            Logging.d(TAG, "WorkManager services initialized successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize WorkManager services: ${e.message}", e)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsRequestor?.onRequestPermissionsResult(requestCode, grantResults)
    }
    
    override fun onResume() {
        super.onResume()
        
        // Set this activity as the active one for logging
        Logging.setActiveActivity(TAG)
    }
    
}

@Composable
fun SplashScreen(
    isLoading: Boolean,
    locationPermissionDenied: Boolean,
    errorMessage: String,
    onRetryPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // App name/logo
            Text(
                text = "Validator",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            when {
                isLoading -> {
                    CircularProgressIndicator()
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                locationPermissionDenied -> {
                    Text(
                        text = "Location Required",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Location permission is required for navigation features to work properly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onRetryPermissions,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Grant Permission")
                    }
                }
                errorMessage.isNotEmpty() -> {
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onRetryPermissions,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

