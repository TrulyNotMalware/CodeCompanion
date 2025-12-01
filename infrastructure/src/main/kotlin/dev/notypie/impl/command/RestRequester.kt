package dev.notypie.impl.command

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

interface RestRequester {
    // safe methods
    fun <T : Any> safeGet(uri: String, authorizationHeader: String?, responseType: Class<T>): Result<ResponseEntity<T>>

    fun <T : Any> safePost(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T : Any> safeDelete(
        uri: String,
        authorizationHeader: String?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T : Any> safePut(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T : Any> safePatch(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T : Any> get(uri: String, authorizationHeader: String?, responseType: Class<T>): T

    fun <T : Any> post(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): T

    fun <T : Any> delete(uri: String, authorizationHeader: String?, responseType: Class<T>): T

    fun <T : Any> put(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): T

    fun <T : Any> patch(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): T
}
