package com.gocavgo.validator.navigator

import android.content.Context
import android.widget.Toast
import com.gocavgo.validator.MapDownloaderManager
import com.gocavgo.validator.util.Logging
import com.here.sdk.core.engine.SDKNativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages map download operations and state.
 * Handles map download initialization, progress tracking, and state management.
 */
data class MapDownloadState(
    val progress: Int = 0,
    val totalSize: Int = 0,
    val message: String = "",
    val status: String = "",
    val showDialog: Boolean = false,
    val isReady: Boolean = false
)

class MapDownloadCoordinator(private val context: Context) {
    companion object {
        private const val TAG = "MapDownloadCoordinator"
    }

    private val _state = MutableStateFlow(MapDownloadState())
    val state: StateFlow<MapDownloadState> = _state.asStateFlow()

    private var mapDownloaderManager: MapDownloaderManager? = null

    /**
     * Initialize map downloader
     */
    fun initialize() {
        Logging.d(TAG, "=== INITIALIZING MAP DOWNLOADER ===")

        // Check if HERE SDK is initialized
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine == null) {
            Logging.e(TAG, "HERE SDK not initialized! Cannot initialize map downloader.")
            _state.value = _state.value.copy(isReady = true) // Proceed without offline maps
            return
        }

        Logging.d(TAG, "HERE SDK is initialized, proceeding with map downloader")

        mapDownloaderManager = MapDownloaderManager(
            context = context,
            onProgressUpdate = { message, progress, totalSizeMB ->
                _state.value = _state.value.copy(
                    message = message,
                    progress = progress,
                    totalSize = totalSizeMB
                )
                Logging.d(TAG, "Map download progress: $message - $progress% (${totalSizeMB}MB)")
            },
            onStatusUpdate = { status ->
                _state.value = _state.value.copy(status = status)
                Logging.d(TAG, "Map download status: $status")
            },
            onDownloadComplete = {
                _state.value = _state.value.copy(
                    isReady = true,
                    showDialog = false
                )
                Logging.d(TAG, "Map download completed successfully")
            },
            onError = { error ->
                Logging.e(TAG, "Map download error: $error")
                _state.value = _state.value.copy(showDialog = false)
            },
            onToastMessage = { message ->
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onShowProgressDialog = {
                (context as? android.app.Activity)?.runOnUiThread {
                    _state.value = _state.value.copy(showDialog = true)
                }
            }
        )

        // Initialize the map downloader in background
        try {
            mapDownloaderManager?.initialize()
            Logging.d(TAG, "Map downloader initialized successfully")
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to initialize map downloader: ${e.message}", e)
            _state.value = _state.value.copy(isReady = true) // Proceed without offline maps
        }

        Logging.d(TAG, "================================")
    }

    /**
     * Cancel downloads
     */
    fun cancelDownloads() {
        mapDownloaderManager?.cancelDownloads()
        _state.value = _state.value.copy(showDialog = false)
    }

    /**
     * Notify map downloader of network availability
     */
    fun onNetworkAvailable() {
        mapDownloaderManager?.onNetworkAvailable()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        mapDownloaderManager?.cleanup()
        mapDownloaderManager = null
        Logging.d(TAG, "Map download coordinator cleaned up")
    }

    /**
     * Get current state
     */
    fun getState(): MapDownloadState = _state.value
}

