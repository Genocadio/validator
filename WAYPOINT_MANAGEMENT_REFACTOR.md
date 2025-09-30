# Waypoint Management Refactor

## Problem Analysis

The original waypoint handling system had several critical issues causing premature waypoint removals:

### Root Causes:
1. **Multiple removal calls**: `removeMarkersForPassedWaypoints()` was called from multiple places:
   - During initial marker setup
   - During progress updates
   - This caused duplicate removal attempts

2. **Index shifting issues**: When markers were removed from the `waypointMarkers` list, indices shifted, causing subsequent waypoints to be incorrectly identified and removed.

3. **Complex tracking**: The system used multiple tracking mechanisms (`passedWaypointIds`, `removedWaypointIds`, `is_passed` flags) that could get out of sync.

4. **Section-based logic confusion**: The waypoint-to-section mapping logic was complex and error-prone.

## Solution: Centralized WaypointManager

### Key Design Principles:
1. **Immutable marker list**: All waypoint markers are created once and never removed from the list
2. **Visibility-based approach**: Instead of removing markers, we hide/show them based on passed status
3. **Single source of truth**: All waypoint operations go through the centralized `WaypointManager`
4. **No index manipulation**: Marker indices never change, eliminating index shifting issues

### Architecture:

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   App.kt        │    │ WaypointManager  │    │ NavigationHandler│
│                 │    │                  │    │                 │
│ - Trip data     │───▶│ - All markers    │◀───│ - Route progress│
│ - Overlay UI    │    │ - Passed tracking│    │ - Progress data │
│ - User actions  │    │ - Visibility mgmt│    │                 │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

### Core Components:

#### 1. WaypointManager Class
- **Purpose**: Centralized management of all waypoint operations
- **Key Features**:
  - Creates all markers once during initialization
  - Tracks passed waypoints using a simple Set
  - Manages marker visibility (show/hide instead of add/remove)
  - Provides unified waypoint overlay data
  - Handles waypoint passing detection

#### 2. Marker Management
```kotlin
data class WaypointMarkerInfo(
    val waypoint: TripWaypoint? = null, // null for origin/destination
    val marker: MapMarker,
    val type: WaypointType,
    val order: Int
)
```

- **Immutable list**: `allWaypointMarkers` never changes size
- **Visibility control**: Markers are shown/hidden based on passed status
- **Type-based handling**: Different logic for origin, waypoints, and destination

#### 3. Waypoint Passing Detection
```kotlin
fun checkAndMarkWaypointAsPassed(routeProgress: RouteProgress): Boolean
```

- Uses route progress data to determine if a waypoint is reached
- 30-meter threshold for waypoint passing
- Updates visibility immediately when waypoint is passed

#### 4. Progress Updates
```kotlin
fun updateWaypointMarkersWithProgress(routeProgress: RouteProgress)
```

- Updates marker text with real-time progress information
- Only updates unpassed waypoints
- Uses section progress data for accurate distance/time calculations

### Benefits:

1. **No Premature Removals**: Markers are never removed, only hidden
2. **Consistent State**: Single source of truth for waypoint status
3. **Simplified Logic**: No complex index calculations or tracking
4. **Better Performance**: No repeated marker creation/removal
5. **Easier Debugging**: Clear separation of concerns

### Migration Changes:

#### App.kt Changes:
- Removed all waypoint marker management code
- Delegated to `WaypointManager`
- Simplified overlay data creation
- Made `waypointManager` accessible for external use

#### NavigationHandler.kt Changes:
- Removed waypoint passing detection logic
- Delegated to `WaypointManager.checkAndMarkWaypointAsPassed()`
- Simplified progress update flow
- Added route progress storage for external access

#### NavigationExample.kt Changes:
- Added `getCurrentRouteProgress()` method
- Delegated to `NavigationHandler` for route progress access

### Usage Flow:

1. **Initialization**:
   ```kotlin
   waypointManager.initialize(trip, route)
   ```

2. **Progress Updates**:
   ```kotlin
   val waypointPassed = waypointManager.checkAndMarkWaypointAsPassed(routeProgress)
   waypointManager.updateWaypointMarkersWithProgress(routeProgress)
   ```

3. **Overlay Data**:
   ```kotlin
   val overlayData = waypointManager.getWaypointOverlayData(routeProgress)
   ```

### Testing:

The new system should be tested with:
- Multiple waypoints (4+ waypoints)
- Already passed waypoints in database
- Real-time waypoint passing
- Overlay display/hide functionality
- Route recalculation scenarios

### Future Enhancements:

1. **Database Integration**: Persist waypoint passing status to database
2. **Custom Thresholds**: Configurable waypoint passing distances
3. **Visual Feedback**: Different marker styles for passed waypoints
4. **Analytics**: Track waypoint passing patterns
5. **Offline Support**: Handle waypoint passing without network

## Conclusion

The centralized `WaypointManager` eliminates the complex, error-prone waypoint handling system and provides a clean, maintainable solution that prevents premature waypoint removals while maintaining all required functionality.
