package com.gocavgo.validator.navigator

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for TripSessionLogger functionality
 */
@RunWith(MockitoJUnitRunner::class)
class TripSessionLoggerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockExternalFilesDir: File

    @Test
    fun `test trip session logger initialization`() {
        // Given
        val mockLogsDir = File("/mock/path/validatorlogs")
        whenever(mockContext.getExternalFilesDir(null)).thenReturn(mockExternalFilesDir)
        whenever(mockExternalFilesDir.absolutePath).thenReturn("/mock/path")
        
        // When
        val logger = TripSessionLogger(mockContext)
        
        // Then
        assertNotNull(logger)
    }

    @Test
    fun `test trip session start and stop`() {
        // Given
        val mockLogsDir = File("/mock/path/validatorlogs")
        whenever(mockContext.getExternalFilesDir(null)).thenReturn(mockExternalFilesDir)
        whenever(mockExternalFilesDir.absolutePath).thenReturn("/mock/path")
        
        val logger = TripSessionLogger(mockContext)
        val tripId = "test-trip-123"
        val tripName = "Test Trip"
        
        // When
        logger.startTripSession(tripId, tripName)
        
        // Then
        assertTrue(logger.isLoggingActive())
        assertTrue(logger.getCurrentTripId() == tripId)
        
        // When stopping
        logger.stopTripSession()
        
        // Then
        assertTrue(!logger.isLoggingActive())
    }

    @Test
    fun `test logging events`() {
        // Given
        val mockLogsDir = File("/mock/path/validatorlogs")
        whenever(mockContext.getExternalFilesDir(null)).thenReturn(mockExternalFilesDir)
        whenever(mockExternalFilesDir.absolutePath).thenReturn("/mock/path")
        
        val logger = TripSessionLogger(mockContext)
        val tripId = "test-trip-456"
        val tripName = "Test Trip 2"
        
        // When
        logger.startTripSession(tripId, tripName)
        logger.logTripValidation()
        logger.logFirstProgressUpdate()
        logger.logWaypointMark("Test Waypoint", 1)
        logger.logTripCompletion()
        logger.stopTripSession()
        
        // Then - all operations should complete without exceptions
        assertTrue(true) // If we get here, no exceptions were thrown
    }
}
