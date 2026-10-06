package dev.notypie.impl.cve

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.schema.createCveTopic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GithubReleaseSourceAdapterTest :
    BehaviorSpec({
        lateinit var respond: (HttpExchange) -> Unit
        var capturedUri = ""
        var capturedAuthorization: String? = null
        var capturedAccept: String? = null

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    capturedUri = exchange.requestURI.toString()
                    capturedAuthorization = exchange.requestHeaders.getFirst("Authorization")
                    capturedAccept = exchange.requestHeaders.getFirst("Accept")
                    respond(exchange)
                }
                start()
            }
        afterSpec { server.stop(0) }

        fun adapter(token: String): GithubReleaseSourceAdapter =
            GithubReleaseSourceAdapter(
                token = token,
                perPage = 5,
                requestTimeout = Duration.ofSeconds(5L),
                apiBaseUrl = "http://127.0.0.1:${server.address.port}",
            )

        fun githubTopic(sourceConfig: String? = """{"repo":"owner/name"}""") =
            createCveTopic(sourceType = CveSourceType.GITHUB_RELEASE, sourceConfig = sourceConfig)

        fun jsonResponse(status: Int, body: String): (HttpExchange) -> Unit =
            { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

        fun warningsDuring(block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(GithubReleaseSourceAdapter::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        }

        given("an anonymous call answered 403 with the rate limit exhausted") {
            respond = { exchange ->
                val bytes = """{"message":"API rate limit exceeded"}""".toByteArray()
                exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
                exchange.responseHeaders.add("X-RateLimit-Reset", "1790000000")
                exchange.sendResponseHeaders(403, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            `when`("fetch") {
                var events: List<RawSourceEvent>? = null
                val warnings = warningsDuring { events = adapter(token = "").fetch(topic = githubTopic()) }

                then("it still returns an empty list, but the log names the rate limit and its reset") {
                    events shouldBe emptyList()
                    val warning = warnings.singleOrNull { it.contains("rate limit exhausted") }
                    warning.shouldNotBeNull()
                    warning shouldContain "anonymous, 60 requests/hour"
                    warning shouldContain "resets at 2026-09-21T14:13:20Z"
                }
            }
        }

        given("a plain 403 without rate-limit headers") {
            respond = jsonResponse(status = 403, body = """{"message":"Forbidden"}""")

            `when`("fetch") {
                val warnings = warningsDuring { adapter(token = "gh-token").fetch(topic = githubTopic()) }

                then("it is logged as an ordinary non-2xx, not as a rate limit") {
                    warnings shouldBe listOf("GitHub releases returned 403 for topic=${githubTopic().topicKey}")
                }
            }
        }

        given("the anonymous-limit boot check") {
            `when`("no token is set and five topics poll twelve times an hour") {
                val warning = adapter(token = "").anonymousLimitWarning(topicCount = 5, requestsPerTopicPerHour = 12)

                then("it warns that the load reaches GitHub's anonymous limit") {
                    warning.shouldNotBeNull()
                    warning shouldContain "~60 requests/hour"
                    warning shouldContain "GITHUB_TOKEN"
                }
            }

            `when`("no token is set and the load stays under the limit") {
                then("there is nothing to warn about") {
                    adapter(
                        token = "",
                    ).anonymousLimitWarning(topicCount = 4, requestsPerTopicPerHour = 12).shouldBeNull()
                }
            }

            `when`("a token is set") {
                then("the anonymous limit does not apply") {
                    adapter(token = "gh-token")
                        .anonymousLimitWarning(topicCount = 50, requestsPerTopicPerHour = 12)
                        .shouldBeNull()
                }
            }
        }

        given("a fetch on a thread that is being interrupted") {
            respond = jsonResponse(status = 200, body = "[]")

            `when`("fetch runs") {
                Thread.currentThread().interrupt()
                val outcome = runCatching { adapter(token = "").fetch(topic = githubTopic()) }
                val keptInterrupt = Thread.interrupted()

                then("the interrupt propagates with its flag instead of an empty result") {
                    outcome.exceptionOrNull().shouldBeInstanceOf<InterruptedException>()
                    keptInterrupt shouldBe true
                }
            }
        }

        given("a repo with two releases") {
            respond =
                jsonResponse(
                    status = 200,
                    body =
                        """
                        [
                          {"id": 111, "name": "v1.1", "tag_name": "v1.1",
                           "body": "First release notes", "published_at": "2026-01-02T03:04:05Z"},
                          {"id": 222, "name": "v1.0", "tag_name": "v1.0",
                           "body": "", "published_at": "2026-01-01T00:00:00Z"}
                        ]
                        """.trimIndent(),
                )

            `when`("fetch with a configured token") {
                val events = adapter(token = "gh-token").fetch(topic = githubTopic())

                then("each release maps to a raw event with id, name, body and published_at") {
                    events shouldHaveSize 2
                    val first = events.first()
                    first.externalId shouldBe "111"
                    first.title shouldBe "v1.1"
                    first.rawContent shouldBe "First release notes"
                    first.publishedAt shouldBe LocalDateTime.of(2026, 1, 2, 3, 4, 5)
                }

                then("the request targets the repo releases path with per_page and the github Accept header") {
                    capturedUri shouldContain "/repos/owner/name/releases"
                    capturedUri shouldContain "per_page=5"
                    capturedAccept shouldBe "application/vnd.github+json"
                }

                then("the bearer token is sent") {
                    capturedAuthorization shouldBe "Bearer gh-token"
                }
            }
        }

        given("a release whose name is null") {
            respond =
                jsonResponse(
                    status = 200,
                    body = """[{"id": 333, "name": null, "tag_name": "v2.0", "body": "notes"}]""",
                )

            `when`("fetch") {
                val events = adapter(token = "gh-token").fetch(topic = githubTopic())

                then("the title falls back to tag_name") {
                    events.single().title shouldBe "v2.0"
                }
            }
        }

        given("a release whose name is an empty string") {
            respond =
                jsonResponse(
                    status = 200,
                    body = """[{"id": 444, "name": "", "tag_name": "v3.0", "body": "notes"}]""",
                )

            `when`("fetch") {
                val events = adapter(token = "gh-token").fetch(topic = githubTopic())

                then("the title falls back to tag_name") {
                    events.single().title shouldBe "v3.0"
                }
            }
        }

        given("a release entry whose fields are structurally malformed") {
            respond =
                jsonResponse(
                    status = 200,
                    body =
                        """
                        [
                          {"id": {"nested": true}, "name": ["v9"], "tag_name": {}},
                          {"id": 555, "name": "v4.0", "tag_name": "v4.0", "body": "ok"}
                        ]
                        """.trimIndent(),
                )

            `when`("fetch") {
                val events = adapter(token = "gh-token").fetch(topic = githubTopic())

                then("the malformed entry is skipped without throwing and the valid one survives") {
                    events.single().externalId shouldBe "555"
                }
            }
        }

        given("a repo that is not owner/name shaped") {
            capturedUri = ""
            respond = jsonResponse(status = 200, body = "[]")

            `when`("fetch with a path-reshaping repo value") {
                val events =
                    adapter(
                        token = "gh-token",
                    ).fetch(topic = githubTopic(sourceConfig = """{"repo":"owner/name/../../evil"}"""))

                then("it returns an empty list without sending any request") {
                    events shouldBe emptyList()
                    capturedUri shouldBe ""
                }
            }
        }

        given("no configured token") {
            respond = jsonResponse(status = 200, body = "[]")

            `when`("fetch with a blank token") {
                adapter(token = "").fetch(topic = githubTopic())

                then("no Authorization header is sent") {
                    capturedAuthorization.shouldBeNull()
                }
            }
        }

        given("a malformed source_config") {
            respond = jsonResponse(status = 200, body = "[]")

            `when`("fetch with a config that has no repo") {
                val events = adapter(token = "gh-token").fetch(topic = githubTopic(sourceConfig = """{"owner":"x"}"""))

                then("it returns an empty list") {
                    events shouldBe emptyList()
                }
            }
        }

        given("a non-2xx response") {
            respond = jsonResponse(status = 404, body = """{"message":"Not Found"}""")

            `when`("fetch") {
                val events = adapter(token = "gh-token").fetch(topic = githubTopic())

                then("it returns an empty list") {
                    events shouldBe emptyList()
                }
            }
        }

        given("a source that sends the headers and then stalls") {
            val release = CountDownLatch(1)
            respond = { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("[".toByteArray())
                exchange.responseBody.flush()
                release.await(10L, TimeUnit.SECONDS)
                exchange.close()
            }
            val impatientAdapter =
                GithubReleaseSourceAdapter(
                    token = "",
                    perPage = 5,
                    requestTimeout = Duration.ofSeconds(1L),
                    apiBaseUrl = "http://127.0.0.1:${server.address.port}",
                )

            `when`("fetch") {
                val startedAt = System.nanoTime()
                val events = impatientAdapter.fetch(topic = githubTopic())
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
                release.countDown()

                then("it gives up within the request budget instead of blocking the scheduler") {
                    events shouldBe emptyList()
                    (elapsed < Duration.ofSeconds(5L)) shouldBe true
                }
            }
        }

        given("any adapter") {
            `when`("asked which source type it supports") {
                val adapter = adapter(token = "")

                then("it supports GITHUB_RELEASE only") {
                    adapter.supports(sourceType = CveSourceType.GITHUB_RELEASE) shouldBe true
                    adapter.supports(sourceType = CveSourceType.NVD_CVE) shouldBe false
                }
            }
        }
    })
