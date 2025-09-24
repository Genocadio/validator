# MQTT Service Fixes and Backend Integration

## Overview
The Android MQTT service has been updated to properly work with the backend MQTT service and handle the same data structures that the backend sends and expects.

## Issues Fixed

### 1. Serialization Errors
- **Problem**: The original MQTT service was using `Map<String, Any>` which caused serialization errors because `Any` is not serializable in Kotlinx Serialization.
- **Solution**: Created proper data classes with `@Serializable` annotations that match the backend data structures.

### 2. Package Name Mismatch
- **Problem**: The MQTT service was in the wrong package (`com.nexxserve.cavgomqt.service`).
- **Solution**: Moved to the correct package (`com.gocavgo.validator.service`) and updated all imports.

### 3. Data Structure Mismatch
- **Problem**: The Android data classes didn't match the backend MQTT message formats.
- **Solution**: Created comprehensive MQTT message data classes that mirror the backend structure.

## New Data Classes

### MQTT Messages (`MqttMessages.kt`)
- `CarStatusMessage` - For online/offline status
- `PingMessage` / `PongMessage` - For heartbeat communication
- `TripAssignmentMessage` - For simple trip assignments
- `TripEventMessage` - For full trip data events
- `BookingEventMessage` - For booking events
- `BookingUpdateMessage` - For booking updates
- `BookingConfirmationMessage` - For booking confirmations

### Backend Data Structures
- `TripData` - Full trip information from backend
- `VehicleData` - Vehicle information
- `DriverData` - Driver information
- `RouteData` - Route information
- `WaypointData` - Waypoint information
- `LocationData` - Location information

## MQTT Service Features

### Connection Management
- SSL/TLS support for secure connections (port 8883)
- Authentication with username/password
- Automatic reconnection
- Last Will and Testament for offline status

### Message Handling
- **Trip Messages**: Handles both simple trip assignments and full trip events
- **Booking Messages**: Processes booking events and updates
- **Heartbeat**: Responds to ping messages with pong responses
- **Status Updates**: Sends car status (online/offline)

### Message Publishing
- `sendTripStatusUpdate()` - Send trip status updates
- `confirmBooking()` - Confirm booking actions
- `confirmTripAssignment()` - Accept/reject trip assignments
- `notifyWaypointReached()` - Notify when waypoints are reached
- `sendHeartbeatResponse()` - Send heartbeat responses

### Data Conversion
- `convertBackendTripToAndroid()` - Converts backend trip data to Android format
- Maintains compatibility with existing Android data structures

## Backend Integration

### Topics Subscribed
- `car/{carId}/trip` - Trip assignments and events
- `car/{carId}/ping` - Heartbeat requests
- `trip/+/booking` - Booking events for any trip
- `trip/+/bookings` - Booking updates for any trip

### Message Formats
The service now properly handles:
1. **Simple Trip Assignments**: Basic trip data from `MqttService.addTrip()`
2. **Full Trip Events**: Complete trip data from `MqttService.publishTrip()`
3. **Booking Events**: Booking information from `MqttService.publishBooking()`
4. **Booking Updates**: Booking status changes from `MqttService.sendBookingUpdate()`

### Expected Backend Messages
- Trip assignments with start/end locations
- Full trip data with routes, waypoints, and vehicle information
- Booking events with passenger and location details
- Booking updates with status changes
- Heartbeat ping requests

## Usage Example

```kotlin
// Initialize MQTT service
val mqttService = MqttService.getInstance(
    context = this,
    brokerHost = "your-broker.hivemq.cloud",
    brokerPort = 8883,
    carId = "your-car-id"
)

// Set connection callback
mqttService.setConnectionCallback { connected ->
    if (connected) {
        // Connected successfully
        Log.d("MQTT", "Connected to broker")
    } else {
        // Connection failed
        Log.e("MQTT", "Failed to connect")
    }
}

// Connect with credentials
mqttService.connect(
    username = "your-username",
    password = "your-password"
)

// Send trip status update
mqttService.sendTripStatusUpdate(
    tripId = "trip-123",
    status = "IN_PROGRESS",
    location = MqttService.Location(37.7749, -122.4194)
)

// Confirm booking
mqttService.confirmBooking(
    tripId = "trip-123",
    bookingId = "booking-456",
    action = "ACCEPTED"
)
```

## Error Handling

The service now properly handles:
- Serialization errors with proper data classes
- Connection timeouts with 30-second timeout
- SSL/TLS configuration errors
- Message parsing errors with try-catch blocks
- Network disconnections with automatic reconnection

## Testing

To test the MQTT service:
1. Ensure the backend MQTT service is running
2. Use the backend's test methods:
   - `addTrip()` - Send simple trip assignment
   - `publishTrip()` - Send full trip event
   - `publishBooking()` - Send booking event
   - `pingCar()` - Send heartbeat request
3. Monitor Android logs for successful message handling

## Future Enhancements

- Add message queuing for offline scenarios
- Implement message acknowledgment
- Add support for QoS 2 (exactly once delivery)
- Add message encryption
- Implement message compression for large payloads
