package com.gocavgo.validator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.dataclass.TripStatus
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.navigator.HeadlessNavigActivity
import com.gocavgo.validator.service.MqttService
import com.gocavgo.validator.service.AutoModeForegroundService
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.ui.theme.ValidatorTheme
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.os.Build

class AutoModeActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "AutoModeActivity"
        private const val POLLING_INTERVAL_MS = 7 * 60 * 1000L // 7 minutes
        private const val COUNTDOWN_UPDATE_INTERVAL_MS = 1000L // 1 second
    }
    
    // Managers
    private lateinit var databaseManager: DatabaseManager
    private lateinit var vehicleSecurityManager: VehicleSecurityManager
    
    // MQTT Service
    private var mqttService: MqttService? = null
    
    // Trip state
    private val currentTrip = MutableStateFlow<TripResponse?>(null)
    private val tripState = MutableStateFlow(TripState.NO_TRIP)
    
    // Countdown text from service (will be updated via service)
    private val countdownText = MutableStateFlow("")
    
    // Polling fallback
    private var pollingJob: Job? = null
    private var lastMqttUpdateTime: Long = System.currentTimeMillis()
    
    // Foreground service
    private var foregroundService: AutoModeForegroundService? = null
    
    // Dialog state
    private var showPastTripDialog = mutableStateOf(false)
    private var pendingPastTrip: TripResponse? = null
    
    enum class TripState {
        NO_TRIP,
        TRIP_SCHEDULED,
        TRIP_LAUNCHING,
        TRIP_ACTIVE,
        TRIP_COMPLETED
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        Logging.d(TAG, "=== AUTO MODE ACTIVITY STARTED ===")
        
        // Initialize managers
        databaseManager = DatabaseManager.getInstance(this)
        vehicleSecurityManager = VehicleSecurityManager(this)
        
        // Start foreground service
        startAutoModeService()
        
        // Get MQTT service instance
        mqttService = MqttService.getInstance()
        
        // Set up UI
        setContent {
            ValidatorTheme {
                AutoModeScreen(
                    tripState = tripState.value,
                    currentTrip = currentTrip.value,
                    countdownText = countdownText.value,
                    showPastTripDialog = showPastTripDialog.value,
                    pastTrip = pendingPastTrip,
                    onPastTripConfirmed = { confirmed: Boolean, trip: TripResponse ->
                        handlePastTripConfirmation(confirmed, trip)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Initialize trip monitoring
        initializeTripMonitoring()
        
        // Handle back button press with confirmation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                androidx.appcompat.app.AlertDialog.Builder(this@AutoModeActivity)
                    .setTitle("Exit Auto Mode?")
                    .setMessage("This will stop automatic trip monitoring. Are you sure?")
                    .setPositiveButton("Yes") { _, _ ->
                        stopAutoModeService()
                        finish()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })
    }
    
    private fun startAutoModeService() {
        try {
            val serviceIntent = Intent(this, AutoModeForegroundService::class.java).apply {
                action = AutoModeForegroundService.ACTION_START_SERVICE
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Logging.d(TAG, "AutoMode foreground service started")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to start AutoMode service: ${e.message}", e)
        }
    }
    
    private fun stopAutoModeService() {
        try {
            val serviceIntent = Intent(this, AutoModeForegroundService::class.java).apply {
                action = AutoModeForegroundService.ACTION_STOP_SERVICE
            }
            startService(serviceIntent)
            
            Logging.d(TAG, "AutoMode foreground service stopped")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to stop AutoMode service: ${e.message}", e)
        }
    }
    
    private fun initializeTripMonitoring() {
        Logging.d(TAG, "Initializing trip monitoring...")
        
        // Check for existing active trip
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!vehicleSecurityManager.isVehicleRegistered()) {
                    Logging.w(TAG, "Vehicle not registered, waiting...")
                    return@launch
                }
                
                val vehicleId = vehicleSecurityManager.getVehicleId()
                val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                
                if (activeTrip != null) {
                    Logging.d(TAG, "Found existing active trip: ${activeTrip.id}")
                    handleTripReceived(activeTrip)
                } else {
                    Logging.d(TAG, "No active trip found, entering waiting state")
                    tripState.value = TripState.NO_TRIP
                    updateNotification("Waiting for trip...")
                }
                
                // Start fallback polling
                startPollingFallback()
                
                // Register MQTT trip event callback
                registerMqttTripEventCallback()
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error initializing trip monitoring: ${e.message}", e)
            }
        }
    }
    
    private fun registerMqttTripEventCallback() {
        Logging.d(TAG, "Registering MQTT trip event callback")
        
        mqttService?.setTripEventCallback { tripEvent ->
            Logging.d(TAG, "MQTT trip event received: ${tripEvent.event}")
            
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    // Convert backend trip to Android trip response
                    val tripResponse = com.gocavgo.validator.util.TripDataConverter.convertBackendTripToAndroid(tripEvent.data)
                    
                    Logging.d(TAG, "Trip event processed: ${tripResponse.id}")
                    handleTripReceived(tripResponse)
                    
                    // Reset polling fallback timer
                    lastMqttUpdateTime = System.currentTimeMillis()
                    
                } catch (e: Exception) {
                    Logging.e(TAG, "Error handling MQTT trip event: ${e.message}", e)
                }
            }
        }
    }
    
    private fun handleTripReceived(tripResponse: TripResponse) {
        Logging.d(TAG, "=== TRIP RECEIVED ===")
        Logging.d(TAG, "Trip ID: ${tripResponse.id}")
        Logging.d(TAG, "Status: ${tripResponse.status}")
        Logging.d(TAG, "Departure: ${tripResponse.departure_time}")
        Logging.d(TAG, "Origin: ${tripResponse.route.origin.google_place_name}")
        Logging.d(TAG, "Destination: ${tripResponse.route.destination.google_place_name}")
        
        // Check if trip departure time has already passed
        val currentTime = System.currentTimeMillis() / 1000
        val twoMinutesBeforeDeparture = tripResponse.departure_time - (2 * 60) // 2 minutes before
        
        if (currentTime > twoMinutesBeforeDeparture) {
            Logging.w(TAG, "Trip departure time has already passed, asking for confirmation")
            pendingPastTrip = tripResponse
            showPastTripDialog.value = true
        } else {
            currentTrip.value = tripResponse
            tripState.value = TripState.TRIP_SCHEDULED
            updateNotification("Trip scheduled: ${tripResponse.route.origin.google_place_name} → ${tripResponse.route.destination.google_place_name}")
            
            // Schedule navigation launch via service
            val service = AutoModeForegroundService.getInstance()
            service?.scheduleNavigationLaunch(tripResponse)
        }
    }
    
    private fun handlePastTripConfirmation(confirmed: Boolean, tripResponse: TripResponse) {
        if (confirmed) {
            Logging.d(TAG, "User confirmed past trip launch")
            currentTrip.value = tripResponse
            tripState.value = TripState.TRIP_SCHEDULED
            updateNotification("Trip scheduled: ${tripResponse.route.origin.google_place_name} → ${tripResponse.route.destination.google_place_name}")
            
            // Launch immediately for past trips
            val service = AutoModeForegroundService.getInstance()
            service?.scheduleNavigationLaunch(tripResponse)
        } else {
            Logging.d(TAG, "User declined past trip launch")
            Toast.makeText(this, "Trip monitoring cancelled", Toast.LENGTH_SHORT).show()
            tripState.value = TripState.NO_TRIP
            currentTrip.value = null
            updateNotification("Waiting for trip...")
        }
        showPastTripDialog.value = false
        pendingPastTrip = null
    }
    
    private fun startPollingFallback() {
        Logging.d(TAG, "Starting fallback polling every 7 minutes")
        
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(POLLING_INTERVAL_MS)
                    
                    // Check if MQTT update received recently
                    val timeSinceLastMqttUpdate = System.currentTimeMillis() - lastMqttUpdateTime
                    
                    if (timeSinceLastMqttUpdate >= POLLING_INTERVAL_MS) {
                        Logging.d(TAG, "No MQTT update in 7 minutes, polling database")
                        pollDatabaseForTrip()
                    } else {
                        Logging.d(TAG, "MQTT update received recently, skipping poll")
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in polling fallback: ${e.message}", e)
                }
            }
        }
    }
    
    private fun pollDatabaseForTrip() {
        Logging.d(TAG, "Polling database for trip changes...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!vehicleSecurityManager.isVehicleRegistered()) {
                    Logging.w(TAG, "Vehicle not registered, skipping poll")
                    return@launch
                }
                
                val vehicleId = vehicleSecurityManager.getVehicleId()
                
                // Check if trip still exists
                currentTrip.value?.let { trip ->
                    val updatedTrip = databaseManager.getTripById(trip.id)
                    if (updatedTrip == null) {
                        Logging.w(TAG, "Trip ${trip.id} was deleted")
                        handleTripDeleted()
                        return@launch
                    }
                    
                    // Update trip if status changed
                    if (updatedTrip.status != trip.status) {
                        Logging.d(TAG, "Trip status changed: ${trip.status} → ${updatedTrip.status}")
                        currentTrip.value = updatedTrip
                        
                        // If trip is completed, return to waiting state
                        if (TripStatus.isCompleted(updatedTrip.status)) {
                            handleTripCompleted()
                        }
                    }
                } ?: run {
                    // No current trip, check for new trips
                    val activeTrip = databaseManager.getActiveTripByVehicle(vehicleId.toInt())
                    if (activeTrip != null && tripState.value == TripState.NO_TRIP) {
                        Logging.d(TAG, "New active trip found: ${activeTrip.id}")
                        handleTripReceived(activeTrip)
                    }
                }
                
            } catch (e: Exception) {
                Logging.e(TAG, "Error polling database: ${e.message}", e)
            }
        }
    }
    
    private fun handleTripDeleted() {
        Logging.w(TAG, "Trip was deleted, showing notification and returning to waiting state")
        
        Toast.makeText(this, "Trip was cancelled or deleted", Toast.LENGTH_LONG).show()
        
        currentTrip.value = null
        tripState.value = TripState.NO_TRIP
        countdownText.value = ""
        
        // Cancel scheduled launch in service
        val service = AutoModeForegroundService.getInstance()
        service?.cancelScheduledLaunch()
        
        updateNotification("Waiting for trip...")
    }
    
    private fun handleTripCompleted() {
        Logging.d(TAG, "Trip completed, returning to waiting state")
        
        Toast.makeText(this, "Trip completed", Toast.LENGTH_SHORT).show()
        
        currentTrip.value = null
        tripState.value = TripState.NO_TRIP
        countdownText.value = ""
        
        // Cancel scheduled launch in service
        val service = AutoModeForegroundService.getInstance()
        service?.cancelScheduledLaunch()
        
        updateNotification("Waiting for trip...")
    }
    
    private fun updateNotification(state: String, countdown: String = "") {
        try {
            foregroundService?.updateNotification(state, countdown)
        } catch (e: Exception) {
            Logging.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
    
    
    override fun onDestroy() {
        Logging.d(TAG, "AutoModeActivity destroyed")
        
        // Cancel all background jobs
        pollingJob?.cancel()
        
        super.onDestroy()
    }
    
    // Use utility function to convert backend trip to Android trip response format
    private fun convertBackendTripToAndroid(backendTrip: com.gocavgo.validator.dataclass.TripData): TripResponse {
        return com.gocavgo.validator.util.TripDataConverter.convertBackendTripToAndroid(backendTrip)
    }
}

@Composable
fun AutoModeScreen(
    tripState: AutoModeActivity.TripState,
    currentTrip: TripResponse?,
    countdownText: String,
    showPastTripDialog: Boolean,
    pastTrip: TripResponse?,
    onPastTripConfirmed: (Boolean, TripResponse) -> Unit,
    modifier: Modifier = Modifier
) {
    // Past trip confirmation dialog
    if (showPastTripDialog && pastTrip != null) {
        AlertDialog(
            onDismissRequest = { onPastTripConfirmed(false, pastTrip) },
            title = {
                Text("Past Trip Detected")
            },
            text = {
                Text("This trip's departure time has already passed. Would you like to launch navigation anyway?")
            },
            confirmButton = {
                TextButton(onClick = { onPastTripConfirmed(true, pastTrip) }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { onPastTripConfirmed(false, pastTrip) }) {
                    Text("No")
                }
            }
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            when (tripState) {
                AutoModeActivity.TripState.NO_TRIP -> {
                    Text(
                        text = "GoCavGo Auto Mode",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "Waiting for trip...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                AutoModeActivity.TripState.TRIP_SCHEDULED -> {
                    currentTrip?.let { trip ->
                        Text(
                            text = "Next Trip",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        Text(
                            text = "${trip.route.origin.custom_name ?: trip.route.origin.google_place_name}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Text(
                            text = "↓",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        Text(
                            text = "${trip.route.destination.custom_name ?: trip.route.destination.google_place_name}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = countdownText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Text(
                            text = "Navigation will launch automatically",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                AutoModeActivity.TripState.TRIP_LAUNCHING -> {
                    Text(
                        text = "Launching Navigation",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp)
                    )
                }
                
                AutoModeActivity.TripState.TRIP_ACTIVE -> {
                    currentTrip?.let { trip ->
                        Text(
                            text = "Navigation Active",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        Text(
                            text = "${trip.route.origin.custom_name ?: trip.route.origin.google_place_name} → ${trip.route.destination.custom_name ?: trip.route.destination.google_place_name}",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                
                else -> {
                    Text(
                        text = "Unknown state",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
