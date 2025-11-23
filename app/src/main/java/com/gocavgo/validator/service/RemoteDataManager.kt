package com.gocavgo.validator.service

import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.dataclass.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.also
import kotlin.io.use

/**
 * Manages remote operations for places, routes, and trips.
 * Currently logs operations but designed to integrate with REST API later.
 */
class RemoteDataManager {

    companion object {
        private const val TAG = "RemoteDataManager"
        private const val TRIPS_BASE_URL = "https://api.gocavgo.com/api/navig/trips"
        private const val SETTINGS_BASE_URL = "https://api.gocavgo.com/api/main/vehicles"
        private const val BOOKINGS_BASE_URL = "https://api.gocavgo.com/api/book/bookings/trip"

        @Volatile
        private var INSTANCE: RemoteDataManager? = null

        fun getInstance(): RemoteDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteDataManager().also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Initialize OkHttp client with proper configuration
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Initialize JSON serializer with proper configuration
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /**
     * Get trips for a specific vehicle with pagination
     * @param vehicleId ID of the vehicle to get trips for
     * @param page Page number (default: 1)
     * @param limit Number of items per page (default: 20)
     * @return RemoteResult containing paginated list of TripResponse objects
     */
    suspend fun getVehicleTrips(
        vehicleId: Int,
        page: Int = 1,
        limit: Int = 20
    ): RemoteResult<RemoteDataManager.PaginatedResult<List<TripResponse>>> {
        return withContext(Dispatchers.IO) {
            try {
                Logging.d(TAG, "=== FETCHING TRIPS FOR VEHICLE $vehicleId FROM REMOTE ===")

                val urlBuilder = TRIPS_BASE_URL.toHttpUrl().newBuilder()
                urlBuilder.addQueryParameter("vehicle_id", vehicleId.toString())
                urlBuilder.addQueryParameter("page", page.toString())
                urlBuilder.addQueryParameter("limit", limit.toString())

                val url = urlBuilder.build()
                Logging.d(TAG, "Request URL: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Logging.d(TAG, "Response: $responseBody")

                        val paginatedResponse =
                            json.decodeFromString<PaginatedTripsResponse>(responseBody)

                        // Filter out completed trips
                        val filteredTrips = paginatedResponse.trips.filter { trip ->
                            trip.status.lowercase() != "completed"
                        }

                        val pagination = PaginationInfo(
                            total = filteredTrips.size, // Use filtered count for total
                            total_pages = (filteredTrips.size + paginatedResponse.limit - 1) / paginatedResponse.limit,
                            page = (paginatedResponse.offset / paginatedResponse.limit) + 1,
                            limit = paginatedResponse.limit,
                            has_next = (paginatedResponse.offset + paginatedResponse.limit) < filteredTrips.size,
                            has_prev = paginatedResponse.offset > 0
                        )
                        Logging.d(TAG, "phaase 1 passed")
                        Logging.d(TAG, "Filtered out ${paginatedResponse.trips.size - filteredTrips.size} completed trips")
                        val paginatedResult = RemoteDataManager.PaginatedResult(
                            data = filteredTrips,
                            pagination = pagination
                        )
                        Logging.d(
                            TAG,
                            "Retrieved ${filteredTrips.size} trips for vehicle $vehicleId (page ${pagination.page} of ${pagination.total_pages})"
                        )
                        Logging.d(TAG, "Total trips: ${pagination.total}")
                        Logging.d(TAG, "===============================")
                        RemoteResult.Success(paginatedResult)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Logging.e(
                            TAG,
                            "Failed to fetch trips. Response code: ${resp.code}, Error: $errorBody"
                        )
                        RemoteResult.Error("Failed to fetch trips: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Logging.e(TAG, "Network error while fetching trips: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to fetch trips: ${e.message}", e)
                RemoteResult.Error("Failed to fetch trips: ${e.message}")
            }
        }
    }

    /**
     * Get vehicle settings for a specific vehicle
     * @param vehicleId ID of the vehicle to get settings for
     * @return RemoteResult containing VehicleSettings object
     */
    suspend fun getVehicleSettings(vehicleId: Int): RemoteResult<VehicleSettings> {
        return withContext(Dispatchers.IO) {
            try {
                Logging.d(TAG, "=== FETCHING SETTINGS FOR VEHICLE $vehicleId FROM REMOTE ===")

                val url = "$SETTINGS_BASE_URL/$vehicleId/settings".toHttpUrl()
                Logging.d(TAG, "Request URL: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Logging.d(TAG, "Settings response: $responseBody")

                        val settings = json.decodeFromString<VehicleSettings>(responseBody)
                        Logging.d(TAG, "Retrieved settings for vehicle $vehicleId")
                        Logging.d(TAG, "===============================")
                        RemoteResult.Success(settings)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Logging.e(
                            TAG,
                            "Failed to fetch settings. Response code: ${resp.code}, Error: $errorBody"
                        )
                        RemoteResult.Error("Failed to fetch settings: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Logging.e(TAG, "Network error while fetching settings: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to fetch settings: ${e.message}", e)
                RemoteResult.Error("Failed to fetch settings: ${e.message}")
            }
        }
    }

    /**
     * Get bookings for a specific trip
     * @param tripId ID of the trip to get bookings for
     * @return RemoteResult containing list of ApiBooking objects
     */
    suspend fun getTripBookings(tripId: Int): RemoteResult<List<com.gocavgo.validator.dataclass.ApiBooking>> {
        return withContext(Dispatchers.IO) {
            try {
                Logging.d(TAG, "=== FETCHING BOOKINGS FOR TRIP $tripId FROM REMOTE ===")

                val url = "$BOOKINGS_BASE_URL/$tripId".toHttpUrl()
                Logging.d(TAG, "Request URL: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Logging.d(TAG, "Bookings response length: ${responseBody.length}")

                        // API returns a direct array of bookings
                        val bookings = json.decodeFromString<List<com.gocavgo.validator.dataclass.ApiBooking>>(responseBody)
                        Logging.d(TAG, "Retrieved ${bookings.size} bookings for trip $tripId")
                        Logging.d(TAG, "===============================")
                        RemoteResult.Success(bookings)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Logging.e(
                            TAG,
                            "Failed to fetch bookings. Response code: ${resp.code}, Error: $errorBody"
                        )
                        RemoteResult.Error("Failed to fetch bookings: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Logging.e(TAG, "Network error while fetching bookings: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to fetch bookings: ${e.message}", e)
                RemoteResult.Error("Failed to fetch bookings: ${e.message}")
            }
        }
    }

    data class PaginatedResult<T>(
        val data: T,
        val pagination: PaginationInfo
    )

    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

/**
 * Sealed class representing remote operation results
 */
sealed class RemoteResult<out T> {
    data class Success<T>(val data: T) : RemoteResult<T>()
    data class Error(val message: String) : RemoteResult<Nothing>()

    fun isSuccess(): Boolean = this is Success
    fun isError(): Boolean = this is Error

    fun getDataOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    fun getErrorOrNull(): String? = when (this) {
        is Success -> null
        is Error -> message
    }
}