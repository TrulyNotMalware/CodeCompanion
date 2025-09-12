package dev.notypie.impl.command

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

interface RestRequester {
    // safe methods
    fun <T> safeGet(uri: String, authorizationHeader: String?, responseType: Class<T>): Result<ResponseEntity<T>>

    fun <T> safePost(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T> safeDelete(uri: String, authorizationHeader: String?, responseType: Class<T>): Result<ResponseEntity<T>>

    fun <T> safePut(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T> safePatch(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): Result<ResponseEntity<T>>

    fun <T> get(uri: String, authorizationHeader: String?, responseType: Class<T>): T

    fun <T> post(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): T

    fun <T> delete(uri: String, authorizationHeader: String?, responseType: Class<T>): T

    fun <T> put(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): T

    fun <T> patch(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ): T
}
