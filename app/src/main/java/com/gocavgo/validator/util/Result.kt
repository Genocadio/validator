package com.gocavgo.validator.util

/**
 * Simple Result class for operation outcomes
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()

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
    
    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun error(message: String): Result<Nothing> = Error(message)
    }
}
