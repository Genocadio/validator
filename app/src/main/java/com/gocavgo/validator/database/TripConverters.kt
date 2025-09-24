package com.gocavgo.validator.database

import androidx.room.TypeConverter
import com.gocavgo.validator.dataclass.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TripConverters {
    
    @TypeConverter
    fun fromVehicleInfo(vehicleInfo: VehicleInfo): String {
        return Json.encodeToString(vehicleInfo)
    }
    
    @TypeConverter
    fun toVehicleInfo(value: String): VehicleInfo {
        return Json.decodeFromString(value)
    }
    
    @TypeConverter
    fun fromTripRoute(tripRoute: TripRoute): String {
        return Json.encodeToString(tripRoute)
    }
    
    @TypeConverter
    fun toTripRoute(value: String): TripRoute {
        return Json.decodeFromString(value)
    }
    
    @TypeConverter
    fun fromTripWaypointList(waypoints: List<TripWaypoint>): String {
        return Json.encodeToString(waypoints)
    }
    
    @TypeConverter
    fun toTripWaypointList(value: String): List<TripWaypoint> {
        return Json.decodeFromString(value)
    }
    
    @TypeConverter
    fun fromSavePlaceResponse(savePlace: SavePlaceResponse): String {
        return Json.encodeToString(savePlace)
    }
    
    @TypeConverter
    fun toSavePlaceResponse(value: String): SavePlaceResponse {
        return Json.decodeFromString(value)
    }
    
    @TypeConverter
    fun fromDriverInfo(driverInfo: DriverInfo?): String? {
        return driverInfo?.let { Json.encodeToString(it) }
    }
    
    @TypeConverter
    fun toDriverInfo(value: String?): DriverInfo? {
        return value?.let { Json.decodeFromString(it) }
    }
    
    @TypeConverter
    fun fromBookingStatus(bookingStatus: com.gocavgo.validator.dataclass.BookingStatus): String {
        return bookingStatus.value
    }
    
    @TypeConverter
    fun toBookingStatus(value: String): com.gocavgo.validator.dataclass.BookingStatus {
        return com.gocavgo.validator.dataclass.BookingStatus.fromString(value)
    }
    
    @TypeConverter
    fun fromPaymentStatus(paymentStatus: com.gocavgo.validator.dataclass.PaymentStatus): String {
        return paymentStatus.value
    }
    
    @TypeConverter
    fun toPaymentStatus(value: String): com.gocavgo.validator.dataclass.PaymentStatus {
        return com.gocavgo.validator.dataclass.PaymentStatus.fromString(value)
    }
    
    @TypeConverter
    fun fromPaymentMethod(paymentMethod: com.gocavgo.validator.dataclass.PaymentMethod): String {
        return paymentMethod.value
    }
    
    @TypeConverter
    fun toPaymentMethod(value: String): com.gocavgo.validator.dataclass.PaymentMethod {
        return com.gocavgo.validator.dataclass.PaymentMethod.fromString(value)
    }
}

