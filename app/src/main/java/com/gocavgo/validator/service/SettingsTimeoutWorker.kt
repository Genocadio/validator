package com.gocavgo.validator.service

import android.content.Context
import android.content.Intent
import com.gocavgo.validator.util.Logging
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gocavgo.validator.database.AppDatabase
import com.gocavgo.validator.security.VehicleAuthActivity
import com.gocavgo.validator.security.VehicleSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker to check if settings haven't been updated for 4 days
 * If no settings update (from API or MQTT) for 4 days, automatically logout
 */
class SettingsTimeoutWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "SettingsTimeoutWorker"
        private const val WORK_NAME = "settings_timeout_worker"
        private const val CHECK_INTERVAL_HOURS = 24L // Check daily
        private const val TIMEOUT_DAYS = 4L // 4 days timeout
        private const val TIMEOUT_MILLIS = TIMEOUT_DAYS * 24 * 60 * 60 * 1000L // 4 days in milliseconds
        
        /**
         * Schedule periodic settings timeout check
         */
        fun schedule(context: Context) {
            try {
                val workRequest = PeriodicWorkRequestBuilder<SettingsTimeoutWorker>(
                    CHECK_INTERVAL_HOURS,
                    TimeUnit.HOURS
                )
                    .setInitialDelay(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                    .build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                
                Logging.d(TAG, "Settings timeout worker scheduled (checks every $CHECK_INTERVAL_HOURS hours, timeout after $TIMEOUT_DAYS days)")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to schedule settings timeout worker: ${e.message}", e)
            }
        }
        
        /**
         * Cancel settings timeout check
         */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Logging.d(TAG, "Settings timeout worker cancelled")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to cancel settings timeout worker: ${e.message}", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Logging.d(TAG, "=== SETTINGS TIMEOUT CHECK ===")
            
            // Check if vehicle is registered
            val securityManager = VehicleSecurityManager(applicationContext)
            if (!securityManager.isVehicleRegistered()) {
                Logging.d(TAG, "Vehicle not registered, skipping timeout check")
                return@withContext Result.success()
            }
            
            val vehicleId = securityManager.getVehicleId().toInt()
            Logging.d(TAG, "Checking timeout for vehicle ID: $vehicleId")
            
            // Get settings from database
            val database = AppDatabase.getDatabase(applicationContext)
            val settingsDao = database.vehicleSettingsDao()
            val settingsEntity = settingsDao.getSettings(vehicleId)
            
            if (settingsEntity == null) {
                Logging.d(TAG, "No settings found in database, skipping timeout check")
                return@withContext Result.success()
            }
            
            val lastUpdated = settingsEntity.lastUpdated
            val currentTime = System.currentTimeMillis()
            val timeSinceUpdate = currentTime - lastUpdated
            
            Logging.d(TAG, "Last settings update: $lastUpdated (${formatTime(lastUpdated)})")
            Logging.d(TAG, "Current time: $currentTime (${formatTime(currentTime)})")
            Logging.d(TAG, "Time since update: ${timeSinceUpdate / (24 * 60 * 60 * 1000)} days")
            
            if (timeSinceUpdate >= TIMEOUT_MILLIS) {
                Logging.w(TAG, "=== SETTINGS TIMEOUT DETECTED ===")
                Logging.w(TAG, "No settings update for ${timeSinceUpdate / (24 * 60 * 60 * 1000)} days (threshold: $TIMEOUT_DAYS days)")
                Logging.w(TAG, "Triggering automatic logout...")
                
                // Clear vehicle data
                securityManager.clearVehicleData()
                Logging.d(TAG, "Vehicle data cleared")
                
                // Delete key pair
                securityManager.deleteKeyPair()
                Logging.d(TAG, "Key pair deleted")
                
                // Clear settings from database
                settingsDao.deleteSettings(vehicleId)
                Logging.d(TAG, "Settings deleted from database")
                
                // Launch VehicleAuthActivity
                val intent = Intent(applicationContext, VehicleAuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                applicationContext.startActivity(intent)
                Logging.d(TAG, "VehicleAuthActivity launched")
                
                Logging.w(TAG, "=== AUTOMATIC LOGOUT COMPLETE ===")
            } else {
                val daysRemaining = (TIMEOUT_MILLIS - timeSinceUpdate) / (24 * 60 * 60 * 1000)
                Logging.d(TAG, "Settings are fresh. Timeout in ${daysRemaining} days")
            }
            
            Logging.d(TAG, "=== END SETTINGS TIMEOUT CHECK ===")
            Result.success()
        } catch (e: Exception) {
            Logging.e(TAG, "Settings timeout check failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    private fun formatTime(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
}

