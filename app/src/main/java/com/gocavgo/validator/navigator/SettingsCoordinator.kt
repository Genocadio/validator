package com.gocavgo.validator.navigator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.gocavgo.validator.dataclass.VehicleSettings
import com.gocavgo.validator.security.ACTION_SETTINGS_CHANGED
import com.gocavgo.validator.security.ACTION_SETTINGS_DEACTIVATE
import com.gocavgo.validator.security.ACTION_SETTINGS_LOGOUT
import com.gocavgo.validator.security.VehicleSecurityManager
import com.gocavgo.validator.security.VehicleSettingsManager
import com.gocavgo.validator.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages vehicle settings fetching, caching, and change handling.
 * Handles settings state and broadcasts.
 */
data class SettingsState(
    val currentSettings: VehicleSettings? = null,
    val isLoading: Boolean = false
)

class SettingsCoordinator(
    private val context: Context,
    private val settingsManager: VehicleSettingsManager,
    private val vehicleSecurityManager: VehicleSecurityManager,
    private val lifecycleScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SettingsCoordinator"
    }

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private var settingsChangeReceiver: BroadcastReceiver? = null

    // Callbacks for settings change handling
    var onLogout: (() -> Unit)? = null
    var onDeactivate: (() -> Unit)? = null
    var onSettingsChanged: ((VehicleSettings, Boolean) -> Unit)? = null // (settings, wasNavigating)

    /**
     * Fetch settings from API
     */
    fun fetchSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true)
            
            val vehicleId = vehicleSecurityManager.getVehicleId()
            try {
                val result = settingsManager.fetchSettingsFromApi(vehicleId.toInt())
                result.onSuccess { settings ->
                    _state.value = _state.value.copy(
                        currentSettings = settings,
                        isLoading = false
                    )
                    Logging.d(TAG, "Settings fetched from API: simulate=${settings.simulate}, devmode=${settings.devmode}")
                }.onFailure { error ->
                    Logging.e(TAG, "Failed to fetch settings: ${error.message}")
                    // Use saved settings if available
                    val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                    savedSettings?.let {
                        _state.value = _state.value.copy(
                            currentSettings = it,
                            isLoading = false
                        )
                    } ?: run {
                        _state.value = _state.value.copy(isLoading = false)
                    }
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Exception fetching settings: ${e.message}", e)
                // Use saved settings if available
                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                savedSettings?.let {
                    _state.value = _state.value.copy(
                        currentSettings = it,
                        isLoading = false
                    )
                } ?: run {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    /**
     * Get settings from database (suspending function)
     */
    suspend fun getSettingsFromDatabase(): VehicleSettings? {
        val vehicleId = vehicleSecurityManager.getVehicleId()
        return settingsManager.getSettings(vehicleId.toInt())
    }

    /**
     * Update settings state
     */
    fun updateSettings(settings: VehicleSettings?) {
        _state.value = _state.value.copy(currentSettings = settings)
        Logging.d(TAG, "Settings updated: ${settings?.simulate}, ${settings?.devmode}")
    }

    /**
     * Register settings change receiver
     */
    fun registerReceiver() {
        try {
            settingsChangeReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (context == null || intent == null) return

                    when (intent.action) {
                        ACTION_SETTINGS_LOGOUT -> {
                            Logging.d(TAG, "Received logout broadcast - immediately exiting")
                            onLogout?.invoke()
                        }
                        ACTION_SETTINGS_DEACTIVATE -> {
                            val isDeactivated = intent.getBooleanExtra("is_deactivated", false)
                            Logging.d(TAG, "Received deactivate broadcast - isDeactivated: $isDeactivated")
                            if (isDeactivated) {
                                Logging.d(TAG, "Deactivate=true - immediately exiting")
                                onDeactivate?.invoke()
                            }
                        }
                        ACTION_SETTINGS_CHANGED -> {
                            Logging.d(TAG, "Received settings changed broadcast - refreshing settings")
                            // Settings are already saved to DB by MQTT service
                            // Refresh settings from database and handle based on priority
                            lifecycleScope.launch {
                                val vehicleId = vehicleSecurityManager.getVehicleId()
                                val savedSettings = settingsManager.getSettings(vehicleId.toInt())
                                savedSettings?.let { newSettings ->
                                    val currentState = _state.value
                                    val currentSettings = currentState.currentSettings
                                    
                                    // Only update if different from current
                                    if (currentSettings == null ||
                                        currentSettings.logout != newSettings.logout ||
                                        currentSettings.devmode != newSettings.devmode ||
                                        currentSettings.deactivate != newSettings.deactivate ||
                                        currentSettings.appmode != newSettings.appmode ||
                                        currentSettings.simulate != newSettings.simulate) {
                                        
                                        // Update state
                                        _state.value = currentState.copy(currentSettings = newSettings)
                                        Logging.d(TAG, "Settings updated from broadcast: simulate=${newSettings.simulate}, devmode=${newSettings.devmode}, deactivate=${newSettings.deactivate}, logout=${newSettings.logout}")

                                        // Notify callback with settings and navigation state
                                        // Callback will determine if wasNavigating
                                        onSettingsChanged?.invoke(newSettings, false) // Callback should check navigation state
                                    } else {
                                        Logging.d(TAG, "Settings unchanged, skipping update")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(ACTION_SETTINGS_LOGOUT)
                addAction(ACTION_SETTINGS_DEACTIVATE)
                addAction(ACTION_SETTINGS_CHANGED)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(settingsChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(settingsChangeReceiver, filter)
            }
            Logging.d(TAG, "Settings change receiver registered")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to register settings change receiver: ${e.message}", e)
        }
    }

    /**
     * Unregister settings change receiver
     */
    fun unregisterReceiver() {
        try {
            settingsChangeReceiver?.let { context.unregisterReceiver(it) }
            settingsChangeReceiver = null
            Logging.d(TAG, "Settings change receiver unregistered")
        } catch (e: Exception) {
            Logging.w(TAG, "Error unregistering settings change receiver: ${e.message}")
        }
    }

    /**
     * Apply settings (check logout/deactivate and route accordingly)
     */
    fun applySettings(activity: android.app.Activity) {
        val settings = _state.value.currentSettings
        if (settings != null) {
            settingsManager.applySettings(activity, settings)
        }
    }

    /**
     * Check if all settings are false
     */
    fun areAllSettingsFalse(): Boolean {
        val settings = _state.value.currentSettings
        return settings != null && settingsManager.areAllSettingsFalse(settings)
    }

    /**
     * Get current state
     */
    fun getState(): SettingsState = _state.value
}

