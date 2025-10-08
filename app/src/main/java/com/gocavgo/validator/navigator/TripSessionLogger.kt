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
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages file logging for trip sessions with specific timing requirements.
 * 
 * Logging frequency:
 * - Trip start (after validation)
 * - First progress update
 * - Waypoint mark + 2 seconds + 10 seconds after waypoint mark
 * - Trip completion
 * 
 * Each trip session gets its own log file in /validatorlogs folder.
 */
class TripSessionLogger(private val context: Context) {
    
    private var currentLogFile: File? = null
    private var currentTripId: String? = null
    private var isLoggingActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val pendingLogs = mutableListOf<String>()
    private var waypointMarkTime: Long = 0L
    private var statusProvider: (() -> String)? = null
    
    companion object {
        private const val TAG = "TripSessionLogger"
        private const val LOG_FOLDER_NAME = "validatorlogs"
        private const val WAYPOINT_LOG_DELAY_2S = 2000L // 2 seconds
        private const val WAYPOINT_LOG_DELAY_10S = 10000L // 10 seconds
    }
    
    /**
     * Sets the status provider callback for getting current status information
     */
    fun setStatusProvider(provider: () -> String) {
        statusProvider = provider
    }
    
    /**
     * Starts a new trip session log file
     * @param tripId Unique identifier for the trip
     * @param tripName Human-readable trip name for the file
     */
    fun startTripSession(tripId: String, tripName: String) {
        Log.d(TAG, "Starting trip session logging for trip: $tripId")
        
        try {
            // Create logs directory if it doesn't exist
            val logsDir = createLogsDirectory()
            if (logsDir == null) {
                Log.e(TAG, "Failed to create logs directory")
                return
            }
            
            // Generate filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sanitizedTripName = tripName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "trip_${tripId}_${sanitizedTripName}_$timestamp.log"
            
            currentLogFile = File(logsDir, fileName)
            currentTripId = tripId
            isLoggingActive = true
            
            // Write initial session header
            val sessionHeader = buildSessionHeader(tripId, tripName)
            writeToFile(sessionHeader)
            
            Log.d(TAG, "Trip session logging started: ${currentLogFile?.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start trip session logging: ${e.message}", e)
            isLoggingActive = false
        }
    }
    
    /**
     * Stops the current trip session logging
     */
    fun stopTripSession() {
        Log.d(TAG, "Stopping trip session logging")
        
        if (isLoggingActive && currentLogFile != null) {
            try {
                val sessionFooter = buildSessionFooter()
                writeToFile(sessionFooter)
                
                Log.d(TAG, "Trip session logging stopped: ${currentLogFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping trip session: ${e.message}", e)
            }
        }
        
        // Cancel any pending waypoint logs
        handler.removeCallbacksAndMessages(null)
        
        // Reset state
        isLoggingActive = false
        currentLogFile = null
        currentTripId = null
        pendingLogs.clear()
        waypointMarkTime = 0L
    }
    
    /**
     * Logs trip validation completion with detailed information
     */
    fun logTripValidation(detailedInfo: String? = null) {
        if (!isLoggingActive) return
        
        val logEntry = buildLogEntry("TRIP_VALIDATION", "Trip validation completed successfully")
        writeToFile(logEntry)
        
        // Add detailed validation information if provided
        if (!detailedInfo.isNullOrBlank()) {
            writeToFile(detailedInfo)
        }
        
        Log.d(TAG, "Logged trip validation")
    }
    
    /**
     * Logs first progress update with detailed status
     */
    fun logFirstProgressUpdate(detailedStatus: String? = null) {
        if (!isLoggingActive) return
        
        val logEntry = buildLogEntry("FIRST_PROGRESS", "First navigation progress update received")
        writeToFile(logEntry)
        
        // Add detailed status if provided
        if (!detailedStatus.isNullOrBlank()) {
            writeToFile(detailedStatus)
        }
        
        Log.d(TAG, "Logged first progress update")
    }
    
