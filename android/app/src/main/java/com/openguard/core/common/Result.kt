package com.openguard.core.common

/** Minimal result wrapper for repository calls. No Loading state: ViewModels
 *  hold their own `isLoading` flag next to the result. */
sealed interface Result<out T> {
    data class Success<T>(val value: T) : Result<T>
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>
}
