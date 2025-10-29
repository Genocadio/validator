# HERE SDK ServiceConnection Leak Fix

## Issue Summary

The app was experiencing `ServiceConnectionLeaked` errors when `MainActivity` was destroyed. The Android system reported that three HERE SDK internal service connections were leaked:

1. `com.here.services.internal.CommonServiceController$1`
2. `com.here.services.location.internal.PositioningClient$Connection`
3. `com.here.services.positioning.auth.internal.AuthClient$Connection`

## Root Cause

The leak was caused by a **context lifecycle mismatch** between `MainActivity` and `MqttForegroundService`:

### The Problem Flow:

1. **MainActivity starts and initializes HERE SDK** using its own context
2. **MqttForegroundService starts in onCreate()** and creates a `LocationEngine`
3. The `LocationEngine.start()` internally binds to Android location services, creating `ServiceConnection` objects
4. **MainActivity is destroyed** and calls `disposeHERESDK()` in its `onDestroy()`
5. The HERE SDK is disposed **while MqttForegroundService's LocationEngine is still running**
6. The internal service connections are orphaned, and Android reports them as leaked from MainActivity

### Why This Happens:

- HERE SDK's `LocationEngine` creates internal Android `ServiceConnection` objects when started
- These connections are tied to the SDK instance
- When the SDK is disposed while a `LocationEngine` is still active, the connections are not properly cleaned up
- Android attributes these leaked connections to MainActivity because that's where the SDK was initialized

## The Fix

### 1. Modified MainActivity.onDestroy() 

**File:** `app/src/main/java/com/gocavgo/validator/MainActivity.kt`

**Changes:**
- Stop `MqttForegroundService` **before** disposing HERE SDK
- Added a 500ms grace period to allow the service to fully stop its LocationEngine
- Ensures proper cleanup order: Service → LocationEngine → HERE SDK

```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    // ... existing cleanup code ...
    
    // Stop MQTT background services BEFORE disposing HERE SDK
    // This ensures LocationEngine is stopped before SDK disposal
    stopMqttForegroundService()
    
    // Give the service time to stop LocationEngine cleanly
    try {
        Thread.sleep(500) // 500ms grace period
    } catch (e: InterruptedException) {
        Logging.w(TAG, "Interrupted while waiting for service cleanup")
    }
    
    MqttHealthCheckWorker.cancel(this)
    
    // Dispose HERE SDK only after service cleanup
    disposeHERESDK()
}
```

### 2. Enhanced MqttForegroundService.stopBackgroundLocationTracking()

**File:** `app/src/main/java/com/gocavgo/validator/service/MqttForegroundService.kt`

**Changes:**
- More robust error handling for each cleanup step
- Better logging to track LocationEngine lifecycle
- Ensures all listeners are removed even if one fails

```kotlin
private fun stopBackgroundLocationTracking() {
    try {
        Log.d(TAG, "🌍 Stopping background location tracking...")
        
        // Remove listeners first (with individual error handling)
        locationEngine?.let { engine ->
            try {
                engine.removeLocationListener(locationListener)
            } catch (e: Exception) {
                Log.e(TAG, "🌍❌ Error removing location listener: ${e.message}", e)
            }
            
            try {
                engine.removeLocationStatusListener(locationStatusListener)
            } catch (e: Exception) {
                Log.e(TAG, "🌍❌ Error removing location status listener: ${e.message}", e)
            }
            
            try {
                engine.stop()
            } catch (e: Exception) {
                Log.e(TAG, "🌍❌ Error stopping location engine: ${e.message}", e)
            }
        }
        
        isLocationTrackingActive.set(false)
    } catch (e: Exception) {
        Log.e(TAG, "🌍❌ Failed to stop background location tracking: ${e.message}", e)
    }
}
```

### 3. Enhanced MqttForegroundService.onDestroy()

**Changes:**
- Added explicit LocationEngine cleanup at the start of onDestroy()
- Added 200ms wait period to ensure LocationEngine fully stops
- Clear logging for tracking service destruction lifecycle

```kotlin
override fun onDestroy() {
    Log.d(TAG, "=== MqttForegroundService DESTROY STARTED ===")
    
    // Stop background location tracking FIRST
    // This is critical to prevent service connection leaks
    try {
        Log.d(TAG, "Stopping background location tracking before service destruction...")
        stopBackgroundLocationTracking()
        
        // Wait a bit for LocationEngine to fully stop
        Thread.sleep(200)
        
        // Null out the reference
        locationEngine = null
        Log.d(TAG, "LocationEngine stopped and nulled")
    } catch (e: Exception) {
        Log.e(TAG, "Error stopping LocationEngine during service destruction: ${e.message}", e)
    }
    
    // ... rest of cleanup ...
}
```

## Testing

To verify the fix:

1. Start the app (MainActivity initializes HERE SDK)
2. MqttForegroundService starts and creates LocationEngine
3. Close the app (MainActivity.onDestroy() is called)
4. Check logcat - you should see:
   - "Stopping MQTT foreground service"
   - "🌍 Stopping background location tracking..."
   - "🌍 Location engine stopped"
   - "=== MqttForegroundService DESTROY COMPLETE ==="
   - "HERE SDK disposed in MainActivity"
5. **No ServiceConnectionLeaked errors should appear**

## Why This Fix Works

1. **Proper cleanup order**: Service stops its LocationEngine before SDK disposal
2. **Grace periods**: Small delays ensure async cleanup completes
3. **Robust error handling**: Each cleanup step has try-catch to prevent one failure from blocking others
4. **Better logging**: Detailed logs help debug any future issues
5. **Resource nullification**: Explicitly null out references after cleanup

## Best Practices for Future Development

When using HERE SDK LocationEngine with background services:

1. **Always stop LocationEngine before disposing SDK**
2. **Service lifecycle should be independent of activity lifecycle**
3. **Add grace periods for async cleanup operations**
4. **Use detailed logging for tracking resource lifecycle**
5. **Test activity destruction while services are running**

## Alternative Approaches Considered

### Option 1: Don't dispose HERE SDK in MainActivity
- **Pros**: Allows background service to continue using SDK
- **Cons**: SDK remains initialized even when not needed, memory overhead

### Option 2: Move HERE SDK initialization to Application class
- **Pros**: Single SDK instance for entire app lifecycle
- **Cons**: More complex lifecycle management, harder to debug

### Option 3: Use separate SDK instances for Activity and Service
- **Pros**: Complete isolation between contexts
- **Cons**: Higher memory usage, license issues with multiple SDK instances

**Chosen approach** (stopping service before SDK disposal) balances simplicity, correctness, and resource efficiency.

