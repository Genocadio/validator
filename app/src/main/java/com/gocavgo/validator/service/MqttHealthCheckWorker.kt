package com.gocavgo.validator.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for MQTT health checks during doze mode
 * Ensures MQTT connection is restored after device wakes from doze mode
 */
class MqttHealthCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "MqttHealthCheckWorker"
        private const val WORK_NAME = "mqtt_health_check"
        private const val HEALTH_CHECK_INTERVAL_MINUTES = 15L
        
        /**
         * Schedule periodic health checks
         */
        fun schedule(context: Context) {
            try {
                val workRequest = PeriodicWorkRequestBuilder<MqttHealthCheckWorker>(
                    HEALTH_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                )
                    .setInitialDelay(HEALTH_CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                
                Log.d(TAG, "Scheduled MQTT health check every $HEALTH_CHECK_INTERVAL_MINUTES minutes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule MQTT health check: ${e.message}", e)
            }
        }
        
        /**
         * Cancel health check work
         */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Cancelled MQTT health check work")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel MQTT health check: ${e.message}", e)
            }
        }
        
        /**
         * Check if health check is scheduled
         */
        fun isScheduled(context: Context): Boolean {
            return try {
                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                
                workInfos.any { !it.state.isFinished }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check if health check is scheduled: ${e.message}", e)
                false
            }
        }
    }
    
    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting MQTT health check...")
            
            // Get MQTT service instance
            val mqttService = MqttService.getInstance()
            
            if (mqttService == null) {
                Log.w(TAG, "MQTT service instance is null, skipping health check")
                return Result.success()
            }
            
            // Check if service is healthy
            val isHealthy = mqttService.isHealthy()
            Log.d(TAG, "MQTT service health status: $isHealthy")
            
            if (!isHealthy) {
                Log.w(TAG, "MQTT service is unhealthy, attempting to fix...")
                
                // Try to fix inconsistent state
                mqttService.checkAndFixInconsistentState()
                
                // Wait a bit for connection attempt
                delay(5000)
                
                // Check again
                val isHealthyAfterFix = mqttService.isHealthy()
                Log.d(TAG, "MQTT service health after fix attempt: $isHealthyAfterFix")
                
                if (!isHealthyAfterFix) {
                    Log.w(TAG, "MQTT service still unhealthy after fix attempt")
                    // Don't fail the work - it will retry on next interval
                }
            } else {
                Log.d(TAG, "MQTT service is healthy")
            }
            
            // Update foreground service notification if running
            val foregroundService = MqttForegroundService.isRunning()
            if (foregroundService) {
                Log.d(TAG, "Foreground service is running, health check complete")
            }
            
            Log.d(TAG, "MQTT health check completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "MQTT health check failed: ${e.message}", e)
            // Return retry result to try again on next interval
            Result.retry()
        }
    }
}
