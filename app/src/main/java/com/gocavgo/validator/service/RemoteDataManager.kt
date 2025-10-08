package com.gocavgo.validator.service

import android.util.Log
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
                Log.d(TAG, "=== FETCHING TRIPS FOR VEHICLE $vehicleId FROM REMOTE ===")

                val urlBuilder = TRIPS_BASE_URL.toHttpUrl().newBuilder()
                urlBuilder.addQueryParameter("vehicle_id", vehicleId.toString())
                urlBuilder.addQueryParameter("page", page.toString())
                urlBuilder.addQueryParameter("limit", limit.toString())

                val url = urlBuilder.build()
                Log.d(TAG, "Request URL: $url")

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
                        Log.d(TAG, "Response: $responseBody")

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
                        Log.d(TAG, "phaase 1 passed")
                        Log.d(TAG, "Filtered out ${paginatedResponse.trips.size - filteredTrips.size} completed trips")
                        val paginatedResult = RemoteDataManager.PaginatedResult(
                            data = filteredTrips,
                            pagination = pagination
                        )
                        Log.d(
                            TAG,
                            "Retrieved ${filteredTrips.size} trips for vehicle $vehicleId (page ${pagination.page} of ${pagination.total_pages})"
                        )
                        Log.d(TAG, "Total trips: ${pagination.total}")
                        Log.d(TAG, "===============================")
                        RemoteResult.Success(paginatedResult)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(
                            TAG,
                            "Failed to fetch trips. Response code: ${resp.code}, Error: $errorBody"
                        )
                        RemoteResult.Error("Failed to fetch trips: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while fetching trips: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch trips: ${e.message}", e)
                RemoteResult.Error("Failed to fetch trips: ${e.message}")
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