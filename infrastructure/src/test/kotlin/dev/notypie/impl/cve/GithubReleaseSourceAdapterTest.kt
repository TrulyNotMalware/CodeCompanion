package dev.notypie.impl.cve

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.schema.createCveTopic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDateTime

/**
 * Exercises the adapter against a real HTTP server (JDK built-in, no extra deps): happy-path JSON
 * mapping, the name→tag_name title fallback, the auth header appearing only with a configured
 * token, and empty-list handling for malformed config and non-2xx responses.
 */
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
            // GitHub sends "" (not null) for tag-only releases; blank must also fall through.
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
