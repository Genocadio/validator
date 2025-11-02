package com.gocavgo.validator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.here.sdk.core.LanguageCode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.maploader.DownloadRegionsStatusListener
import com.here.sdk.maploader.DownloadableRegionsCallback
import com.here.sdk.maploader.MapDownloader
import com.here.sdk.maploader.MapDownloaderTask
import com.here.sdk.maploader.MapLoaderError
import com.here.sdk.maploader.MapLoaderException
import com.here.sdk.maploader.Region
import com.here.sdk.maploader.RegionId
import com.here.sdk.maploader.InstalledRegion
import com.here.sdk.maploader.MapUpdater
import com.here.sdk.maploader.MapUpdaterConstructionCallback
import com.here.sdk.maploader.CatalogUpdateInfo
import com.here.sdk.maploader.CatalogUpdateProgressListener
import com.here.sdk.maploader.CatalogsUpdateInfoCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages automatic download and updates of Rwanda map data for offline navigation.
 * Based on HERE SDK OfflineMapsExample but simplified for Rwanda-specific use case.
 */
class MapDownloaderManager(
    private val context: Context,
    private val onProgressUpdate: (String, Int, Int) -> Unit, // (message, progress, totalSizeMB)
    private val onStatusUpdate: (String) -> Unit,
    private val onDownloadComplete: () -> Unit,
    private val onError: (String) -> Unit,
    private val onToastMessage: ((String) -> Unit)? = null, // Optional toast callback
    private val onShowProgressDialog: (() -> Unit)? = null // Optional progress dialog trigger
) {
    companion object {
        private const val TAG = "MapDownloaderManager"
        private const val MAX_DOWNLOAD_RETRIES = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 5000L
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    // HERE SDK objects
    private var mapDownloader: MapDownloader? = null
    private var mapUpdater: MapUpdater? = null

    // State
    private var downloadableRegions = mutableListOf<Region>()
    private val mapDownloaderTasks = mutableListOf<MapDownloaderTask>()
    private var rwandaRegion: Region? = null
    private var downloadRetryCount = 0
    private var lastUpdateCheckTime = 0L
    private var isDownloadInProgress = false
    private var isInitialized = false

    // Exposed state
    var isMapDataReady: Boolean = false
        private set

    // Coroutine scope for background operations
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initialize the MapDownloaderManager and start checking for map data
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "MapDownloaderManager already initialized")
            return
        }

        Log.d(TAG, "=== INITIALIZING MAP DOWNLOADER MANAGER ===")
        onStatusUpdate("Initializing map downloader...")

        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine == null) {
            Log.e(TAG, "SDKNativeEngine not initialized!")
            onError("SDK not initialized")
            isMapDataReady = true // Proceed without offline maps
            return
        }

        Log.d(TAG, "SDKNativeEngine found, initializing MapDownloader...")

        // Initialize MapDownloader
        MapDownloader.fromEngineAsync(sdkNativeEngine) { mapDownloader ->
            // Verify SDK is still valid before proceeding
            val currentSdk = SDKNativeEngine.getSharedInstance()
            if (currentSdk == null) {
                Log.w(TAG, "SDK disposed during MapDownloader initialization, aborting")
                onStatusUpdate("SDK unavailable during initialization")
                isMapDataReady = true // Proceed without offline maps
                return@fromEngineAsync
            }
            
            this@MapDownloaderManager.mapDownloader = mapDownloader
            Log.d(TAG, "MapDownloader initialized successfully")
            
            // Initialize MapUpdater
            MapUpdater.fromEngineAsync(currentSdk, object : MapUpdaterConstructionCallback {
                override fun onMapUpdaterConstructe(mapUpdater: MapUpdater) {
                    // Verify SDK is still valid before proceeding
                    val finalSdk = SDKNativeEngine.getSharedInstance()
                    if (finalSdk == null) {
                        Log.w(TAG, "SDK disposed during MapUpdater initialization, aborting")
                        onStatusUpdate("SDK unavailable during initialization")
                        isMapDataReady = true // Proceed without offline maps
                        return
                    }
                    
                    this@MapDownloaderManager.mapUpdater = mapUpdater
                    Log.d(TAG, "MapUpdater initialized successfully")
                    
                    isInitialized = true
                    Log.d(TAG, "MapDownloaderManager fully initialized, checking existing map data...")
                    checkExistingMapData()
                }
            })
        }
    }

    /**
     * Check if Rwanda map data is already installed
     */
    private fun checkExistingMapData() {
        // Verify SDK is still valid before proceeding
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine == null) {
            Log.w(TAG, "SDK not available, skipping map data check")
            onStatusUpdate("SDK not available, proceeding without offline maps")
            isMapDataReady = true // Proceed without offline maps
            return
        }

        mapDownloader?.let { downloader ->
            try {
                val installedRegions = downloader.installedRegions
                Log.d(TAG, "=== CHECKING INSTALLED REGIONS ===")
                Log.d(TAG, "Total installed regions: ${installedRegions.size}")

                var rwandaFound = false
                var rwandaRegionInstalled: InstalledRegion? = null

                for (region in installedRegions) {
                    val sizeInMB = region.sizeOnDiskInBytes / (1024 * 1024)
                    Log.d(TAG, "Installed region: ${region.regionId.id}, Size: ${sizeInMB}MB, Status: ${region.status}")

                    if (isRwandaRegion(region.regionId.id.toString())) {
                        rwandaFound = true
                        rwandaRegionInstalled = region
                        Log.d(TAG, "Found existing Rwanda map data!")
                    }
                }

                if (rwandaFound && rwandaRegionInstalled != null) {
                    Log.d(TAG, "Rwanda map data already available")
                    isMapDataReady = true
                    onStatusUpdate("Rwanda map data is ready")
                    
                    // Check for updates in background
                    checkForMapUpdatesBackground(rwandaRegionInstalled)
                    onDownloadComplete()
                } else {
                    Log.d(TAG, "No Rwanda map data found, will download")
                    onStatusUpdate("Rwanda map data not found, downloading...")
                    onToastMessage?.invoke("Downloading Rwanda map data...")
                    onShowProgressDialog?.invoke() // Show progress dialog for initial download
                    downloadRegionsList()
                }

                Log.d(TAG, "==============================")
            } catch (e: MapLoaderException) {
                // Check if error is OPERATION_AFTER_DISPOSE
                val errorString = e.error.toString()
                if (errorString.contains("OPERATION_AFTER_DISPOSE", ignoreCase = true) ||
                    errorString.contains("DISPOSE", ignoreCase = true)) {
                    Log.w(TAG, "SDK disposed during map check, proceeding without offline maps: ${e.error}")
                    isMapDataReady = true // Proceed without offline maps
                    onStatusUpdate("Map check cancelled (SDK unavailable)")
                } else {
                    Log.e(TAG, "Error checking installed regions: ${e.error}")
                    onError("Error checking installed regions: ${e.error}")
                }
            } catch (e: Exception) {
                // Catch any other exceptions (including runtime exceptions)
                Log.e(TAG, "Unexpected error checking installed regions: ${e.message}", e)
                val errorMessage = e.message ?: "Unknown error"
                if (errorMessage.contains("dispose", ignoreCase = true) || 
                    errorMessage.contains("OPERATION_AFTER_DISPOSE", ignoreCase = true)) {
                    Log.w(TAG, "SDK disposed, proceeding without offline maps")
                    isMapDataReady = true // Proceed without offline maps
                    onStatusUpdate("Map check cancelled (SDK unavailable)")
                } else {
                    onError("Error checking installed regions: $errorMessage")
                }
            }
        } ?: run {
            Log.w(TAG, "MapDownloader not available, skipping map data check")
            isMapDataReady = true // Proceed without offline maps
        }
    }

    /**
     * Download the list of available regions to find Rwanda
     */
    private fun downloadRegionsList() {
        mapDownloader?.let { downloader ->
            Log.d(TAG, "Downloading list of available regions...")
            onStatusUpdate("Fetching available regions...")

            downloader.getDownloadableRegions(LanguageCode.EN_US, object : DownloadableRegionsCallback {
                override fun onCompleted(mapLoaderError: MapLoaderError?, regions: MutableList<Region>?) {
                    if (mapLoaderError != null) {
                        Log.e(TAG, "Error downloading regions list: $mapLoaderError")
                        onError("Failed to fetch regions: $mapLoaderError")
                        return
                    }

                    if (regions != null) {
                        downloadableRegions = regions
                        Log.d(TAG, "Found ${regions.size} top-level regions")

                        val rwandaRegion = findRwandaRegion(regions)
                        if (rwandaRegion != null) {
                            Log.d(TAG, "Found Rwanda region: ${rwandaRegion.name}")
                            this@MapDownloaderManager.rwandaRegion = rwandaRegion
                            downloadRwandaMapWithRetry(rwandaRegion)
                        } else {
                            Log.e(TAG, "Rwanda region not found in available regions!")
                            onError("Rwanda region not found in available regions")
                        }
                    }
                }
            })
        }
    }

    /**
     * Find Rwanda region in the list of available regions
     */
    private fun findRwandaRegion(regions: List<Region>): Region? {
        val rwandaNames = listOf(
            "Rwanda", "RWANDA", "rwanda",
            "Republic of Rwanda", "République du Rwanda",
            "U Rwanda"
        )

        for (region in regions) {
            // Check top-level regions
            for (name in rwandaNames) {
                if (region.name.equals(name, ignoreCase = true) || region.name.contains(name, ignoreCase = true)) {
                    Log.d(TAG, "Found Rwanda at top level: ${region.name}")
                    return region
                }
            }

            // Check child regions
            region.childRegions?.let { childRegions ->
                for (childRegion in childRegions) {
                    for (name in rwandaNames) {
                        if (childRegion.name.equals(name, ignoreCase = true) || childRegion.name.contains(name, ignoreCase = true)) {
                            Log.d(TAG, "Found Rwanda in ${region.name}: ${childRegion.name}")
                            return childRegion
                        }
                    }

                    // Check sub-regions
                    childRegion.childRegions?.let { subRegions ->
                        for (subRegion in subRegions) {
                            for (name in rwandaNames) {
                                if (subRegion.name.equals(name, ignoreCase = true) || subRegion.name.contains(name, ignoreCase = true)) {
                                    Log.d(TAG, "Found Rwanda in ${childRegion.name}: ${subRegion.name}")
                                    return subRegion
                                }
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Check if a region ID corresponds to Rwanda
     */
    private fun isRwandaRegion(regionId: String): Boolean {
        // Rwanda region ID patterns (these may vary, adjust as needed)
        return regionId.contains("25726922", ignoreCase = true) ||
               regionId.contains("rwanda", ignoreCase = true) ||
               regionId.contains("rw", ignoreCase = true)
    }

    /**
     * Download Rwanda map with retry logic
     */
    private fun downloadRwandaMapWithRetry(rwandaRegion: Region) {
        if (isDownloadInProgress) {
            Log.w(TAG, "Download already in progress, skipping retry")
            return
        }

        if (downloadRetryCount >= MAX_DOWNLOAD_RETRIES) {
            Log.e(TAG, "Maximum download retries reached ($MAX_DOWNLOAD_RETRIES), proceeding without offline maps")
            onError("Maximum download retries reached. Please check your internet connection.")
            onToastMessage?.invoke("Map download failed after $MAX_DOWNLOAD_RETRIES attempts")
            return
        }

        downloadRetryCount++
        Log.d(TAG, "Starting Rwanda map download (attempt $downloadRetryCount/$MAX_DOWNLOAD_RETRIES)")
        
        val sizeInMB = (rwandaRegion.sizeOnDiskInBytes / (1024 * 1024)).toInt()
        onStatusUpdate("Downloading Rwanda map (${sizeInMB}MB) - Attempt $downloadRetryCount/$MAX_DOWNLOAD_RETRIES")
        onToastMessage?.invoke("Downloading Rwanda map (${sizeInMB}MB)...")
        
        downloadRwandaMap(rwandaRegion)
    }

    /**
     * Download Rwanda map
     */
    private fun downloadRwandaMap(rwandaRegion: Region) {
        mapDownloader?.let { downloader ->
            val sizeInMB: Int = (rwandaRegion.sizeOnDiskInBytes / (1024 * 1024)).toInt()
            Log.d(TAG, "=== DOWNLOADING RWANDA MAP ===")
            Log.d(TAG, "Region: ${rwandaRegion.name}")
            Log.d(TAG, "Size: ${sizeInMB}MB")
            Log.d(TAG, "Attempt: $downloadRetryCount/$MAX_DOWNLOAD_RETRIES")
            Log.d(TAG, "==============================")

            isDownloadInProgress = true
            val regionIds = listOf(rwandaRegion.regionId)
            val downloadTask = downloader.downloadRegions(regionIds, object : DownloadRegionsStatusListener {
                override fun onDownloadRegionsComplete(mapLoaderError: MapLoaderError?, regionIds: List<RegionId>?) {
                    isDownloadInProgress = false

                    if (mapLoaderError != null) {
                        Log.e(TAG, "Rwanda map download failed: $mapLoaderError")
                        handleDownloadError(mapLoaderError, rwandaRegion)
                        return
                    }

                    if (regionIds != null) {
                        Log.d(TAG, "=== DOWNLOAD COMPLETED ===")
                        Log.d(TAG, "Successfully downloaded Rwanda map!")
                        Log.d(TAG, "Downloaded regions: ${regionIds.map { it.id }}")
                        Log.d(TAG, "==========================")

                        downloadRetryCount = 0
                        isMapDataReady = true
                        onStatusUpdate("Rwanda map downloaded successfully!")
                        onToastMessage?.invoke("Rwanda map downloaded successfully!")
                        onDownloadComplete()
                    }
                }

                override fun onProgress(regionId: RegionId, percentage: Int) {
                    Log.d(TAG, "Downloading Rwanda map: ${percentage}% (Region: ${regionId.id})")
                    mainHandler.post {
                        onProgressUpdate("Downloading Rwanda map...", percentage, sizeInMB)
                    }
                }

                override fun onPause(mapLoaderError: MapLoaderError?) {
                    if (mapLoaderError == null) {
                        Log.d(TAG, "Rwanda map download paused by user")
                        onStatusUpdate("Download paused")
                    } else {
                        Log.e(TAG, "Rwanda map download paused due to error: $mapLoaderError")
                        handleDownloadError(mapLoaderError, rwandaRegion)
                    }
                }

                override fun onResume() {
                    Log.d(TAG, "Rwanda map download resumed")
                    onStatusUpdate("Download resumed")
                }
            })

            mapDownloaderTasks.add(downloadTask)
        }
    }

    /**
     * Handle download errors with appropriate retry logic
     */
    private fun handleDownloadError(error: MapLoaderError, rwandaRegion: Region) {
        Log.e(TAG, "=== DOWNLOAD ERROR HANDLING ===")
        Log.e(TAG, "Error: $error")
        Log.e(TAG, "Retry count: $downloadRetryCount/$MAX_DOWNLOAD_RETRIES")

        when (error) {
            MapLoaderError.NETWORK_CONNECTION_ERROR -> {
                Log.w(TAG, "Network error detected, will retry")
                onStatusUpdate("Network error, retrying in ${DOWNLOAD_RETRY_DELAY_MS/1000} seconds...")
                scheduleRetryWithDelay(rwandaRegion)
            }
            MapLoaderError.NOT_ENOUGH_SPACE -> {
                Log.e(TAG, "Insufficient storage for map download")
                onError("Insufficient storage space for map download")
            }
            MapLoaderError.INVALID_ARGUMENT -> {
                Log.e(TAG, "Invalid parameters for download")
                onError("Invalid download parameters")
            }
            MapLoaderError.INTERNAL_ERROR, MapLoaderError.NOT_READY -> {
                Log.w(TAG, "Recoverable error, will retry")
                onStatusUpdate("Download error, retrying...")
                scheduleRetryWithDelay(rwandaRegion)
            }
            else -> {
                Log.w(TAG, "Unknown error, will retry")
                onError("Download failed: ${error.name}")
                scheduleRetryWithDelay(rwandaRegion)
            }
        }
        Log.e(TAG, "===============================")
    }

    /**
     * Schedule retry with exponential backoff
     */
    private fun scheduleRetryWithDelay(rwandaRegion: Region) {
        if (downloadRetryCount < MAX_DOWNLOAD_RETRIES) {
            val delay = DOWNLOAD_RETRY_DELAY_MS * downloadRetryCount
            Log.d(TAG, "Scheduling retry in ${delay}ms")

            mainHandler.postDelayed({
                if (!isMapDataReady) {
                    downloadRwandaMapWithRetry(rwandaRegion)
                }
            }, delay)
        } else {
            Log.e(TAG, "Maximum retries reached, proceeding without offline maps")
            onError("Maximum download retries reached. Please check your internet connection.")
        }
    }

    /**
     * Check for map updates (shows progress dialog)
     */
    fun checkForMapUpdates(installedRegion: InstalledRegion) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateCheckTime < UPDATE_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Map update check skipped - last check was ${(currentTime - lastUpdateCheckTime) / (1000 * 60 * 60)} hours ago")
            return
        }

        Log.d(TAG, "=== CHECKING FOR MAP UPDATES ===")
        onStatusUpdate("Checking for map updates...")

        mapUpdater?.let { updater ->
            updater.retrieveCatalogsUpdateInfo(object : CatalogsUpdateInfoCallback {
                override fun apply(mapLoaderError: MapLoaderError?, catalogList: MutableList<CatalogUpdateInfo>?) {
                    if (mapLoaderError != null) {
                        Log.e(TAG, "Error checking for updates: $mapLoaderError")
                        onStatusUpdate("Error checking for updates")
                        return
                    }

                    if (catalogList != null && catalogList.isNotEmpty()) {
                        Log.d(TAG, "Map updates available")
                        onStatusUpdate("Map updates available, downloading...")
                        onShowProgressDialog?.invoke() // Show progress dialog for updates
                        performMapUpdate(catalogList[0])
                    } else {
                        Log.d(TAG, "No map updates available")
                        onStatusUpdate("Map is up to date")
                    }

                    lastUpdateCheckTime = currentTime
                    Log.d(TAG, "===============================")
                }
            })
        }
    }

    /**
     * Check for map updates in background (no progress dialog, uses Toast)
     */
    private fun checkForMapUpdatesBackground(installedRegion: InstalledRegion) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateCheckTime < UPDATE_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Background map update check skipped - last check was ${(currentTime - lastUpdateCheckTime) / (1000 * 60 * 60)} hours ago")
            return
        }

        Log.d(TAG, "=== BACKGROUND CHECKING FOR MAP UPDATES ===")
        onToastMessage?.invoke("Checking for map updates...")

        mapUpdater?.let { updater ->
            updater.retrieveCatalogsUpdateInfo(object : CatalogsUpdateInfoCallback {
                override fun apply(mapLoaderError: MapLoaderError?, catalogList: MutableList<CatalogUpdateInfo>?) {
                    if (mapLoaderError != null) {
                        Log.e(TAG, "Error checking for updates: $mapLoaderError")
                        onToastMessage?.invoke("Error checking for map updates")
                        return
                    }

                    if (catalogList != null && catalogList.isNotEmpty()) {
                        Log.d(TAG, "Map updates available")
                        onToastMessage?.invoke("Map updates available, downloading...")
                        performMapUpdateBackground(catalogList[0])
                    } else {
                        Log.d(TAG, "No map updates available")
                        onToastMessage?.invoke("Maps are up to date")
                    }

                    lastUpdateCheckTime = currentTime
                    Log.d(TAG, "===============================")
                }
            })
        }
    }

    /**
     * Perform map update (shows progress dialog)
     */
    private fun performMapUpdate(catalogUpdateInfo: CatalogUpdateInfo) {
        mapUpdater?.let { updater ->
            val task = updater.updateCatalog(catalogUpdateInfo, object : CatalogUpdateProgressListener {
                override fun onProgress(regionId: RegionId, percentage: Int) {
                    Log.d(TAG, "Updating map: ${percentage}% for region ${regionId.id}")
                    mainHandler.post {
                        onProgressUpdate("Updating map...", percentage, 0)
                    }
                }

                override fun onPause(mapLoaderError: MapLoaderError?) {
                    if (mapLoaderError == null) {
                        Log.d(TAG, "Map update paused by user")
                        onStatusUpdate("Map update paused")
                    } else {
                        Log.e(TAG, "Map update paused due to error: $mapLoaderError")
                        onError("Map update failed: $mapLoaderError")
                    }
                }

                override fun onResume() {
                    Log.d(TAG, "Map update resumed")
                    onStatusUpdate("Map update resumed")
                }

                override fun onComplete(mapLoaderError: MapLoaderError?) {
                    if (mapLoaderError != null) {
                        Log.e(TAG, "Map update completion error: $mapLoaderError")
                        onError("Map update failed: $mapLoaderError")
                        return
                    }

                    Log.d(TAG, "Map update completed successfully")
                    onStatusUpdate("Map updated successfully")
                }
            })
        }
    }

    /**
     * Perform map update in background (uses Toast notifications)
     */
    private fun performMapUpdateBackground(catalogUpdateInfo: CatalogUpdateInfo) {
        mapUpdater?.let { updater ->
            val task = updater.updateCatalog(catalogUpdateInfo, object : CatalogUpdateProgressListener {
                override fun onProgress(regionId: RegionId, percentage: Int) {
                    Log.d(TAG, "Background updating map: ${percentage}% for region ${regionId.id}")
                    // Only show toast at 25%, 50%, 75%, 100% to avoid spam
                    if (percentage % 25 == 0) {
                        mainHandler.post {
                            onToastMessage?.invoke("Updating maps: ${percentage}%")
                        }
                    }
                }

                override fun onPause(mapLoaderError: MapLoaderError?) {
                    if (mapLoaderError == null) {
                        Log.d(TAG, "Background map update paused by user")
                        onToastMessage?.invoke("Map update paused")
                    } else {
                        Log.e(TAG, "Background map update paused due to error: $mapLoaderError")
                        onToastMessage?.invoke("Map update failed: $mapLoaderError")
                    }
                }

                override fun onResume() {
                    Log.d(TAG, "Background map update resumed")
                    onToastMessage?.invoke("Map update resumed")
                }

                override fun onComplete(mapLoaderError: MapLoaderError?) {
                    if (mapLoaderError != null) {
                        Log.e(TAG, "Background map update completion error: $mapLoaderError")
                        onToastMessage?.invoke("Map update failed: $mapLoaderError")
                        return
                    }

                    Log.d(TAG, "Background map update completed successfully")
                    onToastMessage?.invoke("Maps updated successfully!")
                }
            })
        }
    }

    /**
     * Cancel all ongoing downloads
     */
    fun cancelDownloads() {
        for (task in mapDownloaderTasks) {
            task.cancel()
        }
        Log.d(TAG, "Cancelled ${mapDownloaderTasks.size} download tasks")
        mapDownloaderTasks.clear()
        isDownloadInProgress = false
        onStatusUpdate("Downloads cancelled")
    }

    /**
     * Get current status information
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "isDownloadInProgress" to isDownloadInProgress,
            "isMapDataReady" to isMapDataReady,
            "downloadRetryCount" to downloadRetryCount,
            "maxRetries" to MAX_DOWNLOAD_RETRIES,
            "lastUpdateCheck" to lastUpdateCheckTime,
            "hasRwandaRegion" to (rwandaRegion != null)
        )
    }

    /**
     * Handle network availability for retrying downloads
     */
    fun onNetworkAvailable() {
        if (!isMapDataReady && rwandaRegion != null && !isDownloadInProgress) {
            Log.d(TAG, "Network available and no map data, starting download")
            downloadRwandaMapWithRetry(rwandaRegion!!)
        }
    }

    /**
     * Trigger map update manually (shows progress dialog)
     */
    fun triggerMapUpdate() {
        if (!isInitialized) {
            Log.w(TAG, "MapDownloaderManager not initialized, cannot trigger update")
            return
        }

        // Verify SDK is still valid before proceeding
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        if (sdkNativeEngine == null) {
            Log.w(TAG, "SDK not available, cannot trigger update")
            onError("SDK not available")
            return
        }

        if (isMapDataReady) {
            Log.d(TAG, "Map data is ready, checking for updates...")
            // Check for updates on existing installed regions
            mapDownloader?.let { downloader ->
                try {
                    val installedRegions = downloader.installedRegions
                    val rwandaInstalled = installedRegions.find { region ->
                        isRwandaRegion(region.regionId.id.toString())
                    }
                    
                    if (rwandaInstalled != null) {
                        checkForMapUpdates(rwandaInstalled)
                    } else {
                        Log.d(TAG, "No installed Rwanda region found for update check")
                        onStatusUpdate("No installed map data found for updates")
                    }
                } catch (e: MapLoaderException) {
                    val errorString = e.error.toString()
                    if (errorString.contains("OPERATION_AFTER_DISPOSE", ignoreCase = true) ||
                        errorString.contains("DISPOSE", ignoreCase = true)) {
                        Log.w(TAG, "SDK disposed during update check: ${e.error}")
                        onError("SDK unavailable, cannot check for updates")
                    } else {
                        Log.e(TAG, "Error checking for updates: ${e.error}", e)
                        onError("Error checking for updates: ${e.error}")
                    }
                } catch (e: Exception) {
                    val errorMessage = e.message ?: "Unknown error"
                    if (errorMessage.contains("dispose", ignoreCase = true) || 
                        errorMessage.contains("OPERATION_AFTER_DISPOSE", ignoreCase = true)) {
                        Log.w(TAG, "SDK disposed during update check")
                        onError("SDK unavailable, cannot check for updates")
                    } else {
                        Log.e(TAG, "Error checking for updates: ${e.message}", e)
                        onError("Error checking for updates: ${e.message}")
                    }
                }
            } ?: run {
                Log.w(TAG, "MapDownloader not available")
                onError("Map downloader not available")
            }
        } else {
            Log.d(TAG, "Map data not ready, starting fresh download...")
            if (rwandaRegion != null) {
                downloadRwandaMapWithRetry(rwandaRegion!!)
            } else {
                Log.d(TAG, "No Rwanda region available, fetching region list...")
                downloadRegionsList()
            }
        }
    }

    /**
     * Force download of map data (shows progress dialog)
     */
    fun forceMapDownload() {
        if (!isInitialized) {
            Log.w(TAG, "MapDownloaderManager not initialized, cannot force download")
            onToastMessage?.invoke("Map downloader not ready")
            return
        }

        Log.d(TAG, "Force downloading map data...")
        onToastMessage?.invoke("Starting map download...")
        onShowProgressDialog?.invoke() // Show progress dialog for manual download
        
        if (rwandaRegion != null) {
            downloadRwandaMapWithRetry(rwandaRegion!!)
        } else {
            Log.d(TAG, "No Rwanda region available, fetching region list...")
            downloadRegionsList()
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        cancelDownloads()
        coroutineScope.launch {
            // Cancel any ongoing coroutines
        }
    }
}