    /**
     * Logs waypoint mark and schedules follow-up logs with detailed status
     */
    fun logWaypointMark(waypointName: String, waypointIndex: Int, detailedStatus: String? = null) {
        if (!isLoggingActive) return
        
        waypointMarkTime = System.currentTimeMillis()
        
        // Immediate log
        val immediateLog = buildLogEntry("WAYPOINT_MARK", "Waypoint reached: $waypointName (Index: $waypointIndex)")
        writeToFile(immediateLog)
        
        // Add detailed status if provided
        if (!detailedStatus.isNullOrBlank()) {
            writeToFile(detailedStatus)
        }
        
        // Schedule 2-second follow-up
        handler.postDelayed({
            if (isLoggingActive) {
                val followUp2s = buildLogEntry("WAYPOINT_FOLLOWUP_2S", "2 seconds after waypoint mark: $waypointName")
                writeToFile(followUp2s)
                
                // Get current status for follow-up log
                val currentStatus = statusProvider?.invoke()
                if (!currentStatus.isNullOrBlank()) {
                    writeToFile(currentStatus)
                }
            }
        }, WAYPOINT_LOG_DELAY_2S)
        
        // Schedule 10-second follow-up
        handler.postDelayed({
            if (isLoggingActive) {
                val followUp10s = buildLogEntry("WAYPOINT_FOLLOWUP_10S", "10 seconds after waypoint mark: $waypointName")
                writeToFile(followUp10s)
                
                // Get current status for follow-up log
                val currentStatus = statusProvider?.invoke()
                if (!currentStatus.isNullOrBlank()) {
                    writeToFile(currentStatus)
                }
            }
        }, WAYPOINT_LOG_DELAY_10S)
        
        Log.d(TAG, "Logged waypoint mark and scheduled follow-ups for: $waypointName")
    }
    
    /**
     * Logs trip completion with detailed status
     */
    fun logTripCompletion(detailedStatus: String? = null) {
        if (!isLoggingActive) return
        
        val logEntry = buildLogEntry("TRIP_COMPLETION", "Trip completed successfully")
        writeToFile(logEntry)
        
        // Add detailed status if provided
        if (!detailedStatus.isNullOrBlank()) {
            writeToFile(detailedStatus)
        }
        
        Log.d(TAG, "Logged trip completion")
    }
    
    /**
     * Logs custom events (for debugging or special cases)
     */
    fun logCustomEvent(eventType: String, message: String) {
        if (!isLoggingActive) return
        
        val logEntry = buildLogEntry("CUSTOM_$eventType", message)
        writeToFile(logEntry)
        Log.d(TAG, "Logged custom event: $eventType")
    }
    
    /**
     * Creates the logs directory in the same location as downloads
     */
    private fun createLogsDirectory(): File? {
        return try {
            // Get external files directory (same level as downloads)
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir == null) {
                Log.e(TAG, "External files directory is null")
                return null
            }
            
            val logsDir = File(externalFilesDir, LOG_FOLDER_NAME)
            if (!logsDir.exists()) {
                val created = logsDir.mkdirs()
                if (!created) {
                    Log.e(TAG, "Failed to create logs directory: ${logsDir.absolutePath}")
                    return null
                }
                Log.d(TAG, "Created logs directory: ${logsDir.absolutePath}")
            }
            
            logsDir
        } catch (e: Exception) {
            Log.e(TAG, "Error creating logs directory: ${e.message}", e)
            null
        }
    }
    
    /**
     * Writes content to the current log file
     */
    private fun writeToFile(content: String) {
        if (currentLogFile == null) {
            Log.w(TAG, "No current log file, cannot write: $content")
            return
        }
        
        try {
            FileWriter(currentLogFile, true).use { writer ->
                writer.append(content)
                writer.append("\n")
                writer.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to log file: ${e.message}", e)
        }
    }
    
    /**
     * Builds a formatted log entry with timestamp
     */
    private fun buildLogEntry(eventType: String, message: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        return "[$timestamp] [$eventType] $message"
    }
    
    /**
     * Builds the session header for the log file
     */
    private fun buildSessionHeader(tripId: String, tripName: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val deviceInfo = "Device: ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE})"
        val appVersion = "App Version: ${getAppVersion()}"
        
        return buildString {
            appendLine("=".repeat(80))
            appendLine("TRIP SESSION LOG")
            appendLine("=".repeat(80))
            appendLine("Trip ID: $tripId")
            appendLine("Trip Name: $tripName")
            appendLine("Session Started: $timestamp")
            appendLine(deviceInfo)
            appendLine(appVersion)
            appendLine("=".repeat(80))
            appendLine()
        }
    }
    
    /**
     * Builds the session footer for the log file
     */
    private fun buildSessionFooter(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        return buildString {
            appendLine()
            appendLine("=".repeat(80))
            appendLine("SESSION ENDED: $timestamp")
            appendLine("=".repeat(80))
        }
    }
    
    /**
     * Gets the app version name
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Gets the current log file path (for debugging)
     */
    fun getCurrentLogFilePath(): String? = currentLogFile?.absolutePath
    
    /**
     * Checks if logging is currently active
     */
    fun isLoggingActive(): Boolean = isLoggingActive
    
    /**
     * Gets the current trip ID
     */
    fun getCurrentTripId(): String? = currentTripId
}
