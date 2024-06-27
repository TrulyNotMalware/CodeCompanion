package dev.notypie.impl.command

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

interface RestRequester {

    fun <T> get(uri: String, authorizationHeader: String?, responseType: Class<T>): ResponseEntity<T>
    fun <T> post(uri: String, authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T>
    fun <T> delete(uri: String, authorizationHeader: String?, responseType: Class<T>): ResponseEntity<T>
    fun <T> put(uri: String, authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T>
    fun <T> patch(uri: String, authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T>

}