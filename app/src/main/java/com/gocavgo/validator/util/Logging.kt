package com.gocavgo.validator.util

import android.app.Activity
import android.util.Log
import com.gocavgo.validator.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced logging system with per-activity state management.
 * 
 * Features:
 * - Per-activity logging preferences that persist across activity transitions
 * - Automatic logging state management based on active activity
 * - Only one activity can have logging enabled at a time
 * - When an activity becomes active, it automatically disables logging for all other activities
 * 
 * Usage:
 * 1. In onCreate(): Logging.setActivityLoggingEnabled(TAG, false) // to disable logging for this activity
 * 2. In onResume(): Logging.setActiveActivity(TAG) // to make this activity active
 * 3. The system will automatically manage logging states across activities
 * 
 * Example:
 * - MainActivity disables its logging: Logging.setActivityLoggingEnabled("MainActivity", false)
 * - When NavigActivity becomes active: Logging.setActiveActivity("NavigActivity") 
 * - MainActivity logging remains disabled, NavigActivity logging is enabled (default)
 * - When returning to MainActivity: Logging.setActiveActivity("MainActivity")
 * - MainActivity logging remains disabled, NavigActivity logging is automatically disabled
 */
object Logging {
    @Volatile
    private var globalEnabled: Boolean = BuildConfig.DEBUG

    private val tagEnabledOverrides: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()
    
    // Per-activity logging state management
    private val activityLoggingStates: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()
    private var currentActiveActivity: String? = null
    private val activityLoggingPreferences: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    fun setGlobalEnabled(enabled: Boolean) {
        globalEnabled = enabled
    }

    fun setTagEnabled(tag: String, enabled: Boolean) {
        tagEnabledOverrides[tag] = enabled
    }

    fun clearTagOverride(tag: String) {
        tagEnabledOverrides.remove(tag)
    }

    fun clearAllOverrides() {
        tagEnabledOverrides.clear()
    }

    /**
     * Set logging state for a specific activity and make it the active activity
     * This will disable logging for all other activities
     */
    fun setActivityLoggingEnabled(activityTag: String, enabled: Boolean) {
        // Store the preference for this activity
        activityLoggingPreferences[activityTag] = enabled
        
        // Set this activity as the current active one
        currentActiveActivity = activityTag
        
        // Update the logging state for this activity
        activityLoggingStates[activityTag] = enabled
        
        // Disable logging for all other activities
        activityLoggingStates.keys.forEach { tag ->
            if (tag != activityTag) {
                activityLoggingStates[tag] = false
            }
        }
        
        // Update the global tag override based on current active activity
        updateGlobalTagOverride()
    }

    /**
     * Set an activity as active without changing its logging preference
     * This will disable logging for all other activities
     */
    fun setActiveActivity(activityTag: String) {
        currentActiveActivity = activityTag
        
        // Get the stored preference for this activity, default to true if not set
        val enabled = activityLoggingPreferences[activityTag] ?: true
        
        // Update the logging state for this activity
        activityLoggingStates[activityTag] = enabled
        
        // Disable logging for all other activities
        activityLoggingStates.keys.forEach { tag ->
            if (tag != activityTag) {
                activityLoggingStates[tag] = false
            }
        }
        
        // Update the global tag override based on current active activity
        updateGlobalTagOverride()
    }

    /**
     * Get the current logging state for an activity
     */
    fun isActivityLoggingEnabled(activityTag: String): Boolean {
        return activityLoggingStates[activityTag] ?: true
    }

    /**
     * Get the stored preference for an activity (regardless of current active state)
     */
    fun getActivityLoggingPreference(activityTag: String): Boolean {
        return activityLoggingPreferences[activityTag] ?: true
    }

    /**
     * Clear all activity logging states and preferences
     */
    fun clearAllActivityStates() {
        activityLoggingStates.clear()
        activityLoggingPreferences.clear()
        currentActiveActivity = null
        updateGlobalTagOverride()
    }

    /**
     * Update the global tag override based on the current active activity
     */
    private fun updateGlobalTagOverride() {
        currentActiveActivity?.let { activeTag ->
            val isEnabled = activityLoggingStates[activeTag] ?: true
            tagEnabledOverrides[activeTag] = isEnabled
        }
    }

    fun isEnabled(tag: String): Boolean {
        // First check if this is an activity tag with specific state
        if (activityLoggingStates.containsKey(tag)) {
            val activityEnabled = activityLoggingStates[tag] ?: true
            return BuildConfig.DEBUG && globalEnabled && activityEnabled
        }
        
        // Fall back to regular tag override logic
        val tagEnabled = tagEnabledOverrides[tag]
        val effectiveTagEnabled = tagEnabled ?: true
        return BuildConfig.DEBUG && globalEnabled && effectiveTagEnabled
    }

    // Debug
    fun d(tag: String, message: String) {
        if (isEnabled(tag)) Log.d(tag, message)
    }

    fun d(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.d(tag, message, tr)
    }

    fun d(tag: String, messageProvider: () -> String) {
        if (isEnabled(tag)) Log.d(tag, messageProvider())
    }

    // Info
    fun i(tag: String, message: String) {
        if (isEnabled(tag)) Log.i(tag, message)
    }

    fun i(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.i(tag, message, tr)
    }

    fun i(tag: String, messageProvider: () -> String) {
        if (isEnabled(tag)) Log.i(tag, messageProvider())
    }

    // Warn
    fun w(tag: String, message: String) {
        if (isEnabled(tag)) Log.w(tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.w(tag, message, tr)
    }

    fun w(tag: String, tr: Throwable) {
        if (isEnabled(tag)) Log.w(tag, tr)
    }

    // Error
    fun e(tag: String, message: String) {
        if (isEnabled(tag)) Log.e(tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.e(tag, message, tr)
    }

    fun e(tag: String, messageProvider: () -> String) {
        if (isEnabled(tag)) Log.e(tag, messageProvider())
    }

    // Verbose
    fun v(tag: String, message: String) {
        if (isEnabled(tag)) Log.v(tag, message)
    }

    fun v(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.v(tag, message, tr)
    }
}



