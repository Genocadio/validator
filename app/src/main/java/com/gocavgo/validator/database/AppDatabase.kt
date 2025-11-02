package com.gocavgo.validator.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TripEntity::class, BookingEntity::class, PaymentEntity::class, TicketEntity::class, VehicleLocationEntity::class, VehicleSettingsEntity::class],
    version = 9,
    exportSchema = false
)
@TypeConverters(TripConverters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun tripDao(): TripDao
    abstract fun bookingDao(): BookingDao
    abstract fun paymentDao(): PaymentDao
    abstract fun ticketDao(): TicketDao
    abstract fun vehicleLocationDao(): VehicleLocationDao
    abstract fun vehicleSettingsDao(): VehicleSettingsDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "validator_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

