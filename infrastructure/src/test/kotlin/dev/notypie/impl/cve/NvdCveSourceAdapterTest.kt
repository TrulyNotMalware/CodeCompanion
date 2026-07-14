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

/**
 * Exercises the adapter against a real HTTP server (JDK built-in): happy-path JSON mapping, the
 * cpe→virtualMatchString / keyword→keywordSearch query mapping, the apiKey header appearing only
 * when configured, and empty-list handling for malformed config and non-2xx responses.
 */
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

        fun adapter(apiKey: String, clock: Clock = Clock.systemUTC()): NvdCveSourceAdapter =
            NvdCveSourceAdapter(
                apiKey = apiKey,
                lookbackMinutes = 120,
                requestTimeout = Duration.ofSeconds(5L),
                apiBaseUrl = "http://127.0.0.1:${server.address.port}",
                clock = clock,
            )

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
            // KST (+9) zone on the clock must not shift the window: NVD reads the offset-free
            // params as UTC, so the adapter derives them from the UTC instant regardless of zone.
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
