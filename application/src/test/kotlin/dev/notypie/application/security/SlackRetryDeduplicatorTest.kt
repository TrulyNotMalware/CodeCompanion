package dev.notypie.application.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SlackRetryDeduplicatorTest :
    BehaviorSpec({
        val body = """{"event_id":"Ev123","type":"event_callback"}""".toByteArray()
        val fingerprint = SlackRequestFingerprint.of(method = "POST", requestUri = "/api/slack/events", body = body)
        val start = Instant.ofEpochSecond(1714280000)

        given("the fingerprint") {
            `when`("Slack retries with a fresh timestamp and signature but the same body") {
                then("the fingerprint is identical, because only the body is hashed") {
                    SlackRequestFingerprint.of(method = "POST", requestUri = "/api/slack/events", body = body) shouldBe
                        fingerprint
                }
            }

            `when`("the body differs by one byte") {
                then("the fingerprint differs") {
                    SlackRequestFingerprint.of(
                        method = "POST",
                        requestUri = "/api/slack/events",
                        body = """{"event_id":"Ev124","type":"event_callback"}""".toByteArray(),
                    ) shouldNotBe fingerprint
                }
            }
        }

        given("InMemorySlackRetryDeduplicator") {
            `when`("the original request was accepted and Slack retries it") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("the first attempt is admitted and the retry is a duplicate") {
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = null) shouldBe false
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = "1") shouldBe true
                }
            }

            `when`("the original request failed") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("the retry is processed, not acknowledged away") {
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = null) shouldBe false
                    deduplicator.markFailed(fingerprint = fingerprint)
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = "1") shouldBe false
                }
            }

            `when`("a retry arrives without a previously seen original request") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("it should be allowed to continue") {
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = "1") shouldBe false
                }
            }

            `when`("the entry is older than the TTL") {
                val ticking = mutableListOf(start, start.plus(Duration.ofMinutes(11)))
                val clock =
                    object : Clock() {
                        override fun instant(): Instant = ticking.removeFirst()

                        override fun getZone() = ZoneOffset.UTC

                        override fun withZone(zone: java.time.ZoneId) = this
                    }
                val deduplicator = InMemorySlackRetryDeduplicator(clock = clock, ttl = Duration.ofMinutes(10))

                then("it has been evicted and the retry is treated as new") {
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = null) shouldBe false
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = "1") shouldBe false
                }
            }

            `when`("more fingerprints than maxEntries are recorded") {
                val deduplicator =
                    InMemorySlackRetryDeduplicator(
                        clock = Clock.fixed(start, ZoneOffset.UTC),
                        maxEntries = 5,
                    )
                repeat(50) { index ->
                    deduplicator.isDuplicateRetry(
                        fingerprint =
                            SlackRequestFingerprint.of(
                                method = "POST",
                                requestUri = "/api/slack/events",
                                body = "body-$index".toByteArray(),
                            ),
                        retryNum = null,
                    )
                }

                then("the map is capped instead of growing without bound") {
                    deduplicator.trackedEntries() shouldBe 5
                }
            }
        }
    })
