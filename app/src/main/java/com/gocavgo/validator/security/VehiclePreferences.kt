package com.gocavgo.validator.security

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

class VehiclePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveVehicleData(
        vehicleId: Long,
        companyId: Long,
        companyName: String,
        licensePlate: String
    ) {
        val currentTime = System.currentTimeMillis()
        prefs.edit().apply {
            putLong(KEY_VEHICLE_ID, vehicleId)
            putLong(KEY_COMPANY_ID, companyId)
            putString(KEY_COMPANY_NAME, companyName)
            putString(KEY_LICENSE_PLATE, licensePlate)
            putLong(KEY_REGISTRATION_TIME, currentTime)
            putBoolean(KEY_IS_REGISTERED, true)
            apply()
        }
    }

    fun isVehicleRegistered(): Boolean = prefs.getBoolean(KEY_IS_REGISTERED, false)

    fun getVehicleId(): Long = prefs.getLong(KEY_VEHICLE_ID, -1)
    fun getCompanyId(): Long = prefs.getLong(KEY_COMPANY_ID, -1)
    fun getCompanyName(): String? = prefs.getString(KEY_COMPANY_NAME, null)
    fun getLicensePlate(): String? = prefs.getString(KEY_LICENSE_PLATE, null)

    fun getRegistrationDateTime(): String? {
        val timestamp = prefs.getLong(KEY_REGISTRATION_TIME, -1)
        return if (timestamp != -1L) {
            val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
            dateFormat.format(Date(timestamp))
        } else null
    }

    fun clearVehicleData() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "vehicle_prefs"
        private const val KEY_VEHICLE_ID = "vehicle_id"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_COMPANY_NAME = "company_name"
        private const val KEY_LICENSE_PLATE = "license_plate"
        private const val KEY_REGISTRATION_TIME = "registration_time"
        private const val KEY_IS_REGISTERED = "is_registered"
    }
}