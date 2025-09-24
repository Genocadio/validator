package com.gocavgo.validator

import android.os.Handler
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

class MapDataManager(
    private val handler: Handler,
    private val isNetworkConnected: () -> Boolean
) {

    companion object {
        private const val TAG = "MapDataManager"
        private const val MAX_DOWNLOAD_RETRIES = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 5000L
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    // HERE SDK objects
    private var mapDownloader: MapDownloader? = null

    // State
    private var downloadableRegions = mutableListOf<Region>()
    private val mapDownloaderTasks = mutableListOf<MapDownloaderTask>()
    private var rwandaRegion: Region? = null
    private var downloadRetryCount = 0
    private var lastUpdateCheckTime = 0L
    private var isDownloadInProgress = false

    // Exposed state
    var isMapDataReady: Boolean = false
        private set

    // Callbacks
    private var downloadProgressCallback: ((Int) -> Unit)? = null
    private var onReadyCallback: (() -> Unit)? = null
    private var onShowDialog: (() -> Unit)? = null
    private var onUpdateProgress: ((Int, Int) -> Unit)? = null
    private var onShowError: ((String) -> Unit)? = null
    private var onShowSuccess: (() -> Unit)? = null
    private var onShowRetrying: ((Int, Int) -> Unit)? = null
    private var onShowNetworkWaiting: (() -> Unit)? = null

    fun start(onReady: () -> Unit) {
        onReadyCallback = onReady

        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
            ?: throw RuntimeException("SDKNativeEngine not initialized.")

        MapDownloader.fromEngineAsync(sdkNativeEngine) { mapDownloader ->
            this@MapDataManager.mapDownloader = mapDownloader
            Log.d(TAG, "MapDownloader initialized successfully")

            checkExistingMapData()

            if (!isMapDataReady) {
                downloadRegionsList()
            }
        }
    }

    fun stop() {
        cancelDownloads()
        downloadProgressCallback = null
        onReadyCallback = null
    }

    fun setProgressCallback(callback: (Int) -> Unit) {
        downloadProgressCallback = callback
    }
    
    fun setDialogCallbacks(
        onShowDialog: () -> Unit,
        onUpdateProgress: (Int, Int) -> Unit,
        onShowError: (String) -> Unit,
        onShowSuccess: () -> Unit,
        onShowRetrying: (Int, Int) -> Unit,
        onShowNetworkWaiting: () -> Unit
    ) {
        this.onShowDialog = onShowDialog
        this.onUpdateProgress = onUpdateProgress
        this.onShowError = onShowError
        this.onShowSuccess = onShowSuccess
        this.onShowRetrying = onShowRetrying
        this.onShowNetworkWaiting = onShowNetworkWaiting
    }

    fun triggerMapUpdate() {
        rwandaRegion?.let { region ->
            Log.d(TAG, "Manually triggering map update download")
            downloadRetryCount = 0
            downloadRwandaMapWithRetry(region)
        } ?: run {
            Log.w(TAG, "No updated region available for download")
        }
    }

    fun getStatus(): Map<String, Any> {
        return mapOf(
            "isDownloadInProgress" to isDownloadInProgress,
            "isMapDataReady" to isMapDataReady,
            "downloadRetryCount" to downloadRetryCount,
            "maxRetries" to MAX_DOWNLOAD_RETRIES,
            "lastUpdateCheck" to lastUpdateCheckTime,
            "hasRwandaRegion" to (rwandaRegion != null)
        )
    }

    fun cancelDownloads() {
        for (task in mapDownloaderTasks) {
            task.cancel()
        }
        Log.d(TAG, "Cancelled ${mapDownloaderTasks.size} download tasks")
        mapDownloaderTasks.clear()
        isDownloadInProgress = false
    }

    fun onNetworkAvailable() {
        if (!isNetworkConnected()) {
            Log.d(TAG, "Network not available, skipping map update check")
            return
        }

        if (!isMapDataReady && rwandaRegion != null && !isDownloadInProgress) {
            Log.d(TAG, "Network available and no map data, starting download")
            downloadRwandaMapWithRetry(rwandaRegion!!)
            return
        }

        mapDownloader?.let { downloader ->
            try {
                val installedRegions = downloader.installedRegions
                val rwandaInstalled = installedRegions.find { region ->
                    region.regionId.id.toString().contains("25726922", ignoreCase = true)
                }

                if (rwandaInstalled != null) {
                    Log.d(TAG, "Found installed Rwanda region, checking for updates")
                    checkForMapUpdates(rwandaInstalled)
                } else {
                    Log.d(TAG, "No installed Rwanda region found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking installed regions on network available: ${e.message}", e)
            }
        }
    }

    // Expose manual update check with installed region
    fun checkForMapUpdates(installedRegion: com.here.sdk.maploader.InstalledRegion) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateCheckTime < UPDATE_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Map update check skipped - last check was ${(currentTime - lastUpdateCheckTime) / (1000 * 60 * 60)} hours ago")
            return
        }

        Log.d(TAG, "=== CHECKING FOR MAP UPDATES ===")
        Log.d(TAG, "Checking updates for region: ${installedRegion.regionId.id}")

        mapDownloader?.let { downloader ->
            try {
                downloader.getDownloadableRegions(LanguageCode.EN_US) { error, regions ->
                    if (error != null) {
                        Log.e(TAG, "Error checking for updates: $error")
                        return@getDownloadableRegions
                    }

                    regions?.let { availableRegions ->
                        val updatedRegion = findRwandaRegion(availableRegions)
                        if (updatedRegion != null) {
                            val installedSize = installedRegion.sizeOnDiskInBytes
                            val availableSize = updatedRegion.sizeOnDiskInBytes
                            val sizeDifference = kotlin.math.abs(availableSize - installedSize)
                            val sizeDifferenceMB = sizeDifference / (1024 * 1024)

                            if (sizeDifferenceMB > 10) {
                                Log.d(TAG, "Map update available! Size difference: ${sizeDifferenceMB}MB")
                                handleMapUpdateAvailable(updatedRegion, installedRegion)
                            } else {
                                Log.d(TAG, "No significant map updates available")
                            }
                        }
                    }

                    lastUpdateCheckTime = currentTime
                    Log.d(TAG, "===============================")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during map update check: ${e.message}", e)
            }
        }
    }

    private fun checkExistingMapData() {
        mapDownloader?.let { downloader ->
            try {
                val installedRegions = downloader.installedRegions
                Log.d(TAG, "=== INSTALLED REGIONS CHECK ===")
                Log.d(TAG, "Total installed regions: ${installedRegions.size}")

                var rwandaFound = false
                var rwandaRegionInstalled: com.here.sdk.maploader.InstalledRegion? = null

                for (region in installedRegions) {
                    val sizeInMB = region.sizeOnDiskInBytes / (1024 * 1024)
                    Log.d(TAG, "Installed region: ${region.regionId.id}, Size: ${sizeInMB}MB, Status: ${region.status}")

                    if (region.regionId.id.toString().contains("25726922", ignoreCase = true)) {
                        rwandaFound = true
                        rwandaRegionInstalled = region
                        Log.d(TAG, "Found existing Rwanda map data!")
                    }
                }

                if (rwandaFound && rwandaRegionInstalled != null) {
                    Log.d(TAG, "Rwanda map data already available")
                    checkForMapUpdates(rwandaRegionInstalled)
                    isMapDataReady = true
                    onReadyCallback?.invoke()
                } else {
                    Log.d(TAG, "No Rwanda map data found, will download after region list")
                    onShowDialog?.invoke()
                }

                Log.d(TAG, "==============================")
            } catch (e: MapLoaderException) {
                Log.e(TAG, "Error checking installed regions: ${e.error}")
                proceedWithMapDownload()
            }
        }
    }

    private fun downloadRegionsList() {
        mapDownloader?.let { downloader ->
            Log.d(TAG, "Downloading list of available regions...")

            downloader.getDownloadableRegions(LanguageCode.EN_US, object : DownloadableRegionsCallback {
                override fun onCompleted(mapLoaderError: MapLoaderError?, regions: MutableList<Region>?) {
                    if (mapLoaderError != null) {
                        Log.e(TAG, "Error downloading regions list: $mapLoaderError")
                        return
                    }

                    if (regions != null) {
                        downloadableRegions = regions
                        Log.d(TAG, "Found ${regions.size} top-level regions")

                        val rwandaRegion = findRwandaRegion(regions)
                        if (rwandaRegion != null) {
                            Log.d(TAG, "Found Rwanda region!")
                            this@MapDataManager.rwandaRegion = rwandaRegion
                            if (!isMapDataReady) {
                                downloadRwandaMapWithRetry(rwandaRegion)
                            } else {
                                onReadyCallback?.invoke()
                            }
                        } else {
                            Log.e(TAG, "Rwanda region not found in available regions!")
                            onReadyCallback?.invoke()
                        }
                    }
                }
            })
        }
    }

    private fun findRwandaRegion(regions: List<Region>): Region? {
        val rwandaNames = listOf(
            "Rwanda", "RWANDA", "rwanda",
            "Republic of Rwanda", "République du Rwanda",
            "U Rwanda"
        )

        for (region in regions) {
            for (name in rwandaNames) {
                if (region.name.equals(name, ignoreCase = true) || region.name.contains(name, ignoreCase = true)) {
                    Log.d(TAG, "Found Rwanda at top level: ${region.name}")
                    return region
                }
            }

            region.childRegions?.let { childRegions ->
                for (childRegion in childRegions) {
                    for (name in rwandaNames) {
                        if (childRegion.name.equals(name, ignoreCase = true) || childRegion.name.contains(name, ignoreCase = true)) {
                            Log.d(TAG, "Found Rwanda in ${region.name}: ${childRegion.name}")
                            return childRegion
                        }
                    }

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

    private fun downloadRwandaMapWithRetry(rwandaRegion: Region) {
        if (isDownloadInProgress) {
            Log.w(TAG, "Download already in progress, skipping retry")
            return
        }

        if (downloadRetryCount >= MAX_DOWNLOAD_RETRIES) {
            Log.e(TAG, "Maximum download retries reached ($MAX_DOWNLOAD_RETRIES), proceeding without offline maps")
            onReadyCallback?.invoke()
            return
        }

        downloadRetryCount++
        Log.d(TAG, "Starting Rwanda map download (attempt $downloadRetryCount/$MAX_DOWNLOAD_RETRIES)")
        
        if (downloadRetryCount > 1) {
            onShowRetrying?.invoke(downloadRetryCount, MAX_DOWNLOAD_RETRIES)
        } else {
            onUpdateProgress?.invoke(0, (rwandaRegion.sizeOnDiskInBytes / (1024 * 1024)).toInt())
        }
        
        downloadRwandaMap(rwandaRegion)
    }

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
                        onShowSuccess?.invoke()
                        onReadyCallback?.invoke()
                    }
                }

                override fun onProgress(regionId: RegionId, percentage: Int) {
                    Log.d(TAG, "Downloading Rwanda map: ${percentage}% (Region: ${regionId.id})")
                    downloadProgressCallback?.invoke(percentage)
                    onUpdateProgress?.invoke(percentage, sizeInMB)
                }

                override fun onPause(mapLoaderError: MapLoaderError?) {
                    if (mapLoaderError == null) {
                        Log.d(TAG, "Rwanda map download paused by user")
                    } else {
                        Log.e(TAG, "Rwanda map download paused due to error: $mapLoaderError")
                        handleDownloadError(mapLoaderError, rwandaRegion)
                    }
                }

                override fun onResume() {
                    Log.d(TAG, "Rwanda map download resumed")
                }
            })

            mapDownloaderTasks.add(downloadTask)
        }
    }

    private fun handleDownloadError(error: MapLoaderError, rwandaRegion: Region) {
        Log.e(TAG, "=== DOWNLOAD ERROR HANDLING ===")
        Log.e(TAG, "Error: $error")
        Log.e(TAG, "Retry count: $downloadRetryCount/$MAX_DOWNLOAD_RETRIES")

        when (error) {
            MapLoaderError.NETWORK_CONNECTION_ERROR -> {
                Log.w(TAG, "Network error detected, will retry when network is available")
                onShowNetworkWaiting?.invoke()
                scheduleRetryOnNetworkAvailable(rwandaRegion)
            }
            MapLoaderError.NOT_ENOUGH_SPACE -> {
                Log.e(TAG, "Insufficient storage for map download")
                onShowError?.invoke("Insufficient storage space for map download")
            }
            MapLoaderError.INVALID_ARGUMENT -> {
                Log.e(TAG, "Invalid parameters for download")
                onShowError?.invoke("Invalid download parameters")
            }
            MapLoaderError.INTERNAL_ERROR, MapLoaderError.NOT_READY -> {
                Log.w(TAG, "Recoverable error, will retry")
                scheduleRetryWithDelay(rwandaRegion)
            }
            else -> {
                Log.w(TAG, "Unknown error, will retry")
                onShowError?.invoke("Download failed: ${error.name}")
                scheduleRetryWithDelay(rwandaRegion)
            }
        }
        Log.e(TAG, "===============================")
    }

    private fun scheduleRetryWithDelay(rwandaRegion: Region) {
        if (downloadRetryCount < MAX_DOWNLOAD_RETRIES) {
            val delay = DOWNLOAD_RETRY_DELAY_MS * downloadRetryCount
            Log.d(TAG, "Scheduling retry in ${delay}ms")

            handler.postDelayed({
                if (!isMapDataReady) {
                    downloadRwandaMapWithRetry(rwandaRegion)
                }
            }, delay)
        } else {
            Log.e(TAG, "Maximum retries reached, proceeding without offline maps")
            onReadyCallback?.invoke()
        }
    }

    private fun scheduleRetryOnNetworkAvailable(rwandaRegion: Region) {
        val networkCheckRunnable = object : Runnable {
            override fun run() {
                if (isNetworkConnected() && !isMapDataReady) {
                    Log.d(TAG, "Network available, retrying download")
                    downloadRwandaMapWithRetry(rwandaRegion)
                } else if (!isMapDataReady) {
                    handler.postDelayed(this, 10000)
                }
            }
        }
        handler.postDelayed(networkCheckRunnable, 5000)
    }

    private fun proceedWithMapDownload() {
        rwandaRegion?.let { region ->
            if (!isMapDataReady && !isDownloadInProgress) {
                Log.d(TAG, "Proceeding with map download as fallback")
                downloadRwandaMapWithRetry(region)
            }
        } ?: run {
            Log.w(TAG, "No Rwanda region available for download")
            onReadyCallback?.invoke()
        }
    }

    

    private fun handleMapUpdateAvailable(updatedRegion: Region, installedRegion: com.here.sdk.maploader.InstalledRegion) {
        Log.d(TAG, "=== MAP UPDATE AVAILABLE ===")
        Log.d(TAG, "Current version size: ${installedRegion.sizeOnDiskInBytes / (1024 * 1024)}MB")
        Log.d(TAG, "Updated version size: ${updatedRegion.sizeOnDiskInBytes / (1024 * 1024)}MB")

        rwandaRegion = updatedRegion
        Log.d(TAG, "Map update will be downloaded on next app restart or manual trigger")
        Log.d(TAG, "=============================")
    }
}


