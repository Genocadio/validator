package com.gocavgo.validator.service

import android.annotation.SuppressLint
import android.content.Context
import com.gocavgo.validator.util.Logging
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.gocavgo.validator.dataclass.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.database.BookingEntity
import com.gocavgo.validator.database.PaymentEntity
import com.gocavgo.validator.database.TicketEntity
import com.gocavgo.validator.database.TripEntity
import com.gocavgo.validator.network.NetworkMonitor
import com.gocavgo.validator.sync.SyncCoordinator
import android.content.Intent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MqttService private constructor(
    private val context: Context,
    private val brokerHost: String,
    private val brokerPort: Int = 1883,
    private val carId: String,
    private val clientId: String = "android_car_${carId}_${UUID.randomUUID()}"
) {

    companion object {
        private const val TAG = "MqttService"
        private val QOS = MqttQos.AT_LEAST_ONCE
        private const val KEEP_ALIVE_SECONDS = 60
        private const val RECONNECT_DELAY_MS = 5000L // Legacy constant, not used
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val CONNECTION_TIMEOUT_MS = 15000L // Increased timeout
        private const val BACKGROUND_RECONNECT_DELAY_MS = 3000L // Reduced from 10000L for faster reconnection
        private const val FOREGROUND_RECONNECT_DELAY_MS = 1000L // Reduced from 2000L for faster reconnection
        private const val MAX_QUEUE_SIZE = 100
        private const val MAX_RECONNECT_BACKOFF_MS = 30000L // Reduced from 60000L - Max 30 seconds between attempts
        private const val MAX_RECONNECT_ATTEMPTS = 100 // Increased from 10 - never give up
        
        // Continuous monitoring
        private const val CONNECTION_CHECK_INTERVAL_MS = 10000L // Check connection every 10 seconds
        
        // Background optimization constants
        private const val FOREGROUND_HEARTBEAT_INTERVAL_MS = 30000L // 30s when active
        private const val BACKGROUND_HEARTBEAT_INTERVAL_MS = 60000L // Reduced from 120000L - 1min when background for faster detection

        // Broadcast action for UI when a booking bundle is saved locally
        const val ACTION_BOOKING_BUNDLE_SAVED = "com.gocavgo.validator.BOOKING_BUNDLE_SAVED"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: MqttService? = null

        fun getInstance(
            context: Context,
            brokerHost: String,
            brokerPort: Int = 1883,
            carId: String
        ): MqttService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttService(
                    context.applicationContext,
                    brokerHost,
                    brokerPort,
                    carId
                ).also { INSTANCE = it }
            }
        }

        fun getInstance(): MqttService? = INSTANCE
    }

    // Jackson ObjectMapper for JSON serialization/deserialization
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true // Include null values in MQTT messages for timestamp fields
    }

    // HiveMQ MQTT5 Client
    private var mqttClient: Mqtt5AsyncClient? = null

    // IO scope for background work
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // Message listeners
    private val messageListeners = ConcurrentHashMap<String, (String, String) -> Unit>()

    // Connection callback
    private var connectionCallback: ((Boolean) -> Unit)? = null
    
    // Booking bundle callback for direct UI notification
    private var bookingBundleCallback: ((String, String, String, String, Int, Boolean) -> Unit)? = null
    
    // Trip event callbacks - router pattern for Activity and Service
    private var activityTripEventCallback: ((com.gocavgo.validator.dataclass.TripEventMessage) -> Unit)? = null
    private var serviceTripEventCallback: ((com.gocavgo.validator.dataclass.TripEventMessage) -> Unit)? = null
    
    // Legacy single callback (for backward compatibility, routes to activity callback)
    @Deprecated("Use setTripEventCallback for Activity or setServiceTripEventCallback for Service")
    private var tripEventCallback: ((com.gocavgo.validator.dataclass.TripEventMessage) -> Unit)? = null
    
    // Notification manager for background notifications
    private var notificationManager: MqttNotificationManager? = null

    // Connection state management
    private val isConnecting = AtomicBoolean(false)
    private val isDisconnecting = AtomicBoolean(false)
    private val reconnectAttempts = AtomicLong(0)
    private val lastConnectionTime = AtomicLong(0)
    private val isAppInForeground = AtomicBoolean(true)
    private val isNetworkAvailable = AtomicBoolean(true)
    
    // Message queue for offline periods
    private val messageQueue = ConcurrentHashMap<String, MutableList<QueuedMessage>>()
    
    // Heartbeat management
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionMonitorJob: Job? = null // Continuous connection monitoring
    
    // Connection credentials
    private var username: String? = null
    private var password: String? = null
    
    // Network monitor for connectivity changes
    private var networkMonitor: NetworkMonitor? = null
    
    // Lifecycle state
    private val isServiceActive = AtomicBoolean(false)
    private val lastHeartbeatTime = AtomicLong(0)
    private val connectionLostTime = AtomicLong(0)
    private val isWaitingForNetwork = AtomicBoolean(false)

    // MQTT Topics for this car
    private val carTopics = listOf(
        "car/$carId/trip",           // Trip assignments
        "car/$carId/ping",           // Ping requests
        "car/$carId/settings",       // Vehicle settings updates
        "trip/+/booking",            // Booking events for any trip (wildcard)
        "trip/+/bookings",           // Booking updates for any trip (wildcard)
        "trip/+/booking_bundle/outbound"      // Full booking bundle from backend (booking + payment + tickets)
    )

    // Last Will and Testament
    private val lastWillTopic = "car/$carId/status"
    private val lastWillMessage: String
        get() = json.encodeToString(
            CarStatusMessage(
                vehicle_id = carId,
                status = "OFFLINE",
                timestamp = System.currentTimeMillis(),
                foreground = false,
                bearing = null
            )
        )

    // Data class for queued messages
    data class QueuedMessage(
        val topic: String,
        val payload: String,
        val qos: MqttQos,
        val retained: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Serializable data class for heartbeat messages
    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class HeartbeatMessage(
        val vehicle_id: String,
        val timestamp: Long,
        val status: String,
        val foreground: Boolean,
        val current_latitude: Double? = null,
        val current_longitude: Double? = null,
        val current_speed: Double? = null,
        val bearing: Double? = null
    )

    // Serializable data class for waypoint reached notifications
    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class WaypointReachedNotification(
        val trip_id: String,
        val vehicle_id: String,
        val waypoint_id: Int,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    // Serializable data class for heartbeat responses
    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class HeartbeatResponseMessage(
        val vehicle_id: String,
        val ping_time: Long,
        val response_time: Long,
        val status: String
    )

    // Serializable data class for trip assignment confirmations
    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class TripAssignmentConfirmation(
        val trip_id: String,
        val vehicle_id: String,
        val accepted: Boolean,
        val timestamp: Long,
        val reason: String? = null
    )

    /**
     * Initialize and connect to MQTT broker
     */
    @SuppressLint("CheckResult")
    fun connect(username: String? = null, password: String? = null) {
        if (isServiceActive.get()) {
            Logging.d(TAG, "MQTT service already active, skipping connection")
            return
        }
        
        isServiceActive.set(true)
        this.username = username
        this.password = password
        
        // Initialize network monitoring
        initializeNetworkMonitoring()
        
        // Start connection process
        startConnectionProcess()
    }
    
    /**
     * Initialize network monitoring for connectivity changes
     */
    private fun initializeNetworkMonitoring() {
        try {
            networkMonitor = NetworkMonitor(context) { connected, type, metered ->
                Logging.d(TAG, "=== NETWORK STATE CHANGED ===")
                Logging.d(TAG, "Connected: $connected")
                Logging.d(TAG, "Type: $type")
                Logging.d(TAG, "Metered: $metered")
                Logging.d(TAG, "============================")
                
                val wasConnected = isNetworkAvailable.get()
                isNetworkAvailable.set(connected)
                
                if (connected && !wasConnected) {
                    // Network came back online
                    Logging.d(TAG, "Network restored, checking if service should restart...")
                    if (isWaitingForNetwork.get()) {
                        Logging.d(TAG, "Service was waiting for network, restarting...")
                        restartAfterNetworkRestored()
                    } else {
                        Logging.d(TAG, "Service not waiting for network, attempting reconnection...")
                        scheduleReconnection()
                    }
                } else if (!connected && wasConnected) {
                    // Network lost
                    Logging.d(TAG, "Network lost, shutting down service...")
                    connectionLostTime.set(System.currentTimeMillis())
                    shutdownDueToNetworkLoss()
                }
            }
            
            networkMonitor?.startMonitoring()
            Logging.d(TAG, "Network monitoring initialized")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize network monitoring: ${e.message}", e)
        }
    }
    
    /**
     * Start the connection process with proper error handling
     */
    private fun startConnectionProcess() {
        if (!isNetworkAvailable.get()) {
            Logging.w(TAG, "No network available, scheduling reconnection")
            scheduleReconnection()
            return
        }
        
        // If we're disconnecting, wait for it to complete
        if (isDisconnecting.get()) {
            Logging.d(TAG, "Service is disconnecting, scheduling reconnection after delay")
            scheduleReconnection()
            return
        }
        
        // Check if connection is already in progress and if it's been too long (timeout)
        if (isConnecting.get()) {
            val connectionStartTime = lastConnectionTime.get()
            val timeSinceConnectionStart = System.currentTimeMillis() - connectionStartTime
            val connectionTimeout = CONNECTION_TIMEOUT_MS + 5000 // Add 5s buffer
            
            if (timeSinceConnectionStart > connectionTimeout && connectionStartTime > 0) {
                Logging.w(TAG, "Connection attempt timed out (${timeSinceConnectionStart}ms), resetting connection state")
                isConnecting.set(false)
                // Continue to attempt new connection
            } else {
                Logging.d(TAG, "Connection already in progress (${timeSinceConnectionStart}ms), skipping")
                return
            }
        }
        
        isConnecting.set(true)
        reconnectAttempts.set(0)
        lastConnectionTime.set(System.currentTimeMillis()) // Set before connection attempt
        
        try {
            performConnection()
        } catch (e: Exception) {
            Logging.e(TAG, "Connection process failed: ${e.message}", e)
            isConnecting.set(false)
            scheduleReconnection()
        }
    }
    
    /**
     * Perform the actual MQTT connection
     */
    @SuppressLint("CheckResult")
    private fun performConnection() {
        try {
            Logging.d(TAG, "Connecting to HiveMQ broker: $brokerHost:$brokerPort")

            // Build MQTT5 client with proper TLS configuration
            val clientBuilder = MqttClient.builder()
                .useMqttVersion5()
                .identifier(clientId)
                .serverHost(brokerHost)
                .serverPort(brokerPort)
                .automaticReconnectWithDefaultConfig()

            // Add TLS configuration for secure connections
            if (brokerPort == 8883) {
                clientBuilder.sslWithDefaultConfig()
                Logging.d(TAG, "SSL/TLS configuration applied for port 8883")
            }

            mqttClient = clientBuilder.buildAsync()

            // Build connect message with timeout
            val connectBuilder = mqttClient!!.connectWith()
                .cleanStart(true)
                .keepAlive(KEEP_ALIVE_SECONDS)
                .willPublish()
                .topic(lastWillTopic)
                .payload(lastWillMessage.toByteArray())
                .qos(QOS)
                .retain(false)
                .applyWillPublish()

            // Add credentials if provided
            username?.let { user ->
                Logging.d(TAG, "Adding authentication credentials for user: $user")
                connectBuilder.simpleAuth()
                    .username(user)
                    .password(password?.toByteArray() ?: ByteArray(0))
                    .applySimpleAuth()
            }

            // Set up message handler
            mqttClient!!.publishes(MqttGlobalPublishFilter.ALL) { publish: Mqtt5Publish ->
                val topic = publish.topic.toString()
                val payload = String(publish.payloadAsBytes)
                Logging.d(TAG, "📨 Message received on topic: $topic")
                Logging.d(TAG, "📄 Payload: $payload")
                handleMessage(topic, payload)
            }
            
            // Note: Connection state monitoring is handled in the connection callback

            // Connect with detailed error handling and timeout
            Logging.d(TAG, "Attempting MQTT connection...")
            connectBuilder.send()
                .whenComplete { connAck, throwable ->
                    if (throwable != null) {
                        Logging.e(TAG, "❌ MQTT Connection failed!")
                        Logging.e(TAG, "Error type: ${throwable.javaClass.simpleName}")
                        Logging.e(TAG, "Error message: ${throwable.message}")
                        Logging.e(TAG, "Error cause: ${throwable.cause?.message}")

                        // Log specific SSL/TLS errors
                        if (throwable.message?.contains("SSL") == true ||
                            throwable.message?.contains("TLS") == true) {
                            Logging.e(TAG, "SSL/TLS connection error - check broker SSL configuration")
                        }

                        throwable.printStackTrace()
                        connectionCallback?.invoke(false)
                    } else {
                        Logging.i(TAG, "✅ Successfully connected to HiveMQ broker!")
                        Logging.d(TAG, "Connection ACK reason: ${connAck.reasonCode}")
                        Logging.d(TAG, "Session present: ${connAck.isSessionPresent}")
                        Logging.d(TAG, "Connection restrictions: ${connAck.restrictions}")

                        // Update connection state
                        isConnecting.set(false)
                        lastConnectionTime.set(System.currentTimeMillis())
                        reconnectAttempts.set(0)
                        connectionLostTime.set(0)

                        // Publish online status
                        publishOnlineStatus()

                        // Subscribe to car-specific topics
                        subscribeToTopics()

                        // Start heartbeat mechanism
                        startHeartbeat()
                        
                        // Start continuous connection monitoring
                        startConnectionMonitoring()

                        // Process queued messages
                        processQueuedMessages()

                        connectionCallback?.invoke(true)
                    }
                }
                .exceptionally { ex ->
                    Logging.e(TAG, "❌ Connection timeout or exception", ex)
                    connectionCallback?.invoke(false)
                    null
                }

        } catch (e: Exception) {
            Logging.e(TAG, "❌ Failed to initialize MQTT client", e)
            Logging.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Logging.e(TAG, "Exception message: ${e.message}")
            e.printStackTrace()
            connectionCallback?.invoke(false)
        }
    }

    /**
     * Disconnect from MQTT broker (user-initiated)
     */
    fun disconnect() {
        try {
            Logging.d(TAG, "Disconnecting from MQTT broker...")
            isDisconnecting.set(true)
            
            // Stop all background jobs
            stopHeartbeat()
            stopReconnection()
            stopConnectionMonitoring()
            stopNetworkMonitoring()
            
            if (isConnected()) {
                try {
                    // Publish offline status before disconnecting
                    publishOfflineStatus()
                } catch (e: Exception) {
                    Logging.w(TAG, "Failed to publish offline status: ${e.message}")
                }

                mqttClient?.disconnect()?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Logging.e(TAG, "Error during disconnect", throwable)
                    } else {
                        Logging.i(TAG, "Disconnected from MQTT broker")
                    }
                    // Clear connection state after disconnect completes
                    isServiceActive.set(false)
                    isConnecting.set(false)
                    isDisconnecting.set(false)
                    isWaitingForNetwork.set(false)
                    mqttClient = null
                }
            } else {
                // Not connected, just clear state
                isServiceActive.set(false)
                isConnecting.set(false)
                isDisconnecting.set(false)
                isWaitingForNetwork.set(false)
                mqttClient = null
            }
            
        } catch (e: Exception) {
            Logging.e(TAG, "Error during disconnect", e)
            // Ensure state is cleared even on error
            isServiceActive.set(false)
            isConnecting.set(false)
            isDisconnecting.set(false)
            isWaitingForNetwork.set(false)
            mqttClient = null
        }
    }
    
    /**
     * Shutdown service due to network loss (preserves credentials for restart)
     */
    private fun shutdownDueToNetworkLoss() {
        try {
            Logging.d(TAG, "Shutting down MQTT service due to network loss...")
            isDisconnecting.set(true)
            
            // Stop all background jobs
            stopHeartbeat()
            stopReconnection()
            stopConnectionMonitoring()
            
            if (isConnected()) {
                try {
                    // Publish offline status before disconnecting
                    publishOfflineStatus()
                } catch (e: Exception) {
                    Logging.w(TAG, "Failed to publish offline status: ${e.message}")
                }

                mqttClient?.disconnect()?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Logging.e(TAG, "Error during network-triggered disconnect", throwable)
                    } else {
                        Logging.i(TAG, "Disconnected from MQTT broker due to network loss")
                    }
                    // Clear connection state but preserve credentials and mark as waiting
                    isServiceActive.set(false)
                    isConnecting.set(false)
                    isDisconnecting.set(false)
                    isWaitingForNetwork.set(true)
                    mqttClient = null
                }
            } else {
                // Not connected, just mark as waiting for network
                isServiceActive.set(false)
                isConnecting.set(false)
                isDisconnecting.set(false)
                isWaitingForNetwork.set(true)
                mqttClient = null
            }
            
        } catch (e: Exception) {
            Logging.e(TAG, "Error during network-triggered shutdown", e)
            // Ensure state is cleared even on error
            isServiceActive.set(false)
            isConnecting.set(false)
            isDisconnecting.set(false)
            isWaitingForNetwork.set(true)
            mqttClient = null
        }
    }
    
    /**
     * Restart service after network is restored
     */
    private fun restartAfterNetworkRestored() {
        try {
            Logging.d(TAG, "Restarting MQTT service after network restoration...")
            
            if (!isWaitingForNetwork.get()) {
                Logging.d(TAG, "Service not waiting for network, skipping restart")
                return
            }
            
            // Reset waiting state
            isWaitingForNetwork.set(false)
            
            // Store previous connection callback
            val previousCallback = connectionCallback
            
            // Set temporary callback to notify when connection is restored
            connectionCallback = { connected ->
                // Call previous callback if it exists
                previousCallback?.invoke(connected)
                
                if (connected) {
                    Logging.d(TAG, "MQTT reconnected after network restore - connection callback notified")
                    // Connection callback will be handled by Activity/Service to send trip status/heartbeat
                }
            }
            
            // Restart with preserved credentials
            connect(username, password)
            
        } catch (e: Exception) {
            Logging.e(TAG, "Error restarting service after network restoration: ${e.message}", e)
            // If restart fails, mark as waiting again
            isWaitingForNetwork.set(true)
        }
    }
    
    /**
     * Handle app lifecycle changes
     */
    fun onAppForeground() {
        Logging.d(TAG, "App moved to foreground")
        isAppInForeground.set(true)
        
        // Always check connection and reconnect if needed
        if (isServiceActive.get()) {
            if (!isConnected()) {
                Logging.d(TAG, "App in foreground but MQTT disconnected, reconnecting...")
                // Reset connection state to allow reconnection
                isConnecting.set(false)
                reconnectAttempts.set(0)
                scheduleReconnection()
            } else {
                Logging.d(TAG, "MQTT is connected, no reconnection needed")
            }
        } else {
            Logging.d(TAG, "MQTT service is not active, will connect when service starts")
        }
    }
    
    /**
     * Handle app lifecycle changes
     */
    fun onAppBackground() {
        Logging.d(TAG, "App moved to background")
        isAppInForeground.set(false)
        
        // Continue running in background but with reduced activity
        if (isServiceActive.get()) {
            Logging.d(TAG, "Continuing MQTT service in background")
        }
    }
    
    /**
     * Handle network state changes from NetworkMonitor
     */
    fun onNetworkStateChanged(connected: Boolean, connectionType: String, metered: Boolean) {
        Logging.d(TAG, "Network state changed: connected=$connected, type=$connectionType, metered=$metered")
        
        val wasNetworkAvailable = isNetworkAvailable.get()
        isNetworkAvailable.set(connected)
        
        if (connected && !wasNetworkAvailable) {
            Logging.d(TAG, "Network became available, attempting reconnection...")
            if (isServiceActive.get() && !isConnected()) {
                scheduleReconnection()
            }
        } else if (!connected && wasNetworkAvailable) {
            Logging.d(TAG, "Network became unavailable, pausing operations...")
            // Network is down, but don't disconnect immediately
            // Let the connection timeout handle it naturally
        }
    }
    
    /**
     * Schedule reconnection with exponential backoff
     */
    private fun scheduleReconnection() {
        if (reconnectJob?.isActive == true) {
            Logging.d(TAG, "Reconnection already scheduled")
            return
        }
        
        val currentAttempts = reconnectAttempts.get()
        // Removed MAX_RECONNECT_ATTEMPTS check - never give up on reconnection for maximum stability
        
        val delay = if (isAppInForeground.get()) {
            (FOREGROUND_RECONNECT_DELAY_MS * (1L shl currentAttempts.toInt().coerceAtMost(5))).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
        } else {
            (BACKGROUND_RECONNECT_DELAY_MS * (1L shl currentAttempts.toInt().coerceAtMost(3))).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
        }
        
        Logging.d(TAG, "Scheduling reconnection in ${delay}ms (attempt ${currentAttempts + 1})")
        
        reconnectJob = ioScope.launch {
            try {
                delay(delay)
                
                if (isServiceActive.get() && isNetworkAvailable.get()) {
                    Logging.d(TAG, "Attempting reconnection...")
                    reconnectAttempts.incrementAndGet()
                    startConnectionProcess()
                } else {
                    Logging.d(TAG, "Skipping reconnection - service inactive or no network")
                }
            } catch (e: CancellationException) {
                Logging.d(TAG, "Reconnection cancelled")
            } catch (e: Exception) {
                Logging.e(TAG, "Reconnection failed: ${e.message}", e)
                scheduleReconnection()
            }
        }
    }
    
    /**
     * Stop reconnection attempts
     */
    private fun stopReconnection() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
    
    /**
     * Start heartbeat mechanism to keep connection alive
     */
    private fun startHeartbeat() {
        stopHeartbeat() // Stop any existing heartbeat
        
        heartbeatJob = ioScope.launch {
            try {
                while (isActive && isServiceActive.get()) {
                    val heartbeatInterval = getHeartbeatInterval()
                    delay(heartbeatInterval)
                    
                    if (isConnected() && !isDisconnecting.get()) {
                        try {
                            // Send heartbeat
                            sendHeartbeat()
                            lastHeartbeatTime.set(System.currentTimeMillis())
                            Logging.d(TAG, "Heartbeat sent successfully (interval: ${heartbeatInterval}ms)")
                        } catch (e: Exception) {
                            Logging.e(TAG, "Heartbeat send failed: ${e.message}", e)
                            
                            // If heartbeat fails, check if we're still connected
                            if (!isConnected()) {
                                Logging.w(TAG, "Heartbeat failed - connection lost, scheduling reconnection")
                                scheduleReconnection()
                                break
                            }
                        }
                    } else {
                        Logging.w(TAG, "Heartbeat skipped - not connected or disconnecting")
                        if (!isDisconnecting.get()) {
                            // Continue checking and trigger reconnection instead of breaking
                            // This ensures heartbeat keeps monitoring and triggers reconnection faster
                            scheduleReconnection()
                            // Don't break - continue loop to keep checking connection state
                            delay(5000) // Wait 5 seconds before checking again
                        } else {
                            break // Only break if explicitly disconnecting
                        }
                    }
                }
            } catch (e: CancellationException) {
                Logging.d(TAG, "Heartbeat cancelled")
            } catch (e: Exception) {
                Logging.e(TAG, "Heartbeat error: ${e.message}", e)
            }
        }
    }
    
    /**
     * Get heartbeat interval based on app foreground state
     */
    private fun getHeartbeatInterval(): Long {
        return if (isAppInForeground.get()) {
            FOREGROUND_HEARTBEAT_INTERVAL_MS
        } else {
            BACKGROUND_HEARTBEAT_INTERVAL_MS
        }
    }
    
    /**
     * Stop heartbeat mechanism
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Send heartbeat to keep connection alive
     */
    private fun sendHeartbeat() {
        try {
            // Get latest location from database
            val vehicleId = carId.toIntOrNull()
            var currentLat: Double? = null
            var currentLng: Double? = null
            var currentSpeed: Double? = null
            var bearing: Double? = null
            
            if (vehicleId != null) {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val vehicleLocationDao = db.vehicleLocationDao()
                    val vehicleLocation = kotlinx.coroutines.runBlocking {
                        vehicleLocationDao.getVehicleLocation(vehicleId)
                    }
                    
                    if (vehicleLocation != null) {
                        currentLat = vehicleLocation.latitude
                        currentLng = vehicleLocation.longitude
                        currentSpeed = vehicleLocation.speed
                        bearing = vehicleLocation.bearing
                        Logging.d(TAG, "Including location in heartbeat: lat=$currentLat, lng=$currentLng, speed=$currentSpeed, bearing=$bearing")
                    } else {
                        Logging.d(TAG, "No location data available for heartbeat message (sending nulls)")
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to fetch location for heartbeat: ${e.message}", e)
                }
            }
            
            val heartbeat = HeartbeatMessage(
                vehicle_id = carId,
                timestamp = System.currentTimeMillis(),
                status = "ONLINE",
                foreground = isAppInForeground.get(),
                current_latitude = currentLat,
                current_longitude = currentLng,
                current_speed = currentSpeed,
                bearing = bearing
            )
            
            val payload = json.encodeToString(heartbeat)
            publish("car/$carId/heartbeat", payload, QOS, false)
                .whenComplete { result, throwable ->
                    if (throwable != null) {
                        Logging.w(TAG, "Heartbeat send failed: ${throwable.message}")
                    } else {
                        Logging.d(TAG, "Heartbeat sent successfully with location data")
                    }
                }
        } catch (e: Exception) {
            Logging.e(TAG, "Error sending heartbeat: ${e.message}", e)
        }
    }
    
    /**
     * Process queued messages when connection is restored
     */
    private fun processQueuedMessages() {
        try {
            if (messageQueue.isEmpty()) {
                Logging.d(TAG, "No queued messages to process")
                return
            }
            
            Logging.d(TAG, "Processing ${messageQueue.size} queued message topics...")
            
            messageQueue.forEach { (topic, messages) ->
                Logging.d(TAG, "Processing ${messages.size} messages for topic: $topic")
                
                messages.forEach { queuedMessage ->
                    publish(
                        queuedMessage.topic,
                        queuedMessage.payload,
                        queuedMessage.qos,
                        queuedMessage.retained
                    ).whenComplete { result, throwable ->
                        if (throwable != null) {
                            Logging.w(TAG, "Failed to send queued message to $topic: ${throwable.message}")
                        } else {
                            Logging.d(TAG, "Queued message sent to $topic")
                        }
                    }
                }
            }
            
            // Clear processed messages
            messageQueue.clear()
            Logging.d(TAG, "All queued messages processed")
            
        } catch (e: Exception) {
            Logging.e(TAG, "Error processing queued messages: ${e.message}", e)
        }
    }
    
    /**
     * Stop network monitoring
     */
    private fun stopNetworkMonitoring() {
        networkMonitor?.stopMonitoring()
        networkMonitor = null
    }
    
    /**
     * Start continuous connection monitoring
     * Checks connection state every 10 seconds and triggers reconnection if needed
     */
    private fun startConnectionMonitoring() {
        stopConnectionMonitoring() // Stop any existing monitoring
        
        connectionMonitorJob = ioScope.launch {
            try {
                while (isActive && isServiceActive.get()) {
                    delay(CONNECTION_CHECK_INTERVAL_MS)
                    
                    if (isDisconnecting.get()) {
                        continue // Skip checks while disconnecting
                    }
                    
                    val isConnected = isConnected()
                    val isActive = isServiceActive.get()
                    val isNetworkAvailable = isNetworkAvailable.get()
                    val lastHeartbeat = lastHeartbeatTime.get()
                    val currentTime = System.currentTimeMillis()
                    val heartbeatTimeout = BACKGROUND_HEARTBEAT_INTERVAL_MS * 3 // 3x heartbeat interval
                    
                    // Check if heartbeat is stale
                    val isHeartbeatStale = lastHeartbeat > 0 && (currentTime - lastHeartbeat) > heartbeatTimeout
                    
                    // If service is active but not connected, trigger reconnection immediately
                    if (isActive && !isConnected && isNetworkAvailable) {
                        Logging.w(TAG, "Connection monitor: Service active but not connected, triggering reconnection")
                        if (reconnectJob?.isActive != true) {
                            scheduleReconnection()
                        }
                    }
                    
                    // If connected but heartbeat is stale, connection might be dead
                    if (isConnected && isHeartbeatStale) {
                        Logging.w(TAG, "Connection monitor: Heartbeat stale (${currentTime - lastHeartbeat}ms old), connection may be dead")
                        // Force reconnection
                        if (reconnectJob?.isActive != true) {
                            scheduleReconnection()
                        }
                    }
                    
                    // If we've been trying to connect for too long (>30 seconds), reset state
                    if (isConnecting.get()) {
                        val connectionStartTime = lastConnectionTime.get()
                        val timeSinceConnectionStart = currentTime - connectionStartTime
                        if (timeSinceConnectionStart > CONNECTION_TIMEOUT_MS * 2) {
                            Logging.w(TAG, "Connection monitor: Connection attempt stuck for ${timeSinceConnectionStart}ms, resetting")
                            isConnecting.set(false)
                            scheduleReconnection()
                        }
                    }
                }
            } catch (e: CancellationException) {
                Logging.d(TAG, "Connection monitoring cancelled")
            } catch (e: Exception) {
                Logging.e(TAG, "Connection monitoring error: ${e.message}", e)
            }
        }
        
        Logging.d(TAG, "Connection monitoring started (checking every ${CONNECTION_CHECK_INTERVAL_MS}ms)")
    }
    
    /**
     * Stop continuous connection monitoring
     */
    private fun stopConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
    }
    
    /**
     * Force reconnection (useful for testing or manual recovery)
     */
    fun forceReconnect() {
        Logging.d(TAG, "Force reconnection requested")
        
        // Stop any existing reconnection attempts
        stopReconnection()
        
        if (isConnected()) {
            Logging.d(TAG, "Disconnecting before force reconnect")
            disconnect()
            
            // Wait a bit for disconnect to complete, then reconnect
            ioScope.launch {
                try {
                    delay(1000) // Wait 1 second for disconnect to complete
                    if (!isDisconnecting.get()) {
                        reconnectAttempts.set(0)
                        startConnectionProcess()
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in force reconnect delay: ${e.message}", e)
                    reconnectAttempts.set(0)
                    startConnectionProcess()
                }
            }
        } else {
            Logging.d(TAG, "Not connected, starting connection process immediately")
            reconnectAttempts.set(0)
            startConnectionProcess()
        }
    }
    
    /**
     * Check and fix inconsistent service state
     * More aggressive in detecting and fixing issues
     */
    fun checkAndFixInconsistentState() {
        val isActive = isServiceActive.get()
        val isConnected = isConnected()
        val isNetworkAvailable = isNetworkAvailable.get()
        val lastConnectionTime = lastConnectionTime.get()
        val currentTime = System.currentTimeMillis()
        val timeSinceLastConnection = if (lastConnectionTime > 0) currentTime - lastConnectionTime else Long.MAX_VALUE
        
        Logging.d(TAG, "Checking service state: active=$isActive, connected=$isConnected, network=$isNetworkAvailable, timeSinceConnection=${timeSinceLastConnection}ms")
        
        // If we're connected but marked as inactive, fix the state
        if (isConnected && !isActive && isNetworkAvailable) {
            Logging.w(TAG, "Detected inconsistent state: connected but not active, fixing...")
            isServiceActive.set(true)
            startHeartbeat()
            startConnectionMonitoring()
        }
        
        // If we're active but not connected and have network, try to reconnect
        if (isActive && !isConnected && isNetworkAvailable) {
            // Check if we've been stuck for >30 seconds - trigger full restart
            if (timeSinceLastConnection > 30000 && lastConnectionTime > 0) {
                Logging.w(TAG, "Detected inconsistent state: active but not connected for >30s, triggering full restart")
                fullRestart()
            } else {
                Logging.w(TAG, "Detected inconsistent state: active but not connected, reconnecting...")
                // Reset connection state to allow immediate reconnection
                isConnecting.set(false)
                reconnectAttempts.set(0)
                scheduleReconnection()
            }
        }
        
        // If we're stuck in connecting state for too long, reset
        if (isConnecting.get() && timeSinceLastConnection > CONNECTION_TIMEOUT_MS * 2) {
            Logging.w(TAG, "Connection attempt stuck for ${timeSinceLastConnection}ms, resetting state")
            isConnecting.set(false)
            scheduleReconnection()
        }
    }
    
    /**
     * Full restart of MQTT service - completely resets and reinitializes
     * Used when service is completely stuck or unhealthy for extended period
     */
    fun fullRestart() {
        Logging.w(TAG, "=== FULL RESTART INITIATED ===")
        
        try {
            // Stop all background jobs
            stopHeartbeat()
            stopReconnection()
            stopConnectionMonitoring()
            
            // Disconnect if connected
            if (isConnected()) {
                try {
                    mqttClient?.disconnect()?.whenComplete { _, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Error during full restart disconnect", throwable)
                        }
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Exception during full restart disconnect: ${e.message}", e)
                }
            }
            
            // Clear all state flags
            isServiceActive.set(false)
            isConnecting.set(false)
            isDisconnecting.set(false)
            isWaitingForNetwork.set(false)
            reconnectAttempts.set(0)
            lastConnectionTime.set(0)
            lastHeartbeatTime.set(0)
            connectionLostTime.set(0)
            
            // Clear client
            mqttClient = null
            
            // Wait a moment for cleanup
            ioScope.launch {
                try {
                    delay(2000) // Wait 2 seconds for cleanup
                    
                    // Restart with preserved credentials
                    if (username != null && password != null) {
                        Logging.d(TAG, "Full restart: Reconnecting with preserved credentials")
                        connect(username, password)
                    } else {
                        Logging.w(TAG, "Full restart: No credentials available, service will need to be reinitialized")
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error during full restart reconnection: ${e.message}", e)
                }
            }
            
            Logging.w(TAG, "=== FULL RESTART COMPLETE ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error during full restart: ${e.message}", e)
        }
    }
    
    /**
     * Check if service is active and healthy
     */
    fun isHealthy(): Boolean {
        val isActive = isServiceActive.get()
        val isConnected = isConnected()
        val isNetworkAvailable = isNetworkAvailable.get()
        val isWaiting = isWaitingForNetwork.get()
        val lastHeartbeat = lastHeartbeatTime.get()
        val currentTime = System.currentTimeMillis()
        val heartbeatTimeout = HEARTBEAT_INTERVAL_MS * 5 // More lenient timeout (5x heartbeat interval)
        
        val isHeartbeatValid = lastHeartbeat == 0L || (currentTime - lastHeartbeat) < heartbeatTimeout
        
        // If waiting for network, consider healthy if we have network and are not connected
        if (isWaiting) {
            val healthy = isNetworkAvailable && !isConnected
            if (!healthy) {
                Logging.d(TAG, "Health check failed (waiting for network): active=$isActive, connected=$isConnected, network=$isNetworkAvailable, waiting=$isWaiting")
            }
            return healthy
        }
        
        // If we're connected and have network, consider it healthy even if not "active"
        // This handles the case where disconnect() is called but connection is still active
        val healthy = if (isConnected && isNetworkAvailable) {
            // If connected, we're healthy regardless of active state (handles disconnect race condition)
            isHeartbeatValid
        } else {
            // If not connected, we need to be active and have network
            isActive && isNetworkAvailable && isHeartbeatValid
        }
        
        if (!healthy) {
            Logging.d(TAG, "Health check failed: active=$isActive, connected=$isConnected, network=$isNetworkAvailable, heartbeatValid=$isHeartbeatValid, waiting=$isWaiting")
        }
        
        return healthy
    }
    
    /**
     * Get service status information
     */
    fun getServiceStatus(): Map<String, Any> {
        return mapOf(
            "isActive" to isServiceActive.get(),
            "isConnected" to isConnected(),
            "isNetworkAvailable" to isNetworkAvailable.get(),
            "isAppInForeground" to isAppInForeground.get(),
            "isWaitingForNetwork" to isWaitingForNetwork.get(),
            "reconnectAttempts" to reconnectAttempts.get(),
            "lastConnectionTime" to lastConnectionTime.get(),
            "lastHeartbeatTime" to lastHeartbeatTime.get(),
            "queuedMessages" to messageQueue.values.sumOf { it.size },
            "clientState" to (mqttClient?.state?.name ?: "UNKNOWN")
        )
    }

    /**
     * Subscribe to all car-specific topics
     */
    private fun subscribeToTopics() {
        carTopics.forEach { topic ->
            subscribe(topic)
        }
    }

    /**
     * Subscribe to a specific topic
     */
    private fun subscribe(topic: String) {
        try {
            if (isConnected()) {
                mqttClient?.subscribeWith()
                    ?.topicFilter(topic)
                    ?.qos(QOS)
                    ?.send()
                    ?.whenComplete { subAck, throwable ->
                        if (throwable != null) {
                            Logging.e(TAG, "Failed to subscribe to topic: $topic", throwable)
                        } else {
                            Logging.d(TAG, "Subscribed to topic: $topic")
                            Logging.d(TAG, "Subscription reason codes: ${subAck.reasonCodes}")
                        }
                    }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to subscribe to topic: $topic", e)
        }
    }

    /**
     * Publish a message to a topic
     */
    fun publish(
        topic: String,
        payload: String,
        qos: MqttQos = QOS,
        retained: Boolean = false
    ): CompletableFuture<Mqtt5PublishResult> {
        return try {
            // Check if we're in the middle of disconnecting
            if (isDisconnecting.get()) {
                Logging.w(TAG, "Cannot publish - service is disconnecting, queuing message")
                queueMessage(topic, payload, qos, retained)
                val queuedFuture = CompletableFuture<Mqtt5PublishResult>()
                queuedFuture.completeExceptionally(IllegalStateException("Service disconnecting - message queued"))
                return queuedFuture
            }
            
            if (isConnected() && mqttClient != null) {
                val future = mqttClient!!.publishWith()
                    .topic(topic)
                    .payload(payload.toByteArray())
                    .qos(qos)
                    .retain(retained)
                    .send()

                future.whenComplete { publishResult, throwable ->
                    if (throwable != null) {
                        Logging.e(TAG, "Failed to publish to topic: $topic", throwable)
                        
                        // If it's a session expired error, trigger reconnection
                        if (throwable.message?.contains("Session expired") == true ||
                            throwable.message?.contains("connection was closed") == true) {
                            Logging.w(TAG, "Session expired, triggering reconnection")
                            scheduleReconnection()
                        }
                    } else {
                        Logging.d(TAG, "Published to topic: $topic")
                        Logging.d(TAG, "Payload: $payload")
                        Logging.d(
                            TAG,
                            "Publish result: ${publishResult?.error?.orElse(null) ?: "SUCCESS"}"
                        )
                    }
                }

                future
            } else {
                Logging.w(TAG, "Cannot publish - not connected to broker, queuing message")
                queueMessage(topic, payload, qos, retained)
                
                val queuedFuture = CompletableFuture<Mqtt5PublishResult>()
                queuedFuture.completeExceptionally(IllegalStateException("Not connected - message queued"))
                queuedFuture
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to publish to topic: $topic", e)
            
            // If it's a session expired error, trigger reconnection
            if (e.message?.contains("Session expired") == true ||
                e.message?.contains("connection was closed") == true) {
                Logging.w(TAG, "Session expired during publish, triggering reconnection")
                scheduleReconnection()
            }
            
            val failedFuture = CompletableFuture<Mqtt5PublishResult>()
            failedFuture.completeExceptionally(e)
            failedFuture
        }
    }
    
    /**
     * Queue a message for later sending when connection is restored
     */
    private fun queueMessage(topic: String, payload: String, qos: MqttQos, retained: Boolean) {
        try {
            val queuedMessage = QueuedMessage(topic, payload, qos, retained)
            
            messageQueue.computeIfAbsent(topic) { mutableListOf() }.let { messages ->
                // Limit queue size to prevent memory issues
                if (messages.size >= MAX_QUEUE_SIZE) {
                    messages.removeAt(0) // Remove oldest message
                }
                messages.add(queuedMessage)
            }
            
            Logging.d(TAG, "Message queued for topic: $topic (queue size: ${messageQueue[topic]?.size ?: 0})")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to queue message: ${e.message}", e)
        }
    }

    /**
     * Publish car online status
     */
    private fun publishOnlineStatus() {
        // Get latest location from database
        val vehicleId = carId.toIntOrNull()
        var currentLat: Double? = null
        var currentLng: Double? = null
        var currentSpeed: Double? = null
        var bearing: Double? = null
        
        if (vehicleId != null) {
            try {
                val db = AppDatabase.getDatabase(context)
                val vehicleLocationDao = db.vehicleLocationDao()
                val vehicleLocation = kotlinx.coroutines.runBlocking {
                    vehicleLocationDao.getVehicleLocation(vehicleId)
                }
                
                if (vehicleLocation != null) {
                    currentLat = vehicleLocation.latitude
                    currentLng = vehicleLocation.longitude
                    currentSpeed = vehicleLocation.speed
                    bearing = vehicleLocation.bearing
                    Logging.d(TAG, "Including location in status: lat=$currentLat, lng=$currentLng, speed=$currentSpeed, bearing=$bearing")
                } else {
                    Logging.d(TAG, "No location data available for status message (sending nulls)")
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to fetch location for status: ${e.message}", e)
            }
        }
        
        val status = CarStatusMessage(
            vehicle_id = carId,
            status = "ONLINE",
            timestamp = System.currentTimeMillis(),
            current_latitude = currentLat,
            current_longitude = currentLng,
            current_speed = currentSpeed,
            foreground = isAppInForeground.get(),
            bearing = bearing
        )
        val payload = json.encodeToString(status)
        publish("car/$carId/status", payload, QOS, true) // Retained message
    }

    /**
     * Publish car offline status
     */
    private fun publishOfflineStatus() {
        val status = CarStatusMessage(
            vehicle_id = carId,
            status = "OFFLINE",
            timestamp = System.currentTimeMillis(),
            foreground = isAppInForeground.get(),
            bearing = null
        )
        val payload = json.encodeToString(status)
        publish("car/$carId/status", payload, QOS, true) // Retained message
    }

    /**
     * Send heartbeat/pong response
     */
    fun sendPong(pingTime: Long): CompletableFuture<Mqtt5PublishResult> {
        val pong = PongMessage(
            car_id = carId,
            ping_time = pingTime,
            pong_time = System.currentTimeMillis(),
            response = "pong"
        )
        val payload = json.encodeToString(pong)
        return publish("car/$carId/pong", payload)
    }

    /**
     * Send heartbeat response in backend format
     */
    fun sendHeartbeatResponse(
        pingTime: Long,
        additionalData: Map<String, Any> = emptyMap()
    ): CompletableFuture<Mqtt5PublishResult> {
        val response = HeartbeatResponseMessage(
            vehicle_id = carId,
            ping_time = pingTime,
            response_time = System.currentTimeMillis(),
            status = "active"
        )
        
        val payload = json.encodeToString(response)
        return publish("car/$carId/heartbeat", payload)
    }

    /**
     * Send trip status update
     */
    fun sendTripStatusUpdate(
        tripId: String,
        status: String,
        location: Location? = null
    ): CompletableFuture<Mqtt5PublishResult> {
        val update = TripStatusUpdateMessage(
            trip_id = tripId,
            car_id = carId,
            status = status,
            timestamp = System.currentTimeMillis(),
            current_latitude = location?.latitude,
            current_longitude = location?.longitude
        )

        val payload = json.encodeToString(update)
        return publish("trip/$tripId/status", payload)
    }

    /**
     * Send trip status update in backend format
     */
    fun sendTripStatusUpdateBackend(
        tripId: String,
        status: String,
        location: Location? = null
    ): CompletableFuture<Mqtt5PublishResult> {
        val update = BackendTripStatusUpdateMessage(
            trip_id = tripId,
            vehicle_id = carId,
            status = status,
            timestamp = System.currentTimeMillis(),
            current_latitude = location?.latitude ?: 0.0,
            current_longitude = location?.longitude ?: 0.0
        )
        val payload = json.encodeToString(update)
        return publish("trip/$tripId/status", payload)
    }

    /**
     * Send trip event message to centralized car topic
     */
    fun sendTripEventMessage(
        event: String,
        tripData: TripData
    ): CompletableFuture<Mqtt5PublishResult> {
        val tripEvent = TripEventMessage(
            event = event,
            data = tripData
        )
        val payload = json.encodeToString(tripEvent)
        val topic = "car/$carId/trip/updates"
        return publish(topic, payload)
    }

    /**
     * Convert TripResponse to TripData for MQTT messaging
     */
    fun convertTripResponseToTripData(
        tripResponse: com.gocavgo.validator.dataclass.TripResponse,
        currentSpeed: Double? = null,
        currentLocation: Location? = null,
        speedAccuracy: Double? = null,
        remainingTimeToDestination: Long? = null,
        remainingDistanceToDestination: Double? = null,
        nextWaypointData: Pair<Long?, Double?>? = null
    ): TripData {
        Logging.d(TAG, "=== CONVERTING TRIP RESPONSE TO TRIP DATA ===")
        Logging.d(TAG, "Trip ID: ${tripResponse.id}")
        // If live-calculated remaining time/distance are not provided, fall back to any values stored on the trip/waypoints
        val (finalRemainingTime, finalRemainingDistance) = run {
            Logging.d(TAG, "--- FALLBACK LOGIC FOR REMAINING TIME/DISTANCE ---")
            Logging.d(TAG, "Live remaining time: ${remainingTimeToDestination?.let { formatDuration(it) } ?: "null"}")
            Logging.d(TAG, "Live remaining distance: ${remainingDistanceToDestination?.let { String.format("%.1f", it) } ?: "null"}m")
            
            val nextOrFirstUnpassed = tripResponse.waypoints.firstOrNull { it.is_next }
                ?: tripResponse.waypoints.firstOrNull { !it.is_passed }
            
            Logging.d(TAG, "Next/first unpassed waypoint: ${nextOrFirstUnpassed?.location?.google_place_name ?: "null"}")
            Logging.d(TAG, "Waypoint remaining_time: ${nextOrFirstUnpassed?.remaining_time?.let { formatDuration(it) } ?: "null"}")
            Logging.d(TAG, "Waypoint remaining_distance: ${nextOrFirstUnpassed?.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
            
            // Log all waypoints for debugging
            Logging.d(TAG, "All waypoints in trip:")
            tripResponse.waypoints.forEachIndexed { index, waypoint ->
                Logging.d(TAG, "  [$index] ID:${waypoint.id}, Name:${waypoint.location.google_place_name}, is_next:${waypoint.is_next}, is_passed:${waypoint.is_passed}, remaining_time:${waypoint.remaining_time?.let { formatDuration(it) } ?: "null"}, remaining_distance:${waypoint.remaining_distance?.let { String.format("%.1f", it) } ?: "null"}m")
            }

            val time = remainingTimeToDestination
                ?: tripResponse.remaining_time_to_destination
                ?: nextOrFirstUnpassed?.remaining_time

            val dist = remainingDistanceToDestination
                ?: tripResponse.remaining_distance_to_destination
                ?: nextOrFirstUnpassed?.remaining_distance

            Logging.d(TAG, "Final fallback result: time=${time?.let { formatDuration(it) } ?: "null"}, distance=${dist?.let { String.format("%.1f", it) } ?: "null"}m")
            Logging.d(TAG, "-----------------------------------------------")

            Pair(time, dist)
        }

        Logging.d(TAG, "Remaining time to destination: ${finalRemainingTime?.let { formatDuration(it) } ?: "null"}")
        Logging.d(TAG, "Remaining distance to destination: ${finalRemainingDistance?.let { String.format("%.1f", it) } ?: "null"}m")
        Logging.d(TAG, "Current speed: ${currentSpeed?.let { String.format("%.2f", it) } ?: "null"} m/s")
        Logging.d(TAG, "Current location: ${currentLocation?.let { "${it.latitude}, ${it.longitude}" } ?: "null"}")
        Logging.d(TAG, "=============================================")
        return TripData(
            id = tripResponse.id,
            route_id = tripResponse.route_id,
            vehicle_id = tripResponse.vehicle_id,
            vehicle = VehicleData(
                id = tripResponse.vehicle.id,
                company_id = tripResponse.vehicle.company_id,
                company_name = tripResponse.vehicle.company_name,
                capacity = tripResponse.vehicle.capacity,
                license_plate = tripResponse.vehicle.license_plate,
                driver = tripResponse.vehicle.driver?.let { driver ->
                    DriverData(
                        name = driver.name,
                        phone = driver.phone
                    )
                }
            ),
            status = tripResponse.status,
            departure_time = tripResponse.departure_time,
            completion_time = tripResponse.completion_timestamp, // Use actual completion timestamp
            connection_mode = tripResponse.connection_mode,
            notes = tripResponse.notes,
            seats = tripResponse.seats,
            remaining_time_to_destination = finalRemainingTime,
            remaining_distance_to_destination = finalRemainingDistance,
            is_reversed = tripResponse.is_reversed,
            current_speed = currentSpeed,
            current_latitude = currentLocation?.latitude,
            current_longitude = currentLocation?.longitude,
            has_custom_waypoints = tripResponse.has_custom_waypoints,
            created_at = tripResponse.created_at,
            updated_at = tripResponse.updated_at,
            route = RouteData(
                id = tripResponse.route.id,
                origin = LocationData(
                    id = tripResponse.route.origin.id,
                    latitude = tripResponse.route.origin.latitude,
                    longitude = tripResponse.route.origin.longitude,
                    code = tripResponse.route.origin.code,
                    google_place_name = tripResponse.route.origin.google_place_name,
                    custom_name = tripResponse.route.origin.custom_name,
                    place_id = tripResponse.route.origin.place_id,
                    created_at = tripResponse.route.origin.created_at,
                    updated_at = tripResponse.route.origin.updated_at
                ),
                destination = LocationData(
                    id = tripResponse.route.destination.id,
                    latitude = tripResponse.route.destination.latitude,
                    longitude = tripResponse.route.destination.longitude,
                    code = tripResponse.route.destination.code,
                    google_place_name = tripResponse.route.destination.google_place_name,
                    custom_name = tripResponse.route.destination.custom_name,
                    place_id = tripResponse.route.destination.place_id,
                    created_at = tripResponse.route.destination.created_at,
                    updated_at = tripResponse.route.destination.updated_at
                )
            ),
            waypoints = tripResponse.waypoints.map { waypoint ->
                // Use fresh data for the next waypoint, otherwise use stored data
                val (freshTime, freshDistance) = if (waypoint.is_next && nextWaypointData != null) {
                    Logging.d(TAG, "Using fresh data for next waypoint: ${waypoint.location.google_place_name}")
                    nextWaypointData
                } else {
                    Logging.d(TAG, "Using stored data for waypoint: ${waypoint.location.google_place_name}")
                    Pair(waypoint.remaining_time, waypoint.remaining_distance)
                }
                
                WaypointData(
                    id = waypoint.id,
                    trip_id = waypoint.trip_id,
                    location_id = waypoint.location_id,
                    order = waypoint.order,
                    price = waypoint.price,
                    is_passed = waypoint.is_passed,
                    is_next = waypoint.is_next,
                    passed_timestamp = waypoint.passed_timestamp, // Use actual passed timestamp
                    remaining_time = freshTime,
                    remaining_distance = freshDistance,
                    waypoint_length_meters = waypoint.waypoint_length_meters, // Original length from route sections
                    waypoint_time_seconds = waypoint.waypoint_time_seconds, // Original time from route sections
                    is_custom = waypoint.is_custom,
                    created_at = null, // Not available in TripResponse
                    updated_at = null, // Not available in TripResponse
                    location = LocationData(
                        id = waypoint.location.id,
                        latitude = waypoint.location.latitude,
                        longitude = waypoint.location.longitude,
                        code = waypoint.location.code,
                        google_place_name = waypoint.location.google_place_name,
                        custom_name = waypoint.location.custom_name,
                        place_id = waypoint.location.place_id,
                        created_at = waypoint.location.created_at,
                        updated_at = waypoint.location.updated_at
                    )
                )
            }
        )
    }

    /**
     * Send trip assignment confirmation to backend
     */
    fun confirmTripAssignment(
        tripId: String,
        accepted: Boolean,
        reason: String? = null
    ): CompletableFuture<Mqtt5PublishResult> {
        val confirmation = TripAssignmentConfirmation(
            trip_id = tripId,
            vehicle_id = carId,
            accepted = accepted,
            timestamp = System.currentTimeMillis(),
            reason = reason
        )
        
        val payload = json.encodeToString(confirmation)
        return publish("trip/$tripId/assignment_response", payload)
    }

    /**
     * Send waypoint reached notification
     */
    fun notifyWaypointReached(
        tripId: String,
        waypointId: Int,
        location: Location
    ): CompletableFuture<Mqtt5PublishResult> {
        val notification = WaypointReachedNotification(
            trip_id = tripId,
            vehicle_id = carId,
            waypoint_id = waypointId,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis()
        )
        val payload = json.encodeToString(notification)
        return publish("trip/$tripId/waypoint_reached", payload)
    }

    /**
     * Send booking confirmation
     */
    fun confirmBooking(
        tripId: String,
        bookingId: String,
        action: String
    ): CompletableFuture<Mqtt5PublishResult> {
        val confirmation = BookingConfirmationMessage(
            trip_id = tripId,
            booking_id = bookingId,
            car_id = carId,
            action = action,
            timestamp = System.currentTimeMillis()
        )
        val payload = json.encodeToString(confirmation)
        return publish("trip/$tripId/booking_confirm", payload)
    }

    /**
     * Handle incoming messages
     */
    private fun handleMessage(topic: String, payload: String) {
        try {
            when {
                topic.startsWith("car/$carId/trip") -> {
                    // Check if it's a simple trip assignment or full trip event
                    if (payload.contains("\"event\"")) {
                        handleTripEventMessage(payload)
                    } else {
                        handleTripMessage(payload)
                    }
                }
                topic.startsWith("car/$carId/ping") -> handlePingMessage(payload)
                topic == "car/$carId/settings" -> handleSettingsMessage(payload)
                topic.contains("/booking_bundle") -> handleBookingBundleMessage(topic, payload)
                topic.contains("/booking") -> handleBookingMessage(topic, payload)
                topic.contains("/bookings") -> handleBookingUpdateMessage(topic, payload)
            }

            // Notify registered listeners
            messageListeners[topic]?.invoke(topic, payload)

        } catch (e: Exception) {
            Logging.e(TAG, "Error handling message for topic: $topic", e)
        }
    }

    /**
     * Serializable DTO for publishing and receiving full booking bundles
     */
    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class BookingBundle(
        val trip_id: String,
        val booking: TripBooking,
        val payment: Payment,
        val tickets: List<Ticket>
    )

    /**
     * Publish a complete booking bundle to trip-specific topic
     */
    fun publishBookingBundle(tripId: String, booking: BookingEntity, payment: PaymentEntity, tickets: List<TicketEntity>) {
        try {
            val bundle = BookingBundle(
                trip_id = tripId,
                booking = booking.toTripBooking(),
                payment = payment.toPayment(),
                tickets = tickets.map { it.toTicket() }
            )
            val payload = json.encodeToString(bundle)
            val topic = "trip/$tripId/booking_bundle/inbound"
            publish(topic, payload)
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to publish booking bundle", e)
        }
    }

    /**
     * Handle incoming full booking bundles and persist to local database
     */
    private fun handleBookingBundleMessage(topic: String, payload: String) {
        try {
            val bundle = json.decodeFromString<BookingBundle>(payload)
            Logging.i(TAG, "Booking bundle received for trip: ${bundle.trip_id}")

            // Persist in background with proper conflict handling
            ioScope.launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val bookingDao = db.bookingDao()
                    val paymentDao = db.paymentDao()
                    val ticketDao = db.ticketDao()

                    val bookingEntity = BookingEntity.fromTripBooking(bundle.booking)
                    val paymentEntity = PaymentEntity.fromPayment(bundle.payment)
                    val ticketEntities = bundle.tickets.map { TicketEntity.fromTicket(it) }

                    // Handle booking with conflict detection
                    val bookingResult = handleBookingConflict(bookingDao, bookingEntity)
                    Logging.d(TAG, "Booking processing result: $bookingResult")

                    // Handle payment with conflict detection
                    val paymentResult = handlePaymentConflict(paymentDao, paymentEntity)
                    Logging.d(TAG, "Payment processing result: $paymentResult")

                    // Handle tickets with conflict detection
                    val ticketResults = handleTicketConflicts(ticketDao, ticketEntities)
                    Logging.d(TAG, "Ticket processing results: $ticketResults")

                    Logging.d(TAG, "Booking bundle processed: booking=${bookingEntity.id}, payment=${paymentEntity.id}, tickets=${ticketEntities.size}")

                    // Notify UI layer that a booking bundle was saved
                    try {
                        val passengerName = bookingEntity.user_name ?: "Passenger"
                        val pickup = ticketEntities.firstOrNull()?.pickup_location_name
                            ?: bookingEntity.pickup_location_id ?: "Unknown pickup"
                        val dropoff = ticketEntities.firstOrNull()?.dropoff_location_name
                            ?: bookingEntity.dropoff_location_id ?: "Unknown dropoff"
                        val numTickets = ticketEntities.size
                        val isPaid = paymentEntity.status == com.gocavgo.validator.dataclass.PaymentStatus.COMPLETED

                        Logging.d(TAG, "Broadcasting booking bundle saved event: trip=${bundle.trip_id}, passenger=$passengerName, pickup=$pickup, dropoff=$dropoff, tickets=$numTickets, paid=$isPaid")
                        
                        // Show notification if app is not in foreground
                        if (!isAppInForeground.get()) {
                            notificationManager?.showBookingUpdateNotification(
                                bundle.trip_id, passengerName, pickup, dropoff, numTickets, isPaid
                            )
                        }
                        
                        // Send broadcast (for any activity listening)
                        val intent = Intent(ACTION_BOOKING_BUNDLE_SAVED).apply {
                            putExtra("trip_id", bundle.trip_id)
                            putExtra("passenger_name", passengerName)
                            putExtra("pickup", pickup)
                            putExtra("dropoff", dropoff)
                            putExtra("num_tickets", numTickets)
                            putExtra("is_paid", isPaid)
                        }
                        context.sendBroadcast(intent)
                        Logging.d(TAG, "Booking bundle broadcast sent successfully")
                        
                        // Also call direct callback if available (more reliable)
                        try {
                            bookingBundleCallback?.invoke(bundle.trip_id, passengerName, pickup, dropoff, numTickets, isPaid)
                            Logging.d(TAG, "Booking bundle callback invoked successfully")
                        } catch (callbackError: Exception) {
                            Logging.w(TAG, "Error invoking booking bundle callback: ${callbackError.message}")
                        }
                        
                    } catch (e: Exception) {
                        Logging.e(TAG, "Failed to broadcast booking bundle saved event: ${e.message}", e)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to persist booking bundle", e)
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing booking bundle message", e)
        }
    }

    /**
     * Handle booking conflict resolution
     */
    private suspend fun handleBookingConflict(bookingDao: com.gocavgo.validator.database.BookingDao, newBooking: com.gocavgo.validator.database.BookingEntity): String {
        return try {
            val existingBooking = bookingDao.getBookingById(newBooking.id)
            
            if (existingBooking == null) {
                // New booking - insert it
                bookingDao.insertBooking(newBooking)
                "INSERTED_NEW"
            } else {
                // Existing booking - check if update is needed
                val shouldUpdate = shouldUpdateBooking(existingBooking, newBooking)
                
                if (shouldUpdate) {
                    bookingDao.updateBooking(newBooking)
                    "UPDATED_EXISTING"
                } else {
                    "SKIPPED_NO_CHANGES"
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling booking conflict: ${e.message}", e)
            "ERROR: ${e.message}"
        }
    }
    
    /**
     * Handle payment conflict resolution
     */
    private suspend fun handlePaymentConflict(paymentDao: com.gocavgo.validator.database.PaymentDao, newPayment: com.gocavgo.validator.database.PaymentEntity): String {
        return try {
            val existingPayment = paymentDao.getPaymentById(newPayment.id)
            
            if (existingPayment == null) {
                // New payment - insert it
                paymentDao.insertPayment(newPayment)
                "INSERTED_NEW"
            } else {
                // Existing payment - check if update is needed
                val shouldUpdate = shouldUpdatePayment(existingPayment, newPayment)
                
                if (shouldUpdate) {
                    paymentDao.updatePayment(newPayment)
                    "UPDATED_EXISTING"
                } else {
                    "SKIPPED_NO_CHANGES"
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling payment conflict: ${e.message}", e)
            "ERROR: ${e.message}"
        }
    }
    
    /**
     * Handle ticket conflicts resolution
     */
    private suspend fun handleTicketConflicts(ticketDao: com.gocavgo.validator.database.TicketDao, newTickets: List<com.gocavgo.validator.database.TicketEntity>): Map<String, String> {
        val results = mutableMapOf<String, String>()
        
        for (newTicket in newTickets) {
            try {
                val existingTicket = ticketDao.getTicketById(newTicket.id)
                
                if (existingTicket == null) {
                    // New ticket - insert it
                    ticketDao.insertTicket(newTicket)
                    results[newTicket.id] = "INSERTED_NEW"
                } else {
                    // Existing ticket - check if update is needed
                    val shouldUpdate = shouldUpdateTicket(existingTicket, newTicket)
                    
                    if (shouldUpdate) {
                        ticketDao.updateTicket(newTicket)
                        results[newTicket.id] = "UPDATED_EXISTING"
                    } else {
                        results[newTicket.id] = "SKIPPED_NO_CHANGES"
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error handling ticket conflict for ${newTicket.id}: ${e.message}", e)
                results[newTicket.id] = "ERROR: ${e.message}"
            }
        }
        
        return results
    }
    
    /**
     * Determine if booking should be updated based on business logic
     */
    private fun shouldUpdateBooking(existing: com.gocavgo.validator.database.BookingEntity, incoming: com.gocavgo.validator.database.BookingEntity): Boolean {
        // Always update if timestamps are different (incoming is newer)
        if (incoming.updated_at > existing.updated_at) {
            Logging.d(TAG, "Booking ${existing.id} has newer timestamp, updating")
            return true
        }
        
        // Don't update if incoming is older
        if (incoming.updated_at < existing.updated_at) {
            Logging.d(TAG, "Booking ${existing.id} incoming data is older, skipping update")
            return false
        }
        
        // If timestamps are equal, check for meaningful changes
        val hasChanges = existing.status != incoming.status ||
                        existing.total_amount != incoming.total_amount ||
                        existing.pickup_location_id != incoming.pickup_location_id ||
                        existing.dropoff_location_id != incoming.dropoff_location_id
        
        if (hasChanges) {
            Logging.d(TAG, "Booking ${existing.id} has meaningful changes, updating")
            return true
        }
        
        Logging.d(TAG, "Booking ${existing.id} no changes detected, skipping update")
        return false
    }
    
    /**
     * Determine if payment should be updated based on business logic
     */
    private fun shouldUpdatePayment(existing: com.gocavgo.validator.database.PaymentEntity, incoming: com.gocavgo.validator.database.PaymentEntity): Boolean {
        // Always update if timestamps are different (incoming is newer)
        if (incoming.updated_at > existing.updated_at) {
            Logging.d(TAG, "Payment ${existing.id} has newer timestamp, updating")
            return true
        }
        
        // Don't update if incoming is older
        if (incoming.updated_at < existing.updated_at) {
            Logging.d(TAG, "Payment ${existing.id} incoming data is older, skipping update")
            return false
        }
        
        // If timestamps are equal, check for meaningful changes
        val hasChanges = existing.status != incoming.status ||
                        existing.amount != incoming.amount ||
                        existing.payment_method != incoming.payment_method ||
                        existing.payment_data != incoming.payment_data
        
        if (hasChanges) {
            Logging.d(TAG, "Payment ${existing.id} has meaningful changes, updating")
            return true
        }
        
        Logging.d(TAG, "Payment ${existing.id} no changes detected, skipping update")
        return false
    }
    
    /**
     * Determine if ticket should be updated based on business logic
     */
    private fun shouldUpdateTicket(existing: com.gocavgo.validator.database.TicketEntity, incoming: com.gocavgo.validator.database.TicketEntity): Boolean {
        // Always update if timestamps are different (incoming is newer)
        if (incoming.updated_at > existing.updated_at) {
            Logging.d(TAG, "Ticket ${existing.id} has newer timestamp, updating")
            return true
        }
        
        // Don't update if incoming is older
        if (incoming.updated_at < existing.updated_at) {
            Logging.d(TAG, "Ticket ${existing.id} incoming data is older, skipping update")
            return false
        }
        
        // If timestamps are equal, check for meaningful changes
        val hasChanges = existing.is_used != incoming.is_used ||
                        existing.used_at != incoming.used_at ||
                        existing.validated_by != incoming.validated_by ||
                        existing.pickup_location_name != incoming.pickup_location_name ||
                        existing.dropoff_location_name != incoming.dropoff_location_name ||
                        existing.car_plate != incoming.car_plate ||
                        existing.car_company != incoming.car_company
        
        if (hasChanges) {
            Logging.d(TAG, "Ticket ${existing.id} has meaningful changes, updating")
            return true
        }
        
        Logging.d(TAG, "Ticket ${existing.id} no changes detected, skipping update")
        return false
    }

    /**
     * Handle trip event messages (full trip data)
     */
    private fun handleTripEventMessage(payload: String) {
        try {
            val tripEvent = json.decodeFromString<TripEventMessage>(payload)
            Logging.i(TAG, "Trip event received: ${tripEvent.event}")
            Logging.d(TAG, "Trip data: ${tripEvent.data}")

            // Convert backend trip data to Android trip response format if needed
            val tripResponse = convertBackendTripToAndroid(tripEvent.data)
            Logging.d(TAG, "Converted trip response: $tripResponse")

            // Save trip to database directly in background (like booking bundles)
            ioScope.launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val tripDao = db.tripDao()
                    
                    val tripEntity = TripEntity.fromTripResponse(tripResponse)
                    
                    // Use insertTrip to handle both new and existing trips (REPLACE on conflict)
                    tripDao.insertTrip(tripEntity)
                    
                    Logging.d(TAG, "Trip saved to database successfully: ID=${tripResponse.id}, status=${tripResponse.status}")
                    Logging.d(TAG, "Route: ${tripResponse.route.origin.google_place_name} → ${tripResponse.route.destination.google_place_name}")
                    
                    // Record MQTT update timestamp for sync coordination
                    SyncCoordinator.recordMqttUpdate(context)
                    
                    // Show notification if app is not in foreground
                    if (!isAppInForeground.get()) {
                        notificationManager?.showTripUpdateNotification(tripEvent)
                    }

                    // Route trip event to appropriate callback (Activity or Service)
                    routeTripEvent(tripEvent)
                    
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to save trip to database: ${e.message}", e)
                    
                    // Still try to route event even if database save failed
                    routeTripEvent(tripEvent)
                }
            }

        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing trip event message", e)
        }
    }

    /**
     * Convert backend trip data to Android trip response format
     */
    private fun convertBackendTripToAndroid(backendTrip: TripData): com.gocavgo.validator.dataclass.TripResponse {
        return com.gocavgo.validator.dataclass.TripResponse(
            id = backendTrip.id,
            route_id = backendTrip.route_id,
            vehicle_id = backendTrip.vehicle_id,
            vehicle = com.gocavgo.validator.dataclass.VehicleInfo(
                id = backendTrip.vehicle.id,
                company_id = backendTrip.vehicle.company_id,
                company_name = backendTrip.vehicle.company_name,
                capacity = backendTrip.vehicle.capacity,
                license_plate = backendTrip.vehicle.license_plate,
                driver = backendTrip.vehicle.driver?.let { driver ->
                    com.gocavgo.validator.dataclass.DriverInfo(
                        name = driver.name,
                        phone = driver.phone
                    )
                }
            ),
            status = backendTrip.status,
            departure_time = backendTrip.departure_time,
            connection_mode = backendTrip.connection_mode,
            notes = backendTrip.notes,
            seats = backendTrip.seats,
            remaining_time_to_destination = backendTrip.remaining_time_to_destination,
            remaining_distance_to_destination = backendTrip.remaining_distance_to_destination,
            is_reversed = backendTrip.is_reversed,
            has_custom_waypoints = backendTrip.has_custom_waypoints,
            created_at = backendTrip.created_at,
            updated_at = backendTrip.updated_at,
            completion_timestamp = backendTrip.completion_time, // Use completion timestamp from backend
            route = com.gocavgo.validator.dataclass.TripRoute(
                id = backendTrip.route.id,
                origin = com.gocavgo.validator.dataclass.SavePlaceResponse(
                    id = backendTrip.route.origin.id,
                    latitude = backendTrip.route.origin.latitude,
                    longitude = backendTrip.route.origin.longitude,
                    code = backendTrip.route.origin.code,
                    google_place_name = backendTrip.route.origin.google_place_name,
                    custom_name = backendTrip.route.origin.custom_name,
                    province = "", // Backend doesn't have this field
                    district = "", // Backend doesn't have this field
                    place_id = backendTrip.route.origin.place_id,
                    created_at = backendTrip.route.origin.created_at ?: "",
                    updated_at = backendTrip.route.origin.updated_at ?: ""
                ),
                destination = com.gocavgo.validator.dataclass.SavePlaceResponse(
                    id = backendTrip.route.destination.id,
                    latitude = backendTrip.route.destination.latitude,
                    longitude = backendTrip.route.destination.longitude,
                    code = backendTrip.route.destination.code,
                    google_place_name = backendTrip.route.destination.google_place_name,
                    custom_name = backendTrip.route.destination.custom_name,
                    province = "", // Backend doesn't have this field
                    district = "", // Backend doesn't have this field
                    place_id = backendTrip.route.destination.place_id,
                    created_at = backendTrip.route.destination.created_at ?: "",
                    updated_at = backendTrip.route.destination.updated_at ?: ""
                )
            ),
            waypoints = backendTrip.waypoints.map { waypoint ->
                com.gocavgo.validator.dataclass.TripWaypoint(
                    id = waypoint.id,
                    trip_id = waypoint.trip_id,
                    location_id = waypoint.location_id,
                    order = waypoint.order,
                    price = waypoint.price,
                    is_passed = waypoint.is_passed,
                    is_next = waypoint.is_next,
                    is_custom = waypoint.is_custom,
                    remaining_time = waypoint.remaining_time,
                    remaining_distance = waypoint.remaining_distance,
                    waypoint_length_meters = waypoint.waypoint_length_meters, // Use original length from backend
                    waypoint_time_seconds = waypoint.waypoint_time_seconds, // Use original time from backend
                    passed_timestamp = waypoint.passed_timestamp, // Use passed timestamp from backend
                    location = com.gocavgo.validator.dataclass.SavePlaceResponse(
                        id = waypoint.location.id,
                        latitude = waypoint.location.latitude,
                        longitude = waypoint.location.longitude,
                        code = waypoint.location.code,
                        google_place_name = waypoint.location.google_place_name,
                        custom_name = waypoint.location.custom_name,
                        province = "", // Backend doesn't have this field
                        district = "", // Backend doesn't have this field
                        place_id = waypoint.location.place_id,
                        created_at = waypoint.location.created_at ?: "",
                        updated_at = waypoint.location.updated_at ?: ""
                    )
                )
            }
        )
    }

    /**
     * Handle trip assignment messages (simple format from backend)
     */
    private fun handleTripMessage(payload: String) {
        try {
            val tripData = json.decodeFromString<TripAssignmentMessage>(payload)
            Logging.i(TAG, "Trip assigned: ${tripData.trip_id} from ${tripData.start_location} to ${tripData.end_location}")

            // Handle trip assignment logic here
            // You can add callbacks or listeners for trip events

        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing trip message", e)
        }
    }

    /**
     * Handle ping messages
     */
    private fun handlePingMessage(payload: String) {
        try {
            val pingData = json.decodeFromString<PingMessage>(payload)
            Logging.d(TAG, "Ping received, sending pong response")
            sendPong(pingData.ping_time)

        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing ping message", e)
        }
    }

    /**
     * Handle vehicle settings messages from MQTT
     */
    private fun handleSettingsMessage(payload: String) {
        try {
            Logging.d(TAG, "=== SETTINGS MESSAGE RECEIVED ===")
            Logging.d(TAG, "Payload: $payload")
            
            // Parse MQTT payload format (may include licensePlate)
            val mqttPayload = json.decodeFromString<VehicleSettingsMqttPayload>(payload)
            
            // Convert to VehicleSettings format (using vehicleId from carId)
            val vehicleId = carId.toIntOrNull()
            if (vehicleId == null) {
                Logging.e(TAG, "Invalid vehicle ID from carId: $carId")
                return
            }
            
            val settings = VehicleSettings(
                id = 1, // MQTT doesn't provide id, use default
                vehicleId = vehicleId,
                logout = mqttPayload.logout,
                devmode = mqttPayload.devmode,
                deactivate = mqttPayload.deactivate,
                appmode = mqttPayload.appmode,
                simulate = mqttPayload.simulate
            )
            
            Logging.d(TAG, "Parsed settings: logout=${settings.logout}, devmode=${settings.devmode}, deactivate=${settings.deactivate}, simulate=${settings.simulate}")
            
            // Save settings to database in background
            ioScope.launch {
                try {
                    val settingsManager = com.gocavgo.validator.security.VehicleSettingsManager.getInstance(context)
                    settingsManager.saveSettings(settings)
                    Logging.d(TAG, "Settings saved to database from MQTT")
                    
                    // Apply settings (check logout/deactivate)
                    settingsManager.applySettings(context, settings)
                    Logging.d(TAG, "Settings applied successfully")
                } catch (e: Exception) {
                    Logging.e(TAG, "Error saving settings from MQTT: ${e.message}", e)
                }
            }
            
        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing settings message: ${e.message}", e)
        }
    }

    /**
     * Handle booking event messages
     */
    private fun handleBookingMessage(topic: String, payload: String) {
        try {
            val bookingData = json.decodeFromString<BookingEventMessage>(payload)
            val tripId = extractTripIdFromTopic(topic)

            Logging.i(TAG, "Booking event received for trip: $tripId")
            Logging.d(TAG, "Booking data: $bookingData")

            // Handle booking event logic here

        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing booking message", e)
        }
    }

    /**
     * Handle booking update messages
     */
    private fun handleBookingUpdateMessage(topic: String, payload: String) {
        try {
            val updateData = json.decodeFromString<BookingUpdateMessage>(payload)
            val tripId = extractTripIdFromTopic(topic)
            val action = updateData.booking.action

            Logging.i(TAG, "Booking update received for trip: $tripId, action: $action")

            // Handle booking update logic here

        } catch (e: Exception) {
            Logging.e(TAG, "Error parsing booking update message", e)
        }
    }

    /**
     * Extract trip ID from topic
     */
    private fun extractTripIdFromTopic(topic: String): String? {
        val parts = topic.split("/")
        return if (parts.size >= 2 && parts[0] == "trip") parts[1] else null
    }

    /**
     * Add message listener for specific topic
     */
    fun addMessageListener(topic: String, listener: (String, String) -> Unit) {
        messageListeners[topic] = listener
    }

    /**
     * Remove message listener
     */
    fun removeMessageListener(topic: String) {
        messageListeners.remove(topic)
    }

    /**
     * Set connection callback
     */
    fun setConnectionCallback(callback: (Boolean) -> Unit) {
        connectionCallback = callback
    }
    
    /**
     * Set booking bundle callback for direct UI notification
     */
    fun setBookingBundleCallback(callback: ((String, String, String, String, Int, Boolean) -> Unit)?) {
        bookingBundleCallback = callback
    }
    
    /**
     * Route trip event to appropriate callback (Activity if active, Service if inactive)
     */
    private fun routeTripEvent(tripEvent: com.gocavgo.validator.dataclass.TripEventMessage) {
        try {
            // Check if Activity is active
            val isActivityActive = try {
                com.gocavgo.validator.navigator.AutoModeHeadlessActivity.isActive()
            } catch (e: Exception) {
                false // If Activity not available, assume inactive
            }
            
            if (isActivityActive && activityTripEventCallback != null) {
                // Activity is active - route to Activity callback
                Logging.d(TAG, "Routing trip event to Activity callback (Activity is active)")
                try {
                    activityTripEventCallback?.invoke(tripEvent)
                    Logging.d(TAG, "Activity trip event callback invoked successfully")
                } catch (callbackError: Exception) {
                    Logging.e(TAG, "Error invoking Activity trip event callback: ${callbackError.message}", callbackError)
                }
            } else if (!isActivityActive && serviceTripEventCallback != null) {
                // Activity is inactive - route to Service callback
                Logging.d(TAG, "Routing trip event to Service callback (Activity is inactive)")
                try {
                    serviceTripEventCallback?.invoke(tripEvent)
                    Logging.d(TAG, "Service trip event callback invoked successfully")
                } catch (callbackError: Exception) {
                    Logging.e(TAG, "Error invoking Service trip event callback: ${callbackError.message}", callbackError)
                }
            } else {
                // No callback available - log warning but message is already in DB
                Logging.w(TAG, "No trip event callback available (Activity active: $isActivityActive, Activity callback: ${activityTripEventCallback != null}, Service callback: ${serviceTripEventCallback != null})")
                Logging.w(TAG, "Trip event message saved to database but no handler available. Service should poll database.")
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error routing trip event: ${e.message}", e)
        }
    }
    
    /**
     * Set trip event callback for Activity (used when Activity is active)
     */
    fun setTripEventCallback(callback: ((com.gocavgo.validator.dataclass.TripEventMessage) -> Unit)?) {
        activityTripEventCallback = callback
        Logging.d(TAG, "Activity trip event callback ${if (callback != null) "registered" else "unregistered"}")
    }
    
    /**
     * Set trip event callback for Service (used when Activity is inactive)
     */
    fun setServiceTripEventCallback(callback: ((com.gocavgo.validator.dataclass.TripEventMessage) -> Unit)?) {
        serviceTripEventCallback = callback
        Logging.d(TAG, "Service trip event callback ${if (callback != null) "registered" else "unregistered"}")
    }
    
    /**
     * Set notification manager for background notifications
     */
    fun setNotificationManager(manager: MqttNotificationManager?) {
        notificationManager = manager
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = mqttClient?.state == MqttClientState.CONNECTED

    /**
     * Check if waiting for network
     */
    fun isWaitingForNetwork(): Boolean = isWaitingForNetwork.get()

    /**
     * Check if service is active
     */
    fun isServiceActive(): Boolean = isServiceActive.get()

    /**
     * Get client state
     */
    fun getClientState(): MqttClientState? = mqttClient?.state

    /**
     * Format duration in seconds to human-readable format
     */
    private fun formatDuration(seconds: Long): String {
        return try {
            when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> {
                    val hours = seconds / 3600
                    val minutes = (seconds % 3600) / 60
                    "${hours}h ${minutes}m"
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error formatting duration: ${e.message}", e)
            "Unknown"
        }
    }

    /**
     * Data classes for location
     */
    data class Location(
        val latitude: Double,
        val longitude: Double
    )
}