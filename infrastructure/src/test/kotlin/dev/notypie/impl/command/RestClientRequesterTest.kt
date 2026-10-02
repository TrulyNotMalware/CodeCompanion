package dev.notypie.impl.command

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.dto.PostDomainCreateRequestBody
import dev.notypie.dto.PostDomainResponse
import dev.notypie.dto.PostDomainUpdateRequestBody
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RestClientRequesterTest :
    BehaviorSpec({
        val restRequester: RestRequester =
            RestClientRequester(
                baseUrl = "https://jsonplaceholder.typicode.com/posts",
            )
        val createRequestBody = PostDomainCreateRequestBody.getDefault()
        val updateRequestBody = PostDomainUpdateRequestBody.getDefault()

        given("Rest API Requester") {
            `when`("get request") {
                then("successfully works") {
                    val response =
                        restRequester
                            .safeGet(
                                uri = "/1",
                                authorizationHeader = null,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()
                    val responseList =
                        restRequester
                            .safeGet(
                                uri = "",
                                authorizationHeader = null,
                                responseType = Array<PostDomainResponse>::class.java,
                            ).getOrThrow()
                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldNotBe null

                    responseList.statusCode shouldBe HttpStatus.OK
                    responseList.body shouldNotBe null
                    responseList.body?.size shouldNotBe 0
                }
            }

            `when`("post request") {
                then("successfully works") {
                    val response =
                        restRequester
                            .safePost(
                                uri = "",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = createRequestBody,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()
                    response.statusCode shouldBe HttpStatus.CREATED
                    response.body?.apply {
                        userId shouldBe createRequestBody.userId
                        body shouldBe createRequestBody.body
                    }
                }

                then("throws exceptions when uri is invalid") {
                    shouldThrowExactly<RestClientException> {
                        restRequester.post(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = createRequestBody,
                            responseType = PostDomainResponse::class.java,
                        )
                    }
                }
            }

            `when`("update request") {
                then("successfully works") {
                    val putRequest =
                        restRequester
                            .safePut(
                                uri = "/1",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = updateRequestBody,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()
                    val patchResponse =
                        restRequester
                            .safePatch(
                                uri = "/1",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = updateRequestBody,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()

                    with(putRequest) {
                        statusCode shouldBe HttpStatus.OK
                        body shouldNotBe null
                        body?.apply {
                            userId shouldBe updateRequestBody.userId
                            id shouldBe updateRequestBody.id
                            title shouldBe updateRequestBody.title
                            body shouldBe updateRequestBody.body
                        }
                    }

                    with(patchResponse) {
                        statusCode shouldBe HttpStatus.OK
                        body shouldNotBe null
                        body?.apply {
                            userId shouldBe updateRequestBody.userId
                            id shouldBe updateRequestBody.id
                            title shouldBe updateRequestBody.title
                            body shouldBe updateRequestBody.body
                        }
                    }
                }
            }

            `when`("delete request") {
                then("successfully works") {
                    val response =
                        restRequester
                            .safeDelete(uri = "/1", authorizationHeader = null, responseType = Void::class.java)
                            .getOrThrow()
                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldBe null
                }
            }

            `when`("non-safe get request") {
                then("returns body directly") {
                    val response =
                        restRequester.get(
                            uri = "/1",
                            authorizationHeader = null,
                            responseType = PostDomainResponse::class.java,
                        )
                    response shouldNotBe null
                    response.id shouldBe 1
                }
            }

            `when`("non-safe put request") {
                then("returns body directly") {
                    val response =
                        restRequester.put(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = updateRequestBody,
                            responseType = PostDomainResponse::class.java,
                        )
                    response shouldNotBe null
                    response.userId shouldBe updateRequestBody.userId
                }
            }

            `when`("non-safe patch request") {
                then("returns body directly") {
                    val response =
                        restRequester.patch(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = updateRequestBody,
                            responseType = PostDomainResponse::class.java,
                        )
                    response shouldNotBe null
                    response.userId shouldBe updateRequestBody.userId
                }
            }

            `when`("non-safe delete request") {
                then("throws exception when response body is null") {
                    shouldThrowExactly<RestClientException> {
                        restRequester.delete(
                            uri = "/1",
                            authorizationHeader = null,
                            responseType = Void::class.java,
                        )
                    }
                }
            }
        }

        given("a GET with a URI template variable carrying query metacharacters") {
            var rawQuery: String? = null
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/") { exchange ->
                        rawQuery = exchange.requestURI.rawQuery
                        val bytes = "{}".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    start()
                }
            val loopbackRequester = RestClientRequester(baseUrl = "http://127.0.0.1:${server.address.port}/api/")

            `when`("the variable is expanded") {
                loopbackRequester.safeGet(
                    uri = "users.profile.get?user={user}",
                    authorizationHeader = null,
                    responseType = String::class.java,
                    uriVariables = mapOf("user" to "U1&extra=1#frag"),
                )
                server.stop(0)

                then("the value is encoded and cannot add parameters or a fragment") {
                    rawQuery shouldBe "user=U1%26extra%3D1%23frag"
                }
            }
        }

        given("the production timeouts") {
            then("the read timeout is the same whole-call bound as every other Slack call") {
                RestClientRequester.DEFAULT_READ_TIMEOUT shouldBe SLACK_CALL_TIMEOUT
            }
        }

        given("a loopback server that stalls before or during the response") {
            val release = CountDownLatch(1)
            lateinit var respond: (HttpExchange) -> Unit
            val serverExecutor = Executors.newCachedThreadPool()
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    executor = serverExecutor
                    createContext("/") { exchange -> respond(exchange) }
                    start()
                }
            afterSpec {
                release.countDown()
                server.stop(0)
                serverExecutor.shutdownNow()
            }
            val impatient =
                RestClientRequester(
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/",
                    readTimeout = Duration.ofMillis(300L),
                )

            fun timedLookup(): Pair<Result<*>, Duration> {
                val startedAt = System.nanoTime()
                val result =
                    impatient.safeGet(
                        uri = "users.profile.get?user={user}",
                        authorizationHeader = null,
                        responseType = String::class.java,
                        uriVariables = mapOf("user" to "U1"),
                    )
                return result to Duration.ofNanos(System.nanoTime() - startedAt)
            }

            `when`("the server never answers") {
                respond = { exchange -> release.await(5L, TimeUnit.SECONDS).also { exchange.close() } }
                val (result, elapsed) = timedLookup()

                then("the call fails at the read timeout instead of hanging") {
                    result.isFailure shouldBe true
                    elapsed shouldBeLessThan Duration.ofSeconds(3L)
                }
            }

            `when`("the server sends the headers and then stalls the body") {
                respond = { exchange ->
                    exchange.sendResponseHeaders(200, 0L)
                    exchange.responseBody.write("{".toByteArray())
                    exchange.responseBody.flush()
                    release.await(5L, TimeUnit.SECONDS)
                    exchange.close()
                }
                val (result, elapsed) = timedLookup()

                then("the same timeout still ends the call, because it spans the body too") {
                    result.isFailure shouldBe true
                    elapsed shouldBeLessThan Duration.ofSeconds(3L)
                }
            }
        }
    })
