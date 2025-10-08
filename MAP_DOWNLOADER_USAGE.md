# Map Downloader Manager Usage

## Overview

The `MapDownloaderManager` class provides automatic download and management of Rwanda map data for offline navigation using the HERE SDK. It's designed to work seamlessly with your MainActivity and provides a user-friendly progress dialog.

## Features

- **Automatic Detection**: Checks if Rwanda map data is already installed
- **Smart Download**: Downloads Rwanda map data if not present
- **Progress Tracking**: Real-time progress updates with size information
- **Error Handling**: Robust retry logic with exponential backoff
- **Network Awareness**: Automatically retries downloads when network becomes available
- **Update Checking**: Periodically checks for map updates (every 24 hours)
- **User Control**: Allows users to cancel ongoing downloads

## Integration

### 1. Initialize in MainActivity

```kotlin
private var mapDownloaderManager: MapDownloaderManager? = null
private var mapDownloadProgress by mutableStateOf(0)
private var mapDownloadTotalSize by mutableStateOf(0)
private var mapDownloadMessage by mutableStateOf("")
private var mapDownloadStatus by mutableStateOf("")
private var showMapDownloadDialog by mutableStateOf(false)
private var isMapDataReady by mutableStateOf(false)

private fun initializeMapDownloader() {
    mapDownloaderManager = MapDownloaderManager(
        context = this,
        onProgressUpdate = { message, progress, totalSizeMB ->
            mapDownloadMessage = message
            mapDownloadProgress = progress
            mapDownloadTotalSize = totalSizeMB
        },
        onStatusUpdate = { status ->
            mapDownloadStatus = status
        },
        onDownloadComplete = {
            isMapDataReady = true
            showMapDownloadDialog = false
        },
        onError = { error ->
            showMapDownloadDialog = false
        }
    )
    
    try {
        mapDownloaderManager?.initialize()
        showMapDownloadDialog = true
    } catch (e: Exception) {
        isMapDataReady = true // Proceed without offline maps
    }
}
```

### 2. Add UI Components

```kotlin
// In your Compose UI
if (showMapDownloadDialog) {
    MapDownloadDialog(
        progress = mapDownloadProgress,
        totalSize = mapDownloadTotalSize,
        message = mapDownloadMessage,
        status = mapDownloadStatus,
        onCancel = {
            mapDownloaderManager?.cancelDownloads()
            showMapDownloadDialog = false
        }
    )
}
```

### 3. Network Integration

```kotlin
// In your network monitoring callback
networkMonitor = NetworkMonitor(this) { connected, type, metered ->
    // ... existing code ...
    
    // Notify map downloader of network availability
    if (connected) {
        mapDownloaderManager?.onNetworkAvailable()
    }
}
```

### 4. Cleanup

```kotlin
override fun onDestroy() {
    super.onDestroy()
    // ... existing cleanup ...
    
    // Clean up map downloader
    mapDownloaderManager?.cleanup()
}
```

## How It Works

1. **Initialization**: When `initialize()` is called, the manager checks for existing Rwanda map data
2. **Download Check**: If no map data is found, it fetches the list of available regions
3. **Region Detection**: Searches for Rwanda in the region hierarchy (top-level, child, and sub-regions)
4. **Download Process**: Downloads the Rwanda region with progress tracking
5. **Error Handling**: Implements retry logic with exponential backoff for network errors
6. **Update Checking**: Periodically checks for map updates (every 24 hours)

## Configuration

### Retry Settings
- **Max Retries**: 3 attempts
- **Retry Delay**: 5 seconds with exponential backoff
- **Update Check Interval**: 24 hours

### Region Detection
The manager searches for Rwanda using these name variations:
- "Rwanda", "RWANDA", "rwanda"
- "Republic of Rwanda"
- "République du Rwanda"
- "U Rwanda"

### Size Information
- Progress is shown as percentage (0-100%)
- Total size is displayed in MB
- Real-time progress updates during download

## Error Handling

The manager handles various error scenarios:

- **Network Errors**: Automatic retry when network becomes available
- **Storage Errors**: Clear error message for insufficient space
- **Invalid Parameters**: Error logging and user notification
- **Internal Errors**: Retry with exponential backoff
- **Unknown Errors**: Generic error handling with retry

## User Experience

- **Non-blocking**: App continues to work while map downloads in background
- **Progress Visibility**: Clear progress indication with percentage and size
- **Cancellation**: Users can cancel downloads if needed
- **Status Updates**: Real-time status messages keep users informed
- **Error Recovery**: Automatic retry on network restoration

## Dependencies

- HERE SDK (already integrated in your project)
- Android Compose UI
- Kotlin Coroutines
- Network monitoring (your existing NetworkMonitor)

## Notes

- Map downloads can be several hundred MB, so ensure users have sufficient storage
- Downloads may take several minutes depending on internet speed
- The manager automatically handles HERE SDK initialization requirements
- Map data persists between app sessions
- Updates are checked automatically but can be triggered manually

## Troubleshooting

### Common Issues

1. **Download Fails**: Check internet connection and storage space
2. **Region Not Found**: Verify HERE SDK credentials and region availability
3. **Progress Stuck**: Check network connectivity and restart if needed
4. **Large Downloads**: Ensure device has sufficient storage space

### Debug Information

The manager provides extensive logging with the tag "MapDownloaderManager" for troubleshooting.



