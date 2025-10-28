package com.gocavgo.validator.sync

import android.content.Context
import android.util.Log

/**
 * Coordinates between MQTT real-time updates and periodic backend polling
 * to avoid redundant API calls when fresh data exists from MQTT
 */
class SyncCoordinator {
    
    companion object {
        private const val TAG = "SyncCoordinator"
        private const val PREFERENCES_NAME = "mqtt_sync_state"
        private const val KEY_LAST_MQTT_TRIP_UPDATE = "last_mqtt_trip_update"
        private const val DEFAULT_FRESHNESS_THRESHOLD_MINUTES = 2
        private const val MILLIS_PER_MINUTE = 60_000L
        
        /**
         * Record that an MQTT update has occurred
         * Call this after saving trip data from MQTT
         */
        fun recordMqttUpdate(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                val timestamp = System.currentTimeMillis()
                prefs.edit()
                    .putLong(KEY_LAST_MQTT_TRIP_UPDATE, timestamp)
                    .apply()
                Log.d(TAG, "Recorded MQTT update timestamp: $timestamp")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record MQTT update: ${e.message}", e)
            }
        }
        
        /**
         * Get the timestamp of the last MQTT update
         * @return Timestamp in milliseconds, or 0 if never updated
         */
        fun getLastMqttUpdateTime(context: Context): Long {
            return try {
                val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                prefs.getLong(KEY_LAST_MQTT_TRIP_UPDATE, 0L)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get last MQTT update time: ${e.message}", e)
                0L
            }
        }
        
        /**
         * Determine if backend fetch is needed based on freshness
         * @param context Application context
         * @param thresholdMinutes Number of minutes to consider data fresh (default: 2)
         * @return true if backend fetch should be performed, false if data is fresh from MQTT
         */
        fun shouldFetchFromBackend(context: Context, thresholdMinutes: Int = DEFAULT_FRESHNESS_THRESHOLD_MINUTES): Boolean {
            try {
                val lastUpdateTime = getLastMqttUpdateTime(context)
                
                // If there's no MQTT update recorded, proceed with backend fetch
                if (lastUpdateTime == 0L) {
                    Log.d(TAG, "No MQTT update recorded, proceeding with backend fetch")
                    return true
                }
                
                val currentTime = System.currentTimeMillis()
                val timeSinceLastUpdate = currentTime - lastUpdateTime
                val thresholdMillis = thresholdMinutes * MILLIS_PER_MINUTE
                
                val isDataStale = timeSinceLastUpdate >= thresholdMillis
                
                if (isDataStale) {
                    Log.d(TAG, "Data is stale (${timeSinceLastUpdate / MILLIS_PER_MINUTE} minutes old), proceeding with backend fetch")
                    return true
                } else {
                    val minutesOld = timeSinceLastUpdate / MILLIS_PER_MINUTE
                    val secondsOld = (timeSinceLastUpdate % MILLIS_PER_MINUTE) / 1000
                    Log.d(TAG, "Data is fresh from MQTT (${minutesOld}m ${secondsOld}s ago), skipping backend fetch")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking data freshness: ${e.message}", e)
                // On error, proceed with backend fetch for safety
                return true
            }
        }
        
        /**
         * Check if data was recently updated via MQTT
         * @param context Application context
         * @param thresholdMinutes Number of minutes to consider data "recent" (default: 2)
         * @return true if MQTT update occurred within threshold, false otherwise
         */
        fun isDataFresh(context: Context, thresholdMinutes: Int = DEFAULT_FRESHNESS_THRESHOLD_MINUTES): Boolean {
            val lastUpdateTime = getLastMqttUpdateTime(context)
            if (lastUpdateTime == 0L) return false
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastUpdate = currentTime - lastUpdateTime
            val thresholdMillis = thresholdMinutes * MILLIS_PER_MINUTE
            
            return timeSinceLastUpdate < thresholdMillis
        }
        
        /**
         * Clear the MQTT sync state (for testing or reset scenarios)
         */
        fun clearSyncState(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove(KEY_LAST_MQTT_TRIP_UPDATE)
                    .apply()
                Log.d(TAG, "Cleared MQTT sync state")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear sync state: ${e.message}", e)
            }
        }
        
        /**
         * Get detailed sync status information for debugging
         */
        fun getSyncStatus(context: Context): String {
            val lastUpdateTime = getLastMqttUpdateTime(context)
            val currentTime = System.currentTimeMillis()
            
            return if (lastUpdateTime == 0L) {
                "No MQTT updates recorded"
            } else {
                val timeSinceUpdate = currentTime - lastUpdateTime
                val minutes = timeSinceUpdate / MILLIS_PER_MINUTE
                val seconds = (timeSinceUpdate % MILLIS_PER_MINUTE) / 1000
                "Last MQTT update: ${minutes}m ${seconds}s ago"
            }
        }
    }
}

