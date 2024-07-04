package dev.notypie.impl.command

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

class RestClientRequester(
    val baseUrl: String = "",
    val authorization: String? = null,
): RestRequester{

    companion object{
        const val DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8"
    }

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(this.baseUrl)
        .defaultHeaders { headers ->
            run {
                headers.add(HttpHeaders.CONTENT_TYPE, DEFAULT_CONTENT_TYPE)
                if ( !authorization.isNullOrBlank() ) headers.add(
                    HttpHeaders.AUTHORIZATION,
                    authorization
                )
            }
        }
        .build()

    override fun <T> get(uri: String, authorizationHeader: String?, responseType: Class<T>): ResponseEntity<T> =
        this.performRequest(method = this.restClient.get(), uri = uri, authorizationHeader = authorizationHeader, responseType = responseType)

    override fun <T> post(uri: String, authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T> =
        this.performRequest(method = this.restClient.post(), uri = uri, authorizationHeader = authorizationHeader, body = body, contentType = contentType, responseType = responseType)

    override fun <T> delete(uri: String, authorizationHeader: String?, responseType: Class<T>): ResponseEntity<T> =
        this.performRequest(method = this.restClient.delete(), uri = uri, authorizationHeader = authorizationHeader, responseType = responseType)

    override fun <T> put(uri: String, authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T> =
        this.performRequest(method = this.restClient.put(), uri = uri, authorizationHeader = authorizationHeader, body = body, contentType = contentType, responseType = responseType)

    override fun <T> patch(uri: String, authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T> =
        this.performRequest(method = this.restClient.patch(), uri = uri, authorizationHeader = authorizationHeader, body = body, contentType = contentType, responseType = responseType)

    private fun <T> performRequest( method: RestClient.RequestHeadersUriSpec<*>, uri: String,  authorizationHeader: String?, responseType: Class<T>): ResponseEntity<T> =
        method.uri(uri).apply {
            if (!authorizationHeader.isNullOrBlank()) header(HttpHeaders.AUTHORIZATION, "Bearer $authorizationHeader")
        }.run { returnEntity(spec = this, responseType = responseType) }

    private fun <T> performRequest( method: RestClient.RequestBodyUriSpec, uri: String,  authorizationHeader: String?, body:Any?, contentType: MediaType?, responseType: Class<T>): ResponseEntity<T> =
        method.uri(uri).apply{
            if (!authorizationHeader.isNullOrBlank()) header(HttpHeaders.AUTHORIZATION, "Bearer $authorizationHeader")
            if( body != null ){
                if( contentType != null ) contentType(contentType).body(body)
                else body(body)
            }
        }.run { returnEntity(spec = this, responseType = responseType) }

    private fun <T> returnEntity( spec: RestClient.RequestBodySpec, responseType: Class<T>): ResponseEntity<T> = spec.retrieve().toEntity(responseType)
    private fun <T> returnEntity( spec: RestClient.RequestHeadersSpec<*>, responseType: Class<T>): ResponseEntity<T> = spec.retrieve().toEntity(responseType)

}