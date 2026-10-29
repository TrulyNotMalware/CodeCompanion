package dev.notypie.impl.command

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class EchoedPost(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String,
)

private data class StubReply(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
)

private data class RecordedRequest(
    val method: String,
    val path: String,
    val body: String,
    val authorization: String?,
)

class RestClientRequesterTest :
    BehaviorSpec({
        val postJson = """{"userId":1,"id":1,"title":"foo","body":"bar"}"""
        val expectedPost = EchoedPost(userId = 1, id = 1, title = "foo", body = "bar")
        val replies = ConcurrentHashMap<String, StubReply>()
        val recorded = CopyOnWriteArrayList<RecordedRequest>()
        val stubServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    recorded +=
                        RecordedRequest(
                            method = exchange.requestMethod,
                            path = exchange.requestURI.path,
                            body = exchange.requestBody.readBytes().decodeToString(),
                            authorization = exchange.requestHeaders.getFirst("Authorization"),
                        )
                    val reply =
                        replies["${exchange.requestMethod} ${exchange.requestURI.path}"]
                            ?: StubReply(status = 404, body = """{"error":"not found"}""")
                    val bytes = reply.body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", reply.contentType)
                    exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        afterSpec { stubServer.stop(0) }
        val restRequester: RestRequester =
            RestClientRequester(baseUrl = "http://127.0.0.1:${stubServer.address.port}/posts")
        val createBody = mapOf("title" to "foo", "body" to "bar", "userId" to 1)
        val updateBody = mapOf("title" to "foo", "body" to "bar", "userId" to 1, "id" to 1)
        val createJson = """{"title":"foo","body":"bar","userId":1}"""
        val updateJson = """{"title":"foo","body":"bar","userId":1,"id":1}"""

        beforeEach {
            replies.clear()
            recorded.clear()
        }

        given("Rest API Requester") {
            `when`("get request") {
                then("successfully works") {
                    replies["GET /posts/1"] = StubReply(status = 200, body = postJson)
                    replies["GET /posts"] = StubReply(status = 200, body = "[$postJson,$postJson]")

                    val response =
                        restRequester
                            .safeGet(uri = "/1", authorizationHeader = null, responseType = EchoedPost::class.java)
                            .getOrThrow()
                    val responseList =
                        restRequester
                            .safeGet(
                                uri = "",
                                authorizationHeader = null,
                                responseType = Array<EchoedPost>::class.java,
                            ).getOrThrow()

                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldBe expectedPost
                    responseList.statusCode shouldBe HttpStatus.OK
                    responseList.body?.toList() shouldBe listOf(expectedPost, expectedPost)
                    recorded.map { it.method to it.path } shouldBe listOf("GET" to "/posts/1", "GET" to "/posts")
                }

                then("sends the bearer header only when an authorization value is given") {
                    replies["GET /posts/1"] = StubReply(status = 200, body = postJson)

                    restRequester.safeGet(uri = "/1", authorizationHeader = "tok", responseType = String::class.java)
                    restRequester.safeGet(uri = "/1", authorizationHeader = null, responseType = String::class.java)

                    recorded.map { it.authorization } shouldBe listOf("Bearer tok", null)
                }
            }

            `when`("post request") {
                then("successfully works") {
                    replies["POST /posts"] = StubReply(status = 201, body = postJson)

                    val response =
                        restRequester
                            .safePost(
                                uri = "",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = createBody,
                                responseType = EchoedPost::class.java,
                            ).getOrThrow()

                    response.statusCode shouldBe HttpStatus.CREATED
                    response.body shouldBe expectedPost
                    recorded.single().body shouldBe createJson
                }

                then("throws exceptions when uri is invalid") {
                    shouldThrowExactly<RestClientException> {
                        restRequester.post(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = createBody,
                            responseType = EchoedPost::class.java,
                        )
                    }
                }
            }

            `when`("update request") {
                then("successfully works") {
                    replies["PUT /posts/1"] = StubReply(status = 200, body = postJson)
                    replies["PATCH /posts/1"] = StubReply(status = 200, body = postJson)

                    val putResponse =
                        restRequester
                            .safePut(
                                uri = "/1",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = updateBody,
                                responseType = EchoedPost::class.java,
                            ).getOrThrow()
                    val patchResponse =
                        restRequester
                            .safePatch(
                                uri = "/1",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = updateBody,
                                responseType = EchoedPost::class.java,
                            ).getOrThrow()

                    listOf(putResponse, patchResponse).forEach {
                        it.statusCode shouldBe HttpStatus.OK
                        it.body shouldBe expectedPost
                    }
                    recorded.map { it.method to it.body } shouldBe listOf("PUT" to updateJson, "PATCH" to updateJson)
                }
            }

            `when`("delete request") {
                then("successfully works") {
                    replies["DELETE /posts/1"] = StubReply(status = 200, body = "{}")

                    val response =
                        restRequester
                            .safeDelete(uri = "/1", authorizationHeader = null, responseType = Void::class.java)
                            .getOrThrow()

                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldBe null
                    recorded.single().method shouldBe "DELETE"
                }
            }

            `when`("non-safe get request") {
                then("returns body directly") {
                    replies["GET /posts/1"] = StubReply(status = 200, body = postJson)

                    val response =
                        restRequester.get(uri = "/1", authorizationHeader = null, responseType = EchoedPost::class.java)

                    response shouldBe expectedPost
                }
            }

            `when`("non-safe put request") {
                then("returns body directly") {
                    replies["PUT /posts/1"] = StubReply(status = 200, body = postJson)

                    val response =
                        restRequester.put(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = updateBody,
                            responseType = EchoedPost::class.java,
                        )

                    response shouldBe expectedPost
                }
            }

            `when`("non-safe patch request") {
                then("returns body directly") {
                    replies["PATCH /posts/1"] = StubReply(status = 200, body = postJson)

                    val response =
                        restRequester.patch(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = updateBody,
                            responseType = EchoedPost::class.java,
                        )

                    response shouldBe expectedPost
                }
            }

            `when`("non-safe delete request") {
                then("throws exception when response body is null") {
                    replies["DELETE /posts/1"] = StubReply(status = 200, body = "{}")

                    shouldThrowExactly<RestClientException> {
                        restRequester.delete(uri = "/1", authorizationHeader = null, responseType = Void::class.java)
                    }
                }
            }
        }

        given("a server that answers with an error or an unreadable body") {
            fun RestRequester.getPost() =
                safeGet(uri = "/1", authorizationHeader = null, responseType = EchoedPost::class.java)

            fun RestRequester.getPostOrThrow() =
                get(uri = "/1", authorizationHeader = null, responseType = EchoedPost::class.java)

            `when`("the status is 404") {
                then("the safe call returns the 404 as the failure and the plain call throws RestClientException") {
                    replies["GET /posts/1"] = StubReply(status = 404, body = """{"error":"not found"}""")

                    restRequester.getPost().exceptionOrNull().shouldBeInstanceOf<HttpClientErrorException.NotFound>()
                    shouldThrow<RestClientException> { restRequester.getPostOrThrow() }
                }
            }

            `when`("the status is 500 with a non-JSON body") {
                then("the failure is the server error, not a parse error") {
                    replies["GET /posts/1"] =
                        StubReply(status = 500, body = "<html>boom</html>", contentType = "text/html")

                    restRequester
                        .getPost()
                        .exceptionOrNull()
                        .shouldBeInstanceOf<HttpServerErrorException.InternalServerError>()
                }
            }

            `when`("the status is 200 with an empty body") {
                then("the safe call succeeds with a null body and the plain call throws on the null body") {
                    replies["GET /posts/1"] = StubReply(status = 200, body = "")

                    restRequester.getPost().getOrThrow().body shouldBe null
                    shouldThrowExactly<RestClientException> { restRequester.getPostOrThrow() }
                        .message shouldBe "Response body is null"
                }
            }

            `when`("the status is 200 with malformed JSON") {
                then("the safe call returns a RestClientException and the plain call wraps it") {
                    replies["GET /posts/1"] = StubReply(status = 200, body = """{"userId":1,""")

                    restRequester.getPost().exceptionOrNull().shouldBeInstanceOf<RestClientException>()
                    shouldThrow<RestClientException> { restRequester.getPostOrThrow() }
                        .message shouldBe "Failed to execute Http request to /1"
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

        given("a client that throws inside the exchange") {
            fun requesterFailingWith(failure: Throwable): RestRequester =
                RestClientRequester(
                    baseUrl = "http://127.0.0.1:9",
                    restClientBuilder = RestClient.builder().requestInterceptor { _, _, _ -> throw failure },
                )

            fun RestRequester.getOnce() =
                safeGet(uri = "/probe", authorizationHeader = null, responseType = String::class.java)

            fun RestRequester.postOnce() =
                safePost(
                    uri = "/probe",
                    authorizationHeader = null,
                    body = "probe",
                    contentType = MediaType.TEXT_PLAIN,
                    responseType = String::class.java,
                )

            `when`("the failure is an Exception") {
                val thrown = IllegalStateException("broken")
                val requester = requesterFailingWith(failure = thrown)

                then("a request without and with a body returns it as the failure") {
                    requester.getOnce().exceptionOrNull() shouldBeSameInstanceAs thrown
                    requester.postOnce().exceptionOrNull() shouldBeSameInstanceAs thrown
                }
            }

            `when`("the failure is an Error") {
                val thrown = StackOverflowError("deep")
                val requester = requesterFailingWith(failure = thrown)

                then("a request without and with a body lets the same Error propagate") {
                    shouldThrow<StackOverflowError> { requester.getOnce() } shouldBeSameInstanceAs thrown
                    shouldThrow<StackOverflowError> { requester.postOnce() } shouldBeSameInstanceAs thrown
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
