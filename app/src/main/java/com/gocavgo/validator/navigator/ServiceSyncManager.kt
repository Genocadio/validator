package com.gocavgo.validator.navigator

import com.gocavgo.validator.dataclass.TripResponse
import com.gocavgo.validator.database.DatabaseManager
import com.gocavgo.validator.service.AutoModeHeadlessForegroundService
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages synchronization between Activity and Service.
 * Handles state verification from database and cache synchronization.
 */
data class NavigationCache(
    val lastKnownDistance: Double? = null,
    val lastKnownTime: Long? = null,
    val lastKnownWaypointName: String? = null
)

class ServiceSyncManager(
    private val tripStateManager: TripStateManager,
    private val databaseManager: DatabaseManager,
    private val lifecycleScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ServiceSyncManager"
    }

    private var syncJob: Job? = null
    private var cache = NavigationCache()

    // Callbacks
    var onStateUpdate: ((TripResponse?, String) -> Unit)? = null
    var onCacheUpdate: ((NavigationCache) -> Unit)? = null
    var onNavigationTextUpdate: ((String) -> Unit)? = null

    /**
     * Sync Activity state to Service
     */
    fun syncToService(service: AutoModeHeadlessForegroundService?) {
        if (service == null) return
        
        val state = tripStateManager.getState()
        Logging.d(TAG, "Syncing Activity state to Service: trip=${state.currentTrip?.id}, navigating=${state.isNavigating}")
        
        // Sync cache
        if (cache.lastKnownDistance != null) {
            service.syncCacheFromActivity(
                cache.lastKnownDistance,
                cache.lastKnownTime,
                cache.lastKnownWaypointName
            )
        }
    }

    /**
     * Sync state from Service to Activity
     */
    fun syncFromService(
        service: AutoModeHeadlessForegroundService?,
        dbTrip: TripResponse?
    ) {
        if (service == null) return

        Logging.d(TAG, "=== SYNCING STATE FROM SERVICE ===")
        
        val serviceTrip = service.getCurrentTripForSync()
        val serviceIsNavigating = service.isNavigatingForSync()
        val serviceCountdownText = service.getCountdownTextForSync()
        val serviceHasActiveRoute = service.hasActiveRouteForSync()
        
        val currentState = tripStateManager.getState()
        
        Logging.d(TAG, "Database trip: ${dbTrip?.id} (status: ${dbTrip?.status})")
        Logging.d(TAG, "Service trip: ${serviceTrip?.id} (status: ${serviceTrip?.status})")
        Logging.d(TAG, "Activity trip: ${currentState.currentTrip?.id} (status: ${currentState.currentTrip?.status})")
        Logging.d(TAG, "Service navigating: $serviceIsNavigating, Activity navigating: ${currentState.isNavigating}")
        
        // Use database as authoritative source
        val authoritativeTrip = dbTrip ?: serviceTrip
        
        // Update Activity state from authoritative source
        if (authoritativeTrip != null && currentState.currentTrip?.id != authoritativeTrip.id) {
            tripStateManager.updateTrip(authoritativeTrip)
            onStateUpdate?.invoke(authoritativeTrip, serviceCountdownText)
        }
        
        // Sync navigating state
        if (serviceIsNavigating != currentState.isNavigating) {
            tripStateManager.setNavigating(serviceIsNavigating)
        }
        
        // Sync countdown
        if (serviceCountdownText != currentState.countdownText) {
            tripStateManager.updateCountdown(serviceCountdownText)
        }
    }

    /**
     * Verify state from database (authoritative source)
     */
    fun verifyState(dbTrip: TripResponse?) {
        try {
            Logging.d(TAG, "=== VERIFYING STATE FROM DATABASE ===")
            
            val currentState = tripStateManager.getState()
            Logging.d(TAG, "Database trip: ${dbTrip?.id} (status: ${dbTrip?.status})")
            Logging.d(TAG, "Activity trip: ${currentState.currentTrip?.id} (status: ${currentState.currentTrip?.status})")
            Logging.d(TAG, "Activity navigating: ${currentState.isNavigating}")
            
            // CASE 1: Trip cancellation detection
            if (currentState.currentTrip != null && dbTrip == null) {
                Logging.w(TAG, "TRIP CANCELLATION DETECTED: Activity has trip ${currentState.currentTrip.id} but database has none")
                tripStateManager.clearState()
                onStateUpdate?.invoke(null, "")
                return
            }
            
            // CASE 2: New trip detected
            if (dbTrip != null && currentState.currentTrip?.id != dbTrip.id) {
                Logging.d(TAG, "NEW TRIP DETECTED: Database has trip ${dbTrip.id} but Activity has ${currentState.currentTrip?.id}")
                tripStateManager.updateTrip(dbTrip)
                
                if (dbTrip.status.equals("IN_PROGRESS", ignoreCase = true)) {
                    tripStateManager.setNavigating(true)
                }
                onStateUpdate?.invoke(dbTrip, "")
            }
            
            // CASE 3: Trip status changed (SCHEDULED → IN_PROGRESS)
            if (dbTrip != null && currentState.currentTrip?.id == dbTrip.id) {
                val wasScheduled = currentState.currentTrip?.status?.equals("SCHEDULED", ignoreCase = true) == true
                val nowInProgress = dbTrip.status.equals("IN_PROGRESS", ignoreCase = true)
                
                if (wasScheduled && nowInProgress) {
                    Logging.d(TAG, "TRIP STATUS CHANGED: SCHEDULED → IN_PROGRESS")
                    tripStateManager.updateTrip(dbTrip)
                    tripStateManager.setNavigating(true)
                    onStateUpdate?.invoke(dbTrip, "")
                }
            }
            
            // CASE 4: Navigation state mismatch
            if (dbTrip != null && 
                dbTrip.status.equals("IN_PROGRESS", ignoreCase = true) && 
                !currentState.isNavigating) {
                Logging.w(TAG, "NAVIGATION STATE MISMATCH: Database shows IN_PROGRESS but Activity not navigating")
                tripStateManager.updateTrip(dbTrip)
                tripStateManager.setNavigating(true)
            }
            
            // CASE 5: Trip completed
            if (dbTrip != null && dbTrip.status.equals("COMPLETED", ignoreCase = true)) {
                Logging.d(TAG, "TRIP COMPLETED: Database shows trip ${dbTrip.id} is COMPLETED")
                tripStateManager.clearState()
                onStateUpdate?.invoke(null, "")
            }
            
            Logging.d(TAG, "=== END DATABASE VERIFICATION ===")
        } catch (e: Exception) {
            Logging.e(TAG, "Error verifying state from database: ${e.message}", e)
        }
    }

    /**
     * Start periodic sync of navigation state from Service
     */
    fun startSync(
        service: AutoModeHeadlessForegroundService?,
        app: App?,
        onNavigationProgressUpdate: (() -> Unit)? = null
    ) {
        stopSync()
        
        if (service == null) return
        
        Logging.d(TAG, "Starting periodic Service navigation sync")
        
        syncJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1000) // Update every 1 second
                
                try {
                    val serviceIsNavigating = service.isNavigatingForSync()
                    val serviceHasActiveRoute = service.hasActiveRouteForSync()
                    
                    if (!serviceIsNavigating && !serviceHasActiveRoute) {
                        Logging.d(TAG, "Service is no longer navigating - stopping sync")
                        stopSync()
                        break
                    }
                    
                    // Check if Activity has active route
                    val activityHasActiveRoute = try {
                        val route = app?.getNavigationExample()?.getHeadlessNavigator()?.route
                        route != null
                    } catch (e: Exception) {
                        false
                    }
                    
                    if (activityHasActiveRoute) {
                        Logging.d(TAG, "Activity Navigator is active - stopping Service sync")
                        stopSync()
                        onNavigationProgressUpdate?.invoke()
                        break
                    }
                    
                    // Refresh from database
                    val currentState = tripStateManager.getState()
                    val trip = currentState.currentTrip
                    if (trip != null) {
                        val freshTrip = withContext(Dispatchers.IO) {
                            databaseManager.getTripById(trip.id)
                        }
                        if (freshTrip != null) {
                            tripStateManager.updateTrip(freshTrip)
                            onStateUpdate?.invoke(freshTrip, "")
                        }
                    }
                    
                    // Get navigation text from Service
                    val serviceNavigationText = service.getCurrentNavigationTextForSync()
                    if (serviceNavigationText.isNotEmpty()) {
                        onNavigationTextUpdate?.invoke(serviceNavigationText)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error in Service navigation sync: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Stop periodic sync
     */
    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        Logging.d(TAG, "Stopped periodic Service navigation sync")
    }

    /**
     * Update cache
     */
    fun updateCache(distance: Double?, time: Long?, waypointName: String?) {
        cache = NavigationCache(distance, time, waypointName)
        onCacheUpdate?.invoke(cache)
    }

    /**
     * Get cache
     */
    fun getCache(): NavigationCache = cache

    /**
     * Clear cache
     */
    fun clearCache() {
        cache = NavigationCache()
        onCacheUpdate?.invoke(cache)
    }
}
