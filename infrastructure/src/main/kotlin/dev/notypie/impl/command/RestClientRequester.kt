package dev.notypie.impl.command

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

val logger = KotlinLogging.logger { }

class RestClientRequester(
    val baseUrl: String,
    private val authorization: String? = null,
) : RestRequester {
    companion object {
        const val SLACK_API_BASE_URL = "https://slack.com/api/"
        const val DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8"
        const val BEARER_PREFIX = "Bearer "
    }

    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeaders { headers ->
                headers.add(HttpHeaders.CONTENT_TYPE, DEFAULT_CONTENT_TYPE)
                if (!authorization.isNullOrBlank()) {
                    headers.add(
                        HttpHeaders.AUTHORIZATION,
                        authorization,
                    )
                }
            }.build()

    override fun <T : Any> safeGet(uri: String, authorizationHeader: String?, responseType: Class<T>) =
        performRequest(
            method = restClient.get(),
            uri = uri,
            authorizationHeader = authorizationHeader,
            responseType = responseType,
        )

    override fun <T : Any> safePost(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = performRequest(
        method = restClient.post(),
        uri = uri,
        authorizationHeader = authorizationHeader,
        body = body,
        contentType = contentType,
        responseType = responseType,
    )

    override fun <T : Any> safeDelete(uri: String, authorizationHeader: String?, responseType: Class<T>) =
        performRequest(
            method = restClient.delete(),
            uri = uri,
            authorizationHeader = authorizationHeader,
            responseType = responseType,
        )

    override fun <T : Any> safePut(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = performRequest(
        method = restClient.put(),
        uri = uri,
        authorizationHeader = authorizationHeader,
        body = body,
        contentType = contentType,
        responseType = responseType,
    )

    override fun <T : Any> safePatch(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = performRequest(
        method = restClient.patch(),
        uri = uri,
        authorizationHeader = authorizationHeader,
        body = body,
        contentType = contentType,
        responseType = responseType,
    )

    override fun <T : Any> get(uri: String, authorizationHeader: String?, responseType: Class<T>) =
        safeGet(uri = uri, authorizationHeader = authorizationHeader, responseType = responseType)
            .bodyOrThrow(msg = "Failed to execute Http request to $uri")

    override fun <T : Any> post(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = safePost(
        uri = uri,
        authorizationHeader = authorizationHeader,
        body = body,
        contentType = contentType,
        responseType = responseType,
    ).bodyOrThrow(msg = "Failed to execute Http request to $uri")

    override fun <T : Any> delete(uri: String, authorizationHeader: String?, responseType: Class<T>) =
        safeDelete(uri = uri, authorizationHeader = authorizationHeader, responseType = responseType)
            .bodyOrThrow(msg = "Failed to execute Http request to $uri")

    override fun <T : Any> put(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = safePut(
        uri = uri,
        authorizationHeader = authorizationHeader,
        body = body,
        contentType = contentType,
        responseType = responseType,
    ).bodyOrThrow(msg = "Failed to execute Http request to $uri")

    override fun <T : Any> patch(
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = safePatch(
        uri = uri,
        authorizationHeader = authorizationHeader,
        body = body,
        contentType = contentType,
        responseType = responseType,
    ).bodyOrThrow(msg = "Failed to execute Http request to $uri")

    private fun <T : Any> performRequest(
        method: RestClient.RequestHeadersUriSpec<*>,
        uri: String,
        authorizationHeader: String?,
        responseType: Class<T>,
    ) = runCatching {
        validateUri(uri)
        logger.debug { "Executing HTTP request: ${method.javaClass.simpleName} $uri" }
        method
            .uri(uri)
            .addAuthorizationIfPresent(authorizationHeader = authorizationHeader)
            .retrieve()
            .toEntity(responseType)
            .also {
                logger.debug { "Http request successful : ${method.javaClass.simpleName} $uri" }
            }
    }.onFailure { e ->
        logger.error(throwable = e) { "Http request failed : ${method.javaClass.simpleName} $uri" }
    }

    private fun <T : Any> performRequest(
        method: RestClient.RequestBodyUriSpec,
        uri: String,
        authorizationHeader: String?,
        body: Any?,
        contentType: MediaType?,
        responseType: Class<T>,
    ) = runCatching {
        validateUri(uri)
        logger.debug { "Executing HTTP request: ${method.javaClass.simpleName} $uri" }
        method
            .uri(uri)
            .addBodyIfPresent(body = body, contentType = contentType)
            .addAuthorizationIfPresent(authorizationHeader = authorizationHeader)
            .retrieve()
            .toEntity(responseType)
            .also {
                logger.debug { "Http request successful : ${method.javaClass.simpleName} $uri" }
            }
    }.onFailure { e ->
        logger.error(throwable = e) { "Http request failed : ${method.javaClass.simpleName} $uri" }
    }

    private fun RestClient.RequestHeadersSpec<*>.addAuthorizationIfPresent(authorizationHeader: String?) =
        apply {
            takeIf { !authorizationHeader.isNullOrBlank() }
                ?.header(HttpHeaders.AUTHORIZATION, "$BEARER_PREFIX$authorizationHeader")
        }

    private fun RestClient.RequestBodySpec.addBodyIfPresent(body: Any?, contentType: MediaType?) =
        apply {
            body?.let { b ->
                contentType?.let(::contentType)
                body(b)
            }
        }

    private fun validateUri(uri: String) =
        require(!(baseUrl.isBlank() && uri.isBlank())) {
            "Both baseUrl and uri cannot be blank."
        }
}

fun <T : Any> Result<ResponseEntity<T>>.bodyOrThrow(msg: String = "Failed to execute Http request") =
    getOrElse { throw RestClientException(msg, it) }
        .body ?: throw RestClientException("Response body is null")

fun <T : Any> Result<ResponseEntity<T>>.getBodyOrNull(): T? = getOrNull()?.body

fun <T : Any> Result<ResponseEntity<T>>.onHttpSuccess(action: (ResponseEntity<T>) -> Unit): Result<ResponseEntity<T>> =
    onSuccess(action)

fun <T : Any> Result<ResponseEntity<T>>.onHttpFailure(action: (Throwable) -> Unit): Result<ResponseEntity<T>> =
    onFailure(action)
