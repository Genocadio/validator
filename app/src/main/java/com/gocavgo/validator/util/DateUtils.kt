package com.gocavgo.validator.util

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Utility functions for date parsing and conversion
 */
object DateUtils {
    private val TAG = "DateUtils"
    
    // ISO 8601 date formatter with optional milliseconds and timezone
    private val iso8601Formatter = DateTimeFormatter.ISO_INSTANT
    
    /**
     * Parse ISO 8601 date string to Long timestamp (milliseconds)
     * Handles formats like:
     * - "2025-11-14T08:02:56.165024Z"
     * - "2025-11-14T08:02:56Z"
     * 
     * @param dateString ISO 8601 date string
     * @return Long timestamp in milliseconds, or null if parsing fails
     */
    fun parseIso8601ToMillis(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) {
            return null
        }
        
        return try {
            // Try parsing with ISO_INSTANT (handles Z timezone)
            val instant = Instant.parse(dateString)
            instant.toEpochMilli()
        } catch (e: DateTimeParseException) {
            Logging.w(TAG, "Failed to parse ISO 8601 date: $dateString, error: ${e.message}")
            null
        } catch (e: Exception) {
            Logging.e(TAG, "Unexpected error parsing date: $dateString, error: ${e.message}", e)
            null
        }
    }
    
    /**
     * Parse ISO 8601 date string to Long timestamp, with fallback to current time
     * @param dateString ISO 8601 date string
     * @return Long timestamp in milliseconds, or current time if parsing fails
     */
    fun parseIso8601ToMillisOrNow(dateString: String?): Long {
        return parseIso8601ToMillis(dateString) ?: System.currentTimeMillis()
    }
}

