package com.gocavgo.validator.security

import android.content.Context
import android.content.Intent
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.database.VehicleSettingsEntity
import com.gocavgo.validator.dataclass.VehicleSettings
import com.gocavgo.validator.navigator.AutoModeHeadlessActivity
import com.gocavgo.validator.service.RemoteDataManager
import com.gocavgo.validator.service.RemoteResult
import com.gocavgo.validator.security.VehicleAuthActivity
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Broadcast actions for settings changes
const val ACTION_SETTINGS_CHANGED = "com.gocavgo.validator.ACTION_SETTINGS_CHANGED"
const val ACTION_SETTINGS_LOGOUT = "com.gocavgo.validator.ACTION_SETTINGS_LOGOUT"
const val ACTION_SETTINGS_DEACTIVATE = "com.gocavgo.validator.ACTION_SETTINGS_DEACTIVATE"

/**
 * Manages vehicle settings: fetching from API, saving to database, and applying settings-based behavior
 */
class VehicleSettingsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VehicleSettingsManager"

        @Volatile
        private var INSTANCE: VehicleSettingsManager? = null

        fun getInstance(context: Context): VehicleSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleSettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val remoteDataManager = RemoteDataManager.getInstance()
    private val database = AppDatabase.getDatabase(context)
    private val settingsDao = database.vehicleSettingsDao()

    /**
     * Fetch settings from API
     */
    suspend fun fetchSettingsFromApi(vehicleId: Int): Result<VehicleSettings> {
        return withContext(Dispatchers.IO) {
            try {
                Logging.d(TAG, "Fetching settings from API for vehicle $vehicleId")
                val result = remoteDataManager.getVehicleSettings(vehicleId)

                when {
                    result.isSuccess() -> {
                        val settings = result.getDataOrNull()
                        if (settings != null) {
                            Logging.d(TAG, "Settings fetched successfully")
                            // Save to database
                            saveSettings(settings)
                            Result.success(settings)
                        } else {
                            Logging.e(TAG, "Settings data is null")
                            Result.failure(Exception("Settings data is null"))
                        }
                    }
                    result.isError() -> {
                        val error = result.getErrorOrNull() ?: "Unknown error"
                        Logging.e(TAG, "Failed to fetch settings: $error")
                        // If API fails, try to use saved settings from database
                        val savedSettings = getSettings(vehicleId)
                        if (savedSettings != null) {
                            Logging.d(TAG, "Using saved settings from database as fallback")
                            Result.success(savedSettings)
                        } else {
                            Result.failure(Exception(error))
                        }
                    }
                    else -> {
                        Result.failure(Exception("Unknown error"))
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception fetching settings: ${e.message}", e)
                // If API fails, try to use saved settings from database
                val savedSettings = getSettings(vehicleId)
                if (savedSettings != null) {
                    Logging.d(TAG, "Using saved settings from database as fallback")
                    Result.success(savedSettings)
                } else {
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * Save settings to database
     */
    suspend fun saveSettings(settings: VehicleSettings) {
        withContext(Dispatchers.IO) {
            try {
                Logging.d(TAG, "Saving settings to database for vehicle ${settings.vehicleId}")
                val entity = VehicleSettingsEntity.fromVehicleSettings(settings)
                settingsDao.saveSettings(entity)
                Logging.d(TAG, "Settings saved successfully")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to save settings: ${e.message}", e)
            }
        }
    }

    /**
     * Get settings from database
     */
    suspend fun getSettings(vehicleId: Int): VehicleSettings? {
        return withContext(Dispatchers.IO) {
            try {
                val entity = settingsDao.getSettings(vehicleId)
                entity?.toVehicleSettings()
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to get settings: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Get the last updated timestamp for settings from database
     * Returns null if no settings exist in database
     */
    suspend fun getSettingsLastUpdated(vehicleId: Int): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val entity = settingsDao.getSettings(vehicleId)
                entity?.lastUpdated
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to get settings last updated: ${e.message}", e)
                null
            }
        }
    }


    /**
     * Apply settings - handles logout, deactivate, and navigation flow logic
     */
    fun applySettings(context: Context, settings: VehicleSettings, broadcastChanges: Boolean = true) {
        Logging.d(TAG, "=== APPLYING SETTINGS ===")
        Logging.d(TAG, "Vehicle ID: ${settings.vehicleId}")
        Logging.d(TAG, "logout: ${settings.logout}")
        Logging.d(TAG, "devmode: ${settings.devmode}")
        Logging.d(TAG, "deactivate: ${settings.deactivate}")
        Logging.d(TAG, "appmode: ${settings.appmode}")
        Logging.d(TAG, "simulate: ${settings.simulate}")

        // Get last applied settings to check if they changed
        val lastApplied = getLastAppliedSettings(settings.vehicleId)
        val settingsChanged = lastApplied == null || !areSettingsEqual(lastApplied, settings)
        
        if (!settingsChanged) {
            Logging.d(TAG, "Settings unchanged, skipping apply")
            return
        }

        // Store last applied settings
        setLastAppliedSettings(settings.vehicleId, settings)

        // Broadcast settings changed event only if requested and settings actually changed
        if (broadcastChanges && settingsChanged) {
            broadcastSettingsChanged(context, settings)
        }

        // Check logout first
        if (settings.logout) {
            Logging.d(TAG, "Logout setting is true - clearing credentials and launching auth")
            // Broadcast logout action
            if (broadcastChanges) {
                broadcastLogout(context)
            }
            handleLogout(context)
            return
        }

        // Check deactivate
        val wasDeactivated = lastApplied?.deactivate ?: false
        if (settings.deactivate != wasDeactivated && broadcastChanges) {
            // Broadcast deactivate state change only if it changed
            broadcastDeactivate(context, settings.deactivate)
        }

        if (settings.deactivate) {
            Logging.d(TAG, "Deactivate setting is true - keeping logged in but restricting UI")
            // Deactivate is handled in UI layer (grayed out buttons)
            return
        }

        // Check if all settings are false
        val allFalse = !settings.logout && !settings.devmode && !settings.deactivate && !settings.appmode && !settings.simulate
        if (allFalse) {
            Logging.d(TAG, "All settings are false - AutoModeHeadlessActivity is primary")
            // This is handled in navigation logic
            return
        }

        Logging.d(TAG, "Settings applied")
    }
    
    /**
     * Check if two settings objects are equal
     */
    private fun areSettingsEqual(s1: VehicleSettings, s2: VehicleSettings): Boolean {
        return s1.logout == s2.logout &&
               s1.devmode == s2.devmode &&
               s1.deactivate == s2.deactivate &&
               s1.appmode == s2.appmode &&
               s1.simulate == s2.simulate
    }
    
    // Thread-safe storage for last applied settings
    private val lastAppliedSettings = mutableMapOf<Int, VehicleSettings>()
    
    /**
     * Get last applied settings for a vehicle
     */
    private fun getLastAppliedSettings(vehicleId: Int): VehicleSettings? {
        synchronized(lastAppliedSettings) {
            return lastAppliedSettings[vehicleId]
        }
    }
    
    /**
     * Store last applied settings for a vehicle
     */
    private fun setLastAppliedSettings(vehicleId: Int, settings: VehicleSettings) {
        synchronized(lastAppliedSettings) {
            lastAppliedSettings[vehicleId] = settings
        }
    }

    /**
     * Broadcast settings changed event
     */
    private fun broadcastSettingsChanged(context: Context, settings: VehicleSettings) {
        try {
            val intent = Intent(ACTION_SETTINGS_CHANGED).apply {
                putExtra("vehicle_id", settings.vehicleId)
                putExtra("logout", settings.logout)
                putExtra("devmode", settings.devmode)
                putExtra("deactivate", settings.deactivate)
                putExtra("appmode", settings.appmode)
                putExtra("simulate", settings.simulate)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Logging.d(TAG, "Broadcasted settings changed event")
        } catch (e: Exception) {
            Logging.e(TAG, "Error broadcasting settings changed: ${e.message}", e)
        }
    }

    /**
     * Broadcast logout action
     */
    private fun broadcastLogout(context: Context) {
        try {
            val intent = Intent(ACTION_SETTINGS_LOGOUT).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Logging.d(TAG, "Broadcasted logout action")
        } catch (e: Exception) {
            Logging.e(TAG, "Error broadcasting logout: ${e.message}", e)
        }
    }

    /**
     * Broadcast deactivate state change
     */
    private fun broadcastDeactivate(context: Context, isDeactivated: Boolean) {
        try {
            val intent = Intent(ACTION_SETTINGS_DEACTIVATE).apply {
                putExtra("is_deactivated", isDeactivated)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Logging.d(TAG, "Broadcasted deactivate state: $isDeactivated")
        } catch (e: Exception) {
            Logging.e(TAG, "Error broadcasting deactivate: ${e.message}", e)
        }
    }

    /**
     * Handle logout: clear credentials and launch auth activity
     */
    private fun handleLogout(context: Context) {
        try {
            val securityManager = VehicleSecurityManager(context)
            
            // Clear vehicle preferences
            securityManager.clearVehicleData()
            Logging.d(TAG, "Vehicle data cleared")
            
            // Delete key pair
            securityManager.deleteKeyPair()
            Logging.d(TAG, "Key pair deleted")
            
            // Launch VehicleAuthActivity
            val intent = Intent(context, VehicleAuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
            Logging.d(TAG, "VehicleAuthActivity launched")
        } catch (e: Exception) {
            Logging.e(TAG, "Error handling logout: ${e.message}", e)
        }
    }

    /**
     * Check if all settings are false (AutoMode only mode)
     */
    fun areAllSettingsFalse(settings: VehicleSettings): Boolean {
        return !settings.logout && !settings.devmode && !settings.deactivate && !settings.appmode && !settings.simulate
    }

    /**
     * Check if devmode allows MainActivity access
     */
    fun isDevmodeAllowed(settings: VehicleSettings): Boolean {
        return settings.devmode
    }
}

