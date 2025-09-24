# Database Implementation for Trip Management

## Overview
This implementation introduces a Room database to store trip data locally, improving app performance and enabling offline access to trip information.

## Architecture

### 1. Database Layer
- **AppDatabase**: Main Room database class
- **TripEntity**: Database entity for storing trip data
- **TripDao**: Data Access Object for database operations
- **TripConverters**: Type converters for complex objects

### 2. Repository Layer
- **TripRepository**: Manages data operations between database and remote API
- **DatabaseManager**: Singleton wrapper for easy database access

### 3. Data Flow
1. **Smart Fetch**: Database Check → Remote API (only if needed) → Database → UI
2. **Navigation**: Trip ID → Database → Navigator Activity
3. **Status Management**: Trip status automatically updated (pending → in_progress → completed)
4. **Sync**: Remote API calls only when no active trips exist locally

## Key Changes

### MainActivity
- **Smart Trip Fetching**: Only fetches from remote API when no active trips exist
- **Trip Status Management**: Automatically updates trip status (pending → in_progress → completed)
- **Database-First Strategy**: Always checks database before making remote API calls
- **Passes only trip ID** to Navigator instead of full trip data
- **Database connection testing** on initialization
- **Force refresh option** for manual remote API updates

### Navigator
- Receives trip ID instead of full trip data
- Fetches trip details from local database
- Improved performance and reduced memory usage
- **Navigation Allowed For**: pending, scheduled, and in_progress trips

### Database Operations
- **Insert**: New trips from remote API
- **Query**: Get trips by vehicle, by ID, latest trip
- **Update**: Modify existing trip data
- **Delete**: Remove trips or clear by vehicle

## Benefits

1. **Performance**: Faster navigation startup (no need to pass large objects)
2. **Offline Access**: Trips available even without network connection
3. **Memory Efficiency**: Reduced memory usage in activities
4. **Data Persistence**: Trips survive app restarts
5. **Scalability**: Easy to add more data types in the future

## Smart Trip Fetching Strategy

### How It Works
1. **Database First**: Always check local database before making remote API calls
2. **Active Trip Priority**: If active trips exist (pending/in_progress), use them directly
3. **Conditional Remote Fetch**: Only fetch from remote API when:
   - No trips exist in database
   - All existing trips are completed/cancelled/failed
4. **Status Management**: Automatically track trip lifecycle (pending → in_progress → completed)

### Trip Status Flow
- **PENDING**: Trip is available but navigation hasn't started
- **SCHEDULED**: Trip is scheduled for future navigation
- **IN_PROGRESS**: Navigation is active (trip started)
- **COMPLETED**: Navigation finished successfully
- **CANCELLED**: Trip was cancelled
- **FAILED**: Trip encountered an error

## Usage

### Smart Trip Fetching
```kotlin
// Automatically checks database first, only fetches remote if needed
fetchLatestVehicleTrip()

// Force refresh from remote (bypasses smart logic)
forceRefreshFromRemote()
```

### Trip Status Management
```kotlin
// Update trip status
databaseManager.updateTripStatus(tripId, TripStatus.IN_PROGRESS.value)

// Check if vehicle has active trips
val hasActive = databaseManager.hasActiveTrips(vehicleId)
```

### Fetching and Storing Trips
```kotlin
// Fetch from remote and save to database
val result = databaseManager.syncTripsFromRemote(vehicleId)

// Get from database
val trip = databaseManager.getTripById(tripId)
```

### Starting Navigation
```kotlin
// Pass only trip ID
intent.putExtra(Navigator.EXTRA_TRIP_ID, tripId)
```

## Database Schema

### Trips Table
- `id`: Primary key (trip ID)
- `route_id`: Associated route ID
- `vehicle_id`: Vehicle identifier
- `vehicle`: Serialized VehicleInfo object
- `status`: Trip status
- `departure_time`: Departure timestamp
- `connection_mode`: Connection type
- `notes`: Optional trip notes
- `seats`: Available seats
- `is_reversed`: Route direction flag
- `has_custom_waypoints`: Custom waypoints flag
- `created_at`: Creation timestamp
- `updated_at`: Last update timestamp
- `route`: Serialized TripRoute object
- `waypoints`: Serialized list of TripWaypoint objects

## Dependencies Added
- `androidx.room:room-runtime:2.6.1`
- `androidx.room:room-ktx:2.6.1`
- `androidx.room:room-compiler:2.6.1` (KSP)
- `com.google.devtools.ksp:2.0.21-1.0.27`

## Future Enhancements
1. **Background Sync**: Periodic database updates
2. **Conflict Resolution**: Handle data conflicts between local and remote
3. **Data Versioning**: Track data changes and updates
4. **Cache Management**: Implement cache eviction policies
5. **Migration Support**: Handle database schema changes
