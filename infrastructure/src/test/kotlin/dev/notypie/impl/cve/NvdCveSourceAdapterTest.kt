package dev.notypie.impl.cve

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.schema.createCveTopic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class NvdCveSourceAdapterTest :
    BehaviorSpec({
        lateinit var respond: (HttpExchange) -> Unit
        var capturedUri = ""
        var capturedApiKey: String? = null

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    capturedUri = exchange.requestURI.toString()
                    capturedApiKey = exchange.requestHeaders.getFirst("apiKey")
                    respond(exchange)
                }
                start()
            }
        afterSpec { server.stop(0) }

        val pauses = mutableListOf<Duration>()

        fun adapter(
            apiKey: String,
            clock: Clock = Clock.systemUTC(),
            sleeper: (Duration) -> Unit = { pauses += it },
        ): NvdCveSourceAdapter =
            NvdCveSourceAdapter(
                apiKey = apiKey,
                lookbackMinutes = 120,
                requestTimeout = Duration.ofSeconds(5L),
                apiBaseUrl = "http://127.0.0.1:${server.address.port}",
                clock = clock,
                sleeper = sleeper,
            )

        fun vulnerability(id: String): String =
            """{"cve": {"id": "$id", "descriptions": [{"lang": "en", "value": "Flaw $id."}]}}"""

        fun page(totalResults: Int, ids: List<String>): String =
            """{"totalResults": $totalResults, "vulnerabilities": [${ids.joinToString(",") { vulnerability(it) }}]}"""

        // Serves `pages[startIndex]` and records every startIndex requested; an unmapped index answers `fallback`.
        fun paged(
            pages: Map<Int, String>,
            requested: MutableList<Int>,
            fallback: Pair<Int, String> = 500 to "boom",
        ): (HttpExchange) -> Unit =
            { exchange ->
                val startIndex =
                    Regex("startIndex=(\\d+)")
                        .find(exchange.requestURI.query.orEmpty())
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: -1
                requested += startIndex
                val body = pages[startIndex]
                val (status, payload) = if (body != null) 200 to body else fallback
                val bytes = payload.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

        fun nvdTopic(sourceConfig: String?) =
            createCveTopic(sourceType = CveSourceType.NVD_CVE, sourceConfig = sourceConfig)

        fun jsonResponse(status: Int, body: String): (HttpExchange) -> Unit =
            { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

        val oneVulnerability =
            """
            {
              "vulnerabilities": [
                {
                  "cve": {
                    "id": "CVE-2026-1234",
                    "published": "2026-01-01T00:00:00.000",
                    "descriptions": [
                      {"lang": "es", "value": "Un fallo grave."},
                      {"lang": "en", "value": "A serious flaw.\nMore detail."}
                    ],
                    "metrics": {
                      "cvssMetricV31": [
                        {"cvssData": {"baseScore": 9.8, "baseSeverity": "CRITICAL"}}
                      ]
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        given("a cpe-configured topic with one vulnerability") {
            respond = jsonResponse(status = 200, body = oneVulnerability)

            `when`("fetch with a configured apiKey") {
                val events =
                    adapter(
                        apiKey = "nvd-key",
                    ).fetch(topic = nvdTopic(sourceConfig = """{"cpe":"cpe:2.3:a:x:y"}"""))

                then("the vulnerability maps to id, title (id + first english line), english body + metrics") {
                    val event = events.single()
                    event.externalId shouldBe "CVE-2026-1234"
                    event.title shouldBe "CVE-2026-1234 A serious flaw."
                    event.rawContent shouldBe
                        "A serious flaw.\nMore detail.\n\nCVSS baseScore=9.8 baseSeverity=CRITICAL"
                    event.publishedAt shouldBe LocalDateTime.of(2026, 1, 1, 0, 0, 0)
                }

                then("the query carries virtualMatchString and the lastModified window") {
                    capturedUri shouldContain "virtualMatchString="
                    capturedUri shouldContain "lastModStartDate="
                    capturedUri shouldContain "lastModEndDate="
                }

                then("the apiKey header is sent") {
                    capturedApiKey shouldBe "nvd-key"
                }
            }
        }

        given("a keyword-configured topic") {
            respond = jsonResponse(status = 200, body = """{"vulnerabilities":[]}""")

            `when`("fetch") {
                adapter(apiKey = "nvd-key").fetch(topic = nvdTopic(sourceConfig = """{"keyword":"log4j"}"""))

                then("the query carries keywordSearch") {
                    capturedUri shouldContain "keywordSearch="
                }
            }
        }

        given("no configured apiKey") {
            respond = jsonResponse(status = 200, body = """{"vulnerabilities":[]}""")

            `when`("fetch with a blank apiKey") {
                adapter(apiKey = "").fetch(topic = nvdTopic(sourceConfig = """{"cpe":"cpe:2.3:a:x:y"}"""))

                then("no apiKey header is sent") {
                    capturedApiKey.shouldBeNull()
                }
            }
        }

        given("a fixed clock in a non-UTC default zone") {
            respond = jsonResponse(status = 200, body = """{"vulnerabilities":[]}""")
            val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.ofHours(9))

            `when`("fetch") {
                adapter(
                    apiKey = "nvd-key",
                    clock = clock,
                ).fetch(topic = nvdTopic(sourceConfig = """{"cpe":"cpe:2.3:a:x:y"}"""))

                then("the lastModified window is the UTC instant minus the lookback") {
                    capturedUri shouldContain "lastModStartDate=2026-07-14T10%3A00%3A00.000"
                    capturedUri shouldContain "lastModEndDate=2026-07-14T12%3A00%3A00.000"
                }
            }
        }

        given("a vulnerability entry whose fields are structurally malformed") {
            respond =
                jsonResponse(
                    status = 200,
                    body =
                        """
                        {
                          "vulnerabilities": [
                            {"cve": {"id": {"nested": true}, "descriptions": "not-an-array"}},
                            {"cve": "not-an-object"},
                            {"cve": {"id": "CVE-2026-9999", "descriptions": []}}
                          ]
                        }
                        """.trimIndent(),
                )

            `when`("fetch") {
                val events =
                    adapter(
                        apiKey = "nvd-key",
                    ).fetch(topic = nvdTopic(sourceConfig = """{"cpe":"cpe:2.3:a:x:y"}"""))

                then("malformed entries are skipped without throwing and valid ones survive") {
                    events.single().externalId shouldBe "CVE-2026-9999"
                }
            }
        }

        given("a source_config with neither cpe nor keyword") {
            respond = jsonResponse(status = 200, body = """{"vulnerabilities":[]}""")

            `when`("fetch") {
                val events = adapter(apiKey = "nvd-key").fetch(topic = nvdTopic(sourceConfig = """{"foo":"bar"}"""))

                then("it returns an empty list") {
                    events shouldBe emptyList()
                }
            }
        }

        given("a non-2xx response") {
            respond = jsonResponse(status = 403, body = "rate limited")

            `when`("fetch") {
                val events =
                    adapter(
                        apiKey = "nvd-key",
                    ).fetch(topic = nvdTopic(sourceConfig = """{"cpe":"cpe:2.3:a:x:y"}"""))

                then("it returns an empty list rather than throwing") {
                    events shouldBe emptyList()
                }
            }
        }

        given("a window whose totalResults spans two pages") {
            val requested = mutableListOf<Int>()
            respond =
                paged(
                    pages =
                        mapOf(
                            0 to page(totalResults = 3, ids = listOf("CVE-2026-0001", "CVE-2026-0002")),
                            2 to page(totalResults = 3, ids = listOf("CVE-2026-0003")),
                        ),
                    requested = requested,
                )

            `when`("fetch without an apiKey") {
                pauses.clear()
                val events = adapter(apiKey = "").fetch(topic = nvdTopic(sourceConfig = """{"keyword":"linux"}"""))

                then("every page is read by advancing startIndex until totalResults") {
                    events.map { it.externalId } shouldBe listOf("CVE-2026-0001", "CVE-2026-0002", "CVE-2026-0003")
                    requested shouldBe listOf(0, 2)
                }

                then("the second request waits the anonymous NVD pacing (5 requests / 30 s → 6 s)") {
                    pauses shouldBe listOf(Duration.ofSeconds(6))
                }
            }
        }

        given("a two-page window read with an apiKey") {
            val requested = mutableListOf<Int>()
            respond =
                paged(
                    pages =
                        mapOf(
                            0 to page(totalResults = 2, ids = listOf("CVE-2026-0001")),
                            1 to page(totalResults = 2, ids = listOf("CVE-2026-0002")),
                        ),
                    requested = requested,
                )

            `when`("fetch") {
                pauses.clear()
                adapter(apiKey = "nvd-key").fetch(topic = nvdTopic(sourceConfig = """{"keyword":"linux"}"""))

                then("the pause follows the keyed limit (50 requests / 30 s → 0.6 s)") {
                    pauses shouldBe listOf(Duration.ofMillis(600))
                }
            }
        }

        given("a window whose second page fails upstream") {
            val requested = mutableListOf<Int>()
            respond =
                paged(
                    pages = mapOf(0 to page(totalResults = 4, ids = listOf("CVE-2026-0001", "CVE-2026-0002"))),
                    requested = requested,
                    fallback = 503 to "unavailable",
                )

            `when`("fetch") {
                val events = adapter(apiKey = "").fetch(topic = nvdTopic(sourceConfig = """{"keyword":"linux"}"""))

                then("the first page's events are kept and nothing is thrown") {
                    events.map { it.externalId } shouldBe listOf("CVE-2026-0001", "CVE-2026-0002")
                    requested shouldBe listOf(0, 2)
                }
            }
        }

        given("a pacing pause interrupted between pages") {
            val requested = mutableListOf<Int>()
            respond =
                paged(
                    pages = mapOf(0 to page(totalResults = 4, ids = listOf("CVE-2026-0001", "CVE-2026-0002"))),
                    requested = requested,
                )

            `when`("fetch") {
                val events =
                    adapter(apiKey = "", sleeper = { throw InterruptedException("shutdown") })
                        .fetch(topic = nvdTopic(sourceConfig = """{"keyword":"linux"}"""))
                val interrupted = Thread.interrupted()

                then("it stops paging, keeps what it has, and restores the interrupt flag instead of throwing") {
                    events.map { it.externalId } shouldBe listOf("CVE-2026-0001", "CVE-2026-0002")
                    requested shouldBe listOf(0)
                    interrupted shouldBe true
                }
            }
        }

        given("a window larger than the page budget") {
            val requested = mutableListOf<Int>()
            respond =
                paged(
                    pages = (0 until 20).associateWith { page(totalResults = 100_000, ids = listOf("CVE-2026-$it")) },
                    requested = requested,
                )

            `when`("fetch") {
                val events = adapter(apiKey = "").fetch(topic = nvdTopic(sourceConfig = """{"keyword":"linux"}"""))

                then("it stops after MAX_PAGES requests with the events read so far") {
                    requested.size shouldBe NvdCveSourceAdapter.MAX_PAGES
                    events.size shouldBe NvdCveSourceAdapter.MAX_PAGES
                }
            }
        }

        given("any adapter") {
            `when`("asked which source type it supports") {
                val adapter = adapter(apiKey = "")

                then("it supports NVD_CVE only") {
                    adapter.supports(sourceType = CveSourceType.NVD_CVE) shouldBe true
                    adapter.supports(sourceType = CveSourceType.GITHUB_RELEASE) shouldBe false
                }
            }
        }
    })
