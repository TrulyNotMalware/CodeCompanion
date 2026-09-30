package dev.notypie.application.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SlackRetryDeduplicatorTest :
    BehaviorSpec({
        val body = """{"event_id":"Ev123","type":"event_callback"}""".toByteArray()
        val fingerprint = SlackRequestFingerprint.of(method = "POST", requestPath = "/api/slack/events", body = body)
        val start = Instant.ofEpochSecond(1714280000)

        fun fingerprintOf(index: Int) =
            SlackRequestFingerprint.of(
                method = "POST",
                requestPath = "/api/slack/events",
                body = "body-$index".toByteArray(),
            )

        given("the fingerprint") {
            `when`("Slack retries with a fresh timestamp and signature but the same body") {
                then("the fingerprint is identical, because only the body is hashed") {
                    SlackRequestFingerprint.of(method = "POST", requestPath = "/api/slack/events", body = body) shouldBe
                        fingerprint
                }
            }

            `when`("the body differs by one byte") {
                then("the fingerprint differs") {
                    SlackRequestFingerprint.of(
                        method = "POST",
                        requestPath = "/api/slack/events",
                        body = """{"event_id":"Ev124","type":"event_callback"}""".toByteArray(),
                    ) shouldNotBe fingerprint
                }
            }
        }

        given("InMemorySlackRetryDeduplicator") {
            `when`("Slack retries while the original attempt is still running") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("the retry is deferred so Slack keeps retrying") {
                    deduplicator.firstAttempt(fingerprint = fingerprint)
                    deduplicator.admit(fingerprint = fingerprint, retryNum = "1") shouldBe
                        SlackRetryAdmission.RetryOfInFlight
                }
            }

            `when`("Slack retries after the original attempt completed") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("the retry is absorbed") {
                    deduplicator.markCompleted(ticket = deduplicator.firstAttempt(fingerprint = fingerprint))
                    deduplicator.admit(fingerprint = fingerprint, retryNum = "1") shouldBe
                        SlackRetryAdmission.RetryOfCompleted
                }
            }

            `when`("the original attempt failed") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("the retry is processed as a new attempt") {
                    deduplicator.markFailed(ticket = deduplicator.firstAttempt(fingerprint = fingerprint))
                    deduplicator.firstAttempt(fingerprint = fingerprint, retryNum = "1")
                }
            }

            `when`("a retry arrives without a previously seen original request") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("it is processed and tracked, so the next retry is recognised") {
                    val ticket = deduplicator.firstAttempt(fingerprint = fingerprint, retryNum = "1")
                    deduplicator.markCompleted(ticket = ticket)
                    deduplicator.admit(fingerprint = fingerprint, retryNum = "2") shouldBe
                        SlackRetryAdmission.RetryOfCompleted
                }
            }

            `when`("an identical body arrives again without a retry number after the original completed") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("it is a replay and is absorbed like a retry of the completed request") {
                    deduplicator.markCompleted(ticket = deduplicator.firstAttempt(fingerprint = fingerprint))
                    deduplicator.admit(fingerprint = fingerprint, retryNum = null) shouldBe
                        SlackRetryAdmission.RetryOfCompleted
                    deduplicator.admit(fingerprint = fingerprint, retryNum = "1") shouldBe
                        SlackRetryAdmission.RetryOfCompleted
                }
            }

            `when`("an identical body arrives again without a retry number while the original is running") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))

                then("it is deferred like a retry of the in-flight request instead of running twice") {
                    deduplicator.firstAttempt(fingerprint = fingerprint)
                    deduplicator.admit(fingerprint = fingerprint, retryNum = null) shouldBe
                        SlackRetryAdmission.RetryOfInFlight
                }
            }

            `when`("the entry is older than the TTL") {
                val clock = MutableClock(instant = start)
                val deduplicator = InMemorySlackRetryDeduplicator(clock = clock, ttl = Duration.ofMinutes(10))

                then("it has expired and the retry is treated as a new attempt") {
                    deduplicator.markCompleted(ticket = deduplicator.firstAttempt(fingerprint = fingerprint))
                    clock.instant = start.plus(Duration.ofMinutes(11))
                    deduplicator.firstAttempt(fingerprint = fingerprint, retryNum = "1")
                }
            }

            `when`("two identical requests race while neither has finished") {
                val deduplicator = InMemorySlackRetryDeduplicator(clock = Clock.fixed(start, ZoneOffset.UTC))
                val executor = Executors.newFixedThreadPool(2)
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val admissions =
                    try {
                        listOf("1", "2")
                            .map { retryNum ->
                                executor.submit(
                                    Callable {
                                        ready.countDown()
                                        go.await()
                                        deduplicator.admit(fingerprint = fingerprint, retryNum = retryNum)
                                    },
                                )
                            }.also {
                                ready.await()
                                go.countDown()
                            }.map { it.get() }
                    } finally {
                        executor.shutdownNow()
                    }

                then("exactly one is admitted and the other is deferred") {
                    admissions.map { it::class } shouldContainExactlyInAnyOrder
                        listOf(SlackRetryAdmission.FirstAttempt::class, SlackRetryAdmission.RetryOfInFlight::class)
                }
            }

            `when`("more fingerprints than maxEntries are recorded") {
                val deduplicator =
                    InMemorySlackRetryDeduplicator(
                        clock = Clock.fixed(start, ZoneOffset.UTC),
                        maxEntries = 10,
                    )
                repeat(10) { index ->
                    val ticket = deduplicator.firstAttempt(fingerprint = fingerprintOf(index = index))
                    deduplicator.markCompleted(ticket = ticket)
                }
                val newest = deduplicator.admit(fingerprint = fingerprintOf(index = 10), retryNum = null)

                then("completed entries are trimmed to 90% of the cap in one pass and the new request is tracked") {
                    newest.shouldBeInstanceOf<SlackRetryAdmission.FirstAttempt>()
                    deduplicator.trackedEntries() shouldBe 10
                    deduplicator.admit(fingerprint = fingerprintOf(index = 10), retryNum = "1") shouldBe
                        SlackRetryAdmission.RetryOfInFlight
                }
            }

            `when`("the cap is reached while every entry is still in flight") {
                val deduplicator =
                    InMemorySlackRetryDeduplicator(
                        clock = Clock.fixed(start, ZoneOffset.UTC),
                        maxEntries = 3,
                    )
                val admissions =
                    (0 until 5).map { index ->
                        deduplicator.admit(fingerprint = fingerprintOf(index = index), retryNum = null)
                    }

                then("new requests run untracked, the map stays at the cap and no in-flight entry is evicted") {
                    admissions.take(3).forEach { it.shouldBeInstanceOf<SlackRetryAdmission.FirstAttempt>() }
                    admissions.drop(3) shouldBe List(2) { SlackRetryAdmission.Untracked }
                    deduplicator.trackedEntries() shouldBe 3
                    repeat(3) { index ->
                        deduplicator.admit(fingerprint = fingerprintOf(index = index), retryNum = "1") shouldBe
                            SlackRetryAdmission.RetryOfInFlight
                    }
                }
            }

            `when`("maxEntries is 1 and five different requests are in flight") {
                val deduplicator =
                    InMemorySlackRetryDeduplicator(
                        clock = Clock.fixed(start, ZoneOffset.UTC),
                        maxEntries = 1,
                    )
                repeat(5) { index -> deduplicator.admit(fingerprint = fingerprintOf(index = index), retryNum = null) }

                then("only the first is recorded") {
                    deduplicator.trackedEntries() shouldBe 1
                }
            }

            `when`("a late mark from an expired attempt arrives after a newer attempt registered") {
                val clock = MutableClock(instant = start)
                val deduplicator = InMemorySlackRetryDeduplicator(clock = clock, ttl = Duration.ofMinutes(10))
                val staleTicket = deduplicator.firstAttempt(fingerprint = fingerprint)
                clock.instant = start.plus(Duration.ofMinutes(11))
                val currentTicket = deduplicator.firstAttempt(fingerprint = fingerprint, retryNum = "1")

                deduplicator.markFailed(ticket = staleTicket)
                val afterStaleFailure = deduplicator.admit(fingerprint = fingerprint, retryNum = "2")
                deduplicator.markCompleted(ticket = staleTicket)
                val afterStaleCompletion = deduplicator.admit(fingerprint = fingerprint, retryNum = "3")
                deduplicator.markCompleted(ticket = currentTicket)
                val afterCurrentCompletion = deduplicator.admit(fingerprint = fingerprint, retryNum = "4")

                then("only the mark of the current generation changes the entry") {
                    staleTicket.generation shouldNotBe currentTicket.generation
                    afterStaleFailure shouldBe SlackRetryAdmission.RetryOfInFlight
                    afterStaleCompletion shouldBe SlackRetryAdmission.RetryOfInFlight
                    afterCurrentCompletion shouldBe SlackRetryAdmission.RetryOfCompleted
                }
            }

            `when`("maxEntries is not positive") {
                then("construction is rejected") {
                    shouldThrow<IllegalArgumentException> { InMemorySlackRetryDeduplicator(maxEntries = 0) }
                }
            }
        }
    })

private class MutableClock(
    var instant: Instant,
) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

private fun SlackRetryDeduplicator.firstAttempt(
    fingerprint: SlackRequestFingerprint,
    retryNum: String? = null,
): SlackRetryTicket =
    admit(fingerprint = fingerprint, retryNum = retryNum)
        .shouldBeInstanceOf<SlackRetryAdmission.FirstAttempt>()
        .ticket
