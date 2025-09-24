package com.gocavgo.validator.dataclass

enum class TripStatus(val value: String) {
    PENDING("pending"),
    SCHEDULED("scheduled"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed");
    
    companion object {
        fun fromString(value: String): TripStatus {
            // Normalize the input value to handle both uppercase and lowercase
            val normalizedValue = normalizeStatus(value)
            return values().find { it.value == normalizedValue } ?: PENDING
        }
        
        /**
         * Normalize status string to lowercase format
         * Handles both backend API format (uppercase) and local format (lowercase)
         */
        fun normalizeStatus(status: String): String {
            return when (status.uppercase()) {
                "PENDING" -> "pending"
                "SCHEDULED" -> "scheduled"
                "IN_PROGRESS", "IN PROGRESS" -> "in_progress"
                "COMPLETED" -> "completed"
                "CANCELLED" -> "cancelled"
                "FAILED" -> "failed"
                else -> status.lowercase() // Fallback to lowercase
            }
        }
        
        /**
         * Check if status is active (pending, scheduled, or in_progress)
         * Handles both uppercase and lowercase formats
         */
        fun isActive(status: String): Boolean {
            val normalizedStatus = normalizeStatus(status)
            return normalizedStatus == IN_PROGRESS.value || 
                   normalizedStatus == PENDING.value || 
                   normalizedStatus == SCHEDULED.value
        }
        
        /**
         * Check if status is completed (completed, cancelled, or failed)
         * Handles both uppercase and lowercase formats
         */
        fun isCompleted(status: String): Boolean {
            val normalizedStatus = normalizeStatus(status)
            return normalizedStatus == COMPLETED.value || 
                   normalizedStatus == CANCELLED.value || 
                   normalizedStatus == FAILED.value
        }
    }
}
