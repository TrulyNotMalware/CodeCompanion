package dev.notypie.common

inline fun containFailure(onFailure: (Exception) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (exception: Exception) {
        onFailure(exception)
    }
}

inline fun <T> runCatchingExceptions(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (exception: Exception) {
        Result.failure(exception)
    }
