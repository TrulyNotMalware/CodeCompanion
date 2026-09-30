package dev.notypie.impl.command

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RestClientRequesterTimeoutTest :
    BehaviorSpec({
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

        given("the production defaults") {
            then("the read timeout is the same whole-call bound as every other Slack call") {
                RestClientRequester.DEFAULT_READ_TIMEOUT shouldBe SLACK_CALL_TIMEOUT
            }
        }

        given("a server that never answers") {
            respond = { exchange -> release.await(5L, TimeUnit.SECONDS).also { exchange.close() } }

            `when`("users.profile.get is requested") {
                val (result, elapsed) = timedLookup()

                then("the call fails at the read timeout instead of hanging") {
                    result.isFailure shouldBe true
                    elapsed shouldBeLessThan Duration.ofSeconds(3L)
                }
            }
        }

        given("a server that sends the headers and then stalls the body") {
            respond = { exchange ->
                exchange.sendResponseHeaders(200, 0L)
                exchange.responseBody.write("{".toByteArray())
                exchange.responseBody.flush()
                release.await(5L, TimeUnit.SECONDS)
                exchange.close()
            }

            `when`("users.profile.get is requested") {
                val (result, elapsed) = timedLookup()

                then("the same timeout still ends the call, because it spans the body too") {
                    result.isFailure shouldBe true
                    elapsed shouldBeLessThan Duration.ofSeconds(3L)
                }
            }
        }
    })
