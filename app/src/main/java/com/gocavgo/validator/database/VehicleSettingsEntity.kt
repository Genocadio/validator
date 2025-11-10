package com.gocavgo.validator.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vehicle_settings")
data class VehicleSettingsEntity(
    @PrimaryKey val vehicleId: Int,
    val logout: Boolean,
    val devmode: Boolean,
    val deactivate: Boolean,
    val appmode: Boolean,
    val simulate: Boolean,
    val lastUpdated: Long // Timestamp when settings were last updated (milliseconds since epoch)
) {
    fun toVehicleSettings(id: Int = 1): com.gocavgo.validator.dataclass.VehicleSettings {
        return com.gocavgo.validator.dataclass.VehicleSettings(
            id = id,
            vehicleId = vehicleId,
            logout = logout,
            devmode = devmode,
            deactivate = deactivate,
            appmode = appmode,
            simulate = simulate
        )
    }
    
    companion object {
        fun fromVehicleSettings(
            settings: com.gocavgo.validator.dataclass.VehicleSettings,
            timestamp: Long = System.currentTimeMillis()
        ): VehicleSettingsEntity {
            return VehicleSettingsEntity(
                vehicleId = settings.vehicleId,
                logout = settings.logout,
                devmode = settings.devmode,
                deactivate = settings.deactivate,
                appmode = settings.appmode,
                simulate = settings.simulate,
                lastUpdated = timestamp
            )
        }
    }
}














