# Vehicle Location Tracking Implementation

## Summary

Successfully implemented vehicle location tracking from `navigableLocationListener` in both visual and headless navigation modes. Location data (coordinates, speed, accuracy, bearing) is now stored in a dedicated `VehicleLocationEntity` and also updates the Trip's vehicle info so MQTT always has the latest location data.

## Changes Made

### 1. Database Layer (NEW)

#### VehicleLocationEntity.kt
- Created new entity to store latest location per vehicle
- Fields: `vehicleId` (PK), `latitude`, `longitude`, `speed`, `accuracy`, `bearing`, `timestamp`
- Only keeps the most recent location per vehicle (update-on-insert with primary key)

#### VehicleLocationDao.kt
- Created DAO with upsert operation (insert or replace)
- Query methods: `getVehicleLocation()`, `getAllVehicleLocations()`
- Delete methods for cleanup

### 2. Database Configuration

#### AppDatabase.kt
- Added `VehicleLocationEntity` to entities list
- Added `abstract fun vehicleLocationDao(): VehicleLocationDao`
- Incremented database version from 7 to 8

### 3. Repository Layer

#### TripRepository.kt
- Modified `updateVehicleCurrentLocation()` signature:
  - Added `accuracy: Double` parameter
  - Added `bearing: Double?` parameter
- Stores in both places:
  1. VehicleLocationEntity (with bearing and accuracy)
  2. Trip's vehicle info (for MQTT - lat, lng, speed, timestamp)
- Added `vehicleLocationDao` member

#### DatabaseManager.kt
- Updated `updateVehicleCurrentLocation()` signature to accept new parameters
- Passes through to repository

### 4. Navigation Integration

#### NavigationHandler.kt (Visual Navigation)
- Modified `navigableLocationListener` to extract location data
- Extracts: `lat`, `lng`, `speed`, `accuracy`, `bearing` from `mapMatchedLocation`
- Calls `tripSectionValidator.updateLocationData()` to cache location
- Database update happens from TripSectionValidator at waypoint progress frequency

#### HeadlessNavigActivity.kt (Headless Navigation)
- Modified `setupHeadlessListeners()` → `navigableLocationListener`
- Extracts same data: `lat`, `lng`, `speed`, `accuracy`, `bearing`
- Calls `tripSectionValidator.updateLocationData()` to cache location
- Same update frequency as visual mode

### 5. Trip Section Validator Integration

#### TripSectionValidator.kt
- Added member variables for latest location data:
  - `latestLat`, `latestLng`, `latestSpeed`, `latestAccuracy`, `latestBearing`
- Added `updateLocationData()` method to cache location from listener
- Modified `writeProgressToDatabase()` method:
  - After writing waypoint data, calls `databaseManager.updateVehicleCurrentLocation()`
  - Ensures location update happens at same frequency as waypoint progress writes
  - Updates both VehicleLocationEntity and Trip's vehicle info

## Architecture

### Update Frequency
- Location data updates happen at the **same frequency as waypoint progress writes**
- Not on every location update from HERE SDK (would be too frequent, ~1-5 seconds)
- Instead, cached and written when `writeProgressToDatabase()` is called
- This is typically during route progress updates (5-10 second intervals)

### Data Flow

```
NavigableLocationListener (every 1-5 sec)
    ↓ extracts: lat, lng, speed, accuracy, bearing
    ↓ caches in TripSectionValidator member variables
    ↓ waits for writeProgressToDatabase() to be called
writeProgressToDatabase() (every 5-10 sec)
    ↓ writes waypoint progress data
    ↓ then updates vehicle location:
    ↓   1. VehicleLocationEntity (with bearing, accuracy)
    ↓   2. Trip's vehicle info (for MQTT)
MQTT Publish
    ↓ reads from database
    ↓ always gets latest vehicle location with trip data
```

### Data Storage

#### VehicleLocationEntity
- Stores: `vehicleId`, `latitude`, `longitude`, `speed`, `accuracy`, `bearing`, `timestamp`
- Latest record only (update-on-insert)
- Can be queried independently

#### Trip's Vehicle Info
- Updates: `current_latitude`, `current_longitude`, `current_speed`, `last_location_update`
- Used by MQTT messages to always include latest location
- Embedded in TripResponse for consistency

## Testing Recommendations

1. **Visual Navigation**: Verify location updates in `NavigActivity` logs
2. **Headless Navigation**: Verify location updates in `HeadlessNavigActivity` logs
3. **Database**: Check `VehicleLocationEntity` table for latest location per vehicle
4. **MQTT**: Verify MQTT messages include latest vehicle location
5. **Trip Data**: Verify Trip's vehicle info always has current lat/lng/speed

## Notes

- Bearing is stored in `VehicleLocationEntity` but NOT in Trip's vehicle info (per requirements)
- Location updates only trigger when waypoint progress is written (not on every GPS update)
- Both visual and headless modes now capture and store location data identically
- MQTT always gets the latest location because it reads from database before publishing

