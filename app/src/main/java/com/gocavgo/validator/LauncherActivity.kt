package com.gocavgo.validator

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.security.VehicleAuthActivity
import com.gocavgo.validator.security.ACTION_SETTINGS_CHANGED
import com.gocavgo.validator.security.ACTION_SETTINGS_DEACTIVATE
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
import io.sentry.Sentry

class LauncherActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LauncherActivity"
        private const val PERMISSIONS_REQUEST_CODE = 42
        private const val SETTINGS_STALE_THRESHOLD_MS = 1    * 60 * 1000L // 10 minutes
        private const val PREFS_NAME = "LauncherActivityPrefs"
        private const val KEY_EXACT_ALARM_FIRST_PROMPT_SHOWN = "exact_alarm_first_prompt_shown"
    }
    
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    private lateinit var settingsManager: VehicleSettingsManager
    private var permissionsRequestor: PermissionsRequestor? = null
    private lateinit var sharedPreferences: SharedPreferences
    
    private var isLoading by mutableStateOf(true)
    private var locationPermissionDenied by mutableStateOf(false)
    private var errorMessage by mutableStateOf("")
    private var isDeactivated by mutableStateOf(false)
    private var exactAlarmPermissionDenied by mutableStateOf(false)
    private var exactAlarmButtonClicked by mutableStateOf(false)
    
    // Settings change broadcast receiver
    private var settingsChangeReceiver: BroadcastReceiver? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    // waiting for view to draw to better represent a captured error with a screenshot
    findViewById<android.view.View>(android.R.id.content).viewTreeObserver.addOnGlobalLayoutListener {
      try {
          Sentry.logger().info("A simple log message")
          Sentry.logger().error("A %s log message", "formatted")
        throw Exception("This app uses Sentry! :)")
      } catch (e: Exception) {
        Sentry.captureException(e)
      }
    }

        enableEdgeToEdge()
        
        // Set this activity as active and disable its logging
        Logging.setActivityLoggingEnabled(TAG, false)
        Logging.d(TAG, "=== LAUNCHER ACTIVITY STARTED ===")

        // Initialize managers
        vehicleSecurityManager = VehicleSecurityManager(this)
        settingsManager = VehicleSettingsManager.getInstance(this)
        permissionsRequestor = PermissionsRequestor(this)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Initialize UI
        setContent {
            ValidatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SplashScreen(
                        isLoading = isLoading,
                        locationPermissionDenied = locationPermissionDenied,
                        errorMessage = errorMessage,
                        isDeactivated = isDeactivated,
                        exactAlarmPermissionDenied = exactAlarmPermissionDenied,
                        onRetryPermissions = { requestPermissions() },
                        onGrantExactAlarm = { requestExactAlarmPermission() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        
        // Request permissions
        requestPermissions()
        
        // Register settings change receiver
        registerSettingsChangeReceiver()
    }
    
    private fun requestPermissions() {
        Logging.d(TAG, "Requesting permissions")
        isLoading = true
        locationPermissionDenied = false
        exactAlarmPermissionDenied = false
        exactAlarmButtonClicked = false
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
        
        // Location granted, check exact alarm permission
        checkExactAlarmPermission()
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
    
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // SCHEDULE_EXACT_ALARM is only required on Android 12+
            Logging.d(TAG, "Android version < 12, exact alarm permission not required")
            evaluateSettingsAndNavigate()
            return
        }
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val hasExactAlarm = alarmManager.canScheduleExactAlarms()
        
        Logging.d(TAG, "Exact alarm permission status: $hasExactAlarm")
        
        if (hasExactAlarm) {
            // Permission granted, proceed
            exactAlarmPermissionDenied = false
            exactAlarmButtonClicked = false
            evaluateSettingsAndNavigate()
        } else {
            // Permission not granted, handle based on first-time or later
            val firstPromptShown = sharedPreferences.getBoolean(KEY_EXACT_ALARM_FIRST_PROMPT_SHOWN, false)
            
            if (!firstPromptShown) {
                // First time: just continue without warning
                Logging.d(TAG, "First time - exact alarm denied, continuing without prompt")
                sharedPreferences.edit().putBoolean(KEY_EXACT_ALARM_FIRST_PROMPT_SHOWN, true).apply()
                exactAlarmPermissionDenied = false
                exactAlarmButtonClicked = false
                evaluateSettingsAndNavigate()
            } else {
                // Subsequent launches: show warning with button for 2 seconds
                Logging.w(TAG, "Exact alarm permission denied - showing warning UI for 2 seconds")
                exactAlarmPermissionDenied = true
                exactAlarmButtonClicked = false
                isLoading = false
                
                // Auto-continue after 2 seconds if button wasn't clicked
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (!exactAlarmButtonClicked) {
                        Logging.d(TAG, "2 seconds passed without button click - continuing")
                        exactAlarmPermissionDenied = false
                        evaluateSettingsAndNavigate()
                    }
                    // If button was clicked, wait for onResume to handle permission result
                }
            }
        }
    }
    
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        
        // Mark button as clicked to prevent auto-continue
        exactAlarmButtonClicked = true
        Logging.d(TAG, "Exact alarm permission button clicked - opening settings and waiting for result")
        
        try {
            Intent().also {
                it.action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                startActivity(it)
            }
            // Don't continue here - wait for onResume to check permission result
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to open exact alarm settings: ${e.message}", e)
            errorMessage = "Failed to open settings: ${e.message}"
            exactAlarmButtonClicked = false
        }
    }
    
    private fun evaluateSettingsAndNavigate() {
        isLoading = true
        locationPermissionDenied = false
        locationPermissionDenied = false
        exactAlarmPermissionDenied = false
        exactAlarmButtonClicked = false
        
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
        
        // PRIORITY 1: Check deactivate FIRST - lock the app if deactivated
        if (settings.deactivate) {
            Logging.d(TAG, "deactivate=true - LOCKING APP (preventing navigation)")
            isDeactivated = true
            isLoading = false
            // Don't navigate anywhere - stay on LauncherActivity with locked screen
            return
        }
        
        // Clear deactivated state if it was previously set
        isDeactivated = false
        
        // PRIORITY 2: Check logout
        if (settings.logout) {
            Logging.d(TAG, "logout=true - navigating to VehicleAuthActivity")
            navigateToActivity(VehicleAuthActivity::class.java)
            return
        }
        
        // PRIORITY 3: Check if all settings are false
        if (settingsManager.areAllSettingsFalse(settings)) {
            Logging.d(TAG, "All settings false - navigating to AutoModeHeadlessActivity")
            navigateToActivity(AutoModeHeadlessActivity::class.java)
            return
        }
        
        // PRIORITY 4: Check devmode
        if (!settings.devmode) {
            Logging.d(TAG, "devmode=false - navigating to AutoModeHeadlessActivity")
            navigateToActivity(AutoModeHeadlessActivity::class.java)
            return
        }
        
        // PRIORITY 5: devmode=true - navigate to MainActivity
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
        
        // Re-check exact alarm permission on resume (user might have granted it after clicking button)
        if (exactAlarmButtonClicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                Logging.d(TAG, "Exact alarm permission granted on resume after button click")
                exactAlarmPermissionDenied = false
                exactAlarmButtonClicked = false
                evaluateSettingsAndNavigate()
                return
            } else {
                // Permission still not granted, but button was clicked - continue anyway
                Logging.d(TAG, "Exact alarm permission still denied after button click - continuing")
                exactAlarmPermissionDenied = false
                exactAlarmButtonClicked = false
                evaluateSettingsAndNavigate()
                return
            }
        }
        
        // Re-check settings on resume in case deactivate status changed
        if (vehicleSecurityManager.isVehicleRegistered()) {
            lifecycleScope.launch {
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val savedSettings = withContext(Dispatchers.IO) {
                    settingsManager.getSettings(vehicleId.toInt())
                }
                
                savedSettings?.let { settings ->
                    // If deactivated, ensure we stay locked
                    if (settings.deactivate) {
                        Logging.d(TAG, "Settings check on resume: deactivate=true - keeping app locked")
                        isDeactivated = true
                        isLoading = false
                    } else if (isDeactivated) {
                        // If no longer deactivated, re-evaluate settings
                        Logging.d(TAG, "Settings check on resume: deactivate=false - re-evaluating settings")
                        isDeactivated = false
                        routeBasedOnSettings(settings)
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister settings change receiver
        unregisterSettingsChangeReceiver()
    }
    
    /**
     * Register broadcast receiver for settings changes
     */
    private fun registerSettingsChangeReceiver() {
        settingsChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                
                when (intent.action) {
                    ACTION_SETTINGS_DEACTIVATE -> {
                        val isDeactivatedValue = intent.getBooleanExtra("is_deactivated", false)
                        Logging.d(TAG, "Received deactivate broadcast - isDeactivated: $isDeactivatedValue")
                        if (isDeactivatedValue) {
                            // Lock the app immediately
                            Logging.d(TAG, "Deactivate=true received via broadcast - locking app")
                            isDeactivated = true
                            isLoading = false
                        } else if (isDeactivated) {
                            // If deactivate was cleared, re-evaluate settings
                            Logging.d(TAG, "Deactivate=false received via broadcast - re-evaluating settings")
                            lifecycleScope.launch {
                                val vehicleId = vehicleSecurityManager.getVehicleId()
                                val savedSettings = withContext(Dispatchers.IO) {
                                    settingsManager.getSettings(vehicleId.toInt())
                                }
                                savedSettings?.let { settings ->
                                    isDeactivated = false
                                    routeBasedOnSettings(settings)
                                }
                            }
                        }
                    }
                    ACTION_SETTINGS_CHANGED -> {
                        Logging.d(TAG, "Received settings changed broadcast - checking deactivate status")
                        lifecycleScope.launch {
                            val vehicleId = vehicleSecurityManager.getVehicleId()
                            val savedSettings = withContext(Dispatchers.IO) {
                                settingsManager.getSettings(vehicleId.toInt())
                            }
                            savedSettings?.let { settings ->
                                // Check deactivate status
                                if (settings.deactivate) {
                                    Logging.d(TAG, "Settings changed: deactivate=true - locking app")
                                    isDeactivated = true
                                    isLoading = false
                                } else if (isDeactivated) {
                                    // If no longer deactivated, re-evaluate settings
                                    Logging.d(TAG, "Settings changed: deactivate=false - re-evaluating settings")
                                    isDeactivated = false
                                    routeBasedOnSettings(settings)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(ACTION_SETTINGS_DEACTIVATE)
            addAction(ACTION_SETTINGS_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsChangeReceiver, filter)
        }
        Logging.d(TAG, "Settings change receiver registered")
    }
    
    /**
     * Unregister settings change receiver
     */
    private fun unregisterSettingsChangeReceiver() {
        settingsChangeReceiver?.let {
            try {
                unregisterReceiver(it)
                Logging.d(TAG, "Settings change receiver unregistered")
            } catch (e: Exception) {
                Logging.e(TAG, "Error unregistering settings change receiver: ${e.message}", e)
            }
            settingsChangeReceiver = null
        }
    }
    
}

@Composable
fun SplashScreen(
    isLoading: Boolean,
    locationPermissionDenied: Boolean,
    errorMessage: String,
    isDeactivated: Boolean,
    exactAlarmPermissionDenied: Boolean,
    onRetryPermissions: () -> Unit,
    onGrantExactAlarm: () -> Unit,
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
                isDeactivated -> {
                    // Locked/Deactivated screen
                    Text(
                        text = "App Deactivated",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "This vehicle has been deactivated. Please contact your administrator.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    // No button - app is locked, user cannot proceed
                }
                exactAlarmPermissionDenied -> {
                    Text(
                        text = "Exact Alarm Permission",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Exact alarm permission is needed for optimal app performance. Please grant this permission in settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onGrantExactAlarm,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Grant Permission")
                    }
                }
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

