package dev.notypie.impl.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BoundedHttpTest :
    BehaviorSpec({
        fun httpDate(instant: Instant): String =
            DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))

        fun clientWhoseBodyRead(fails: () -> Nothing): HttpClient {
            val stream =
                object : InputStream() {
                    override fun read(): Int = fails()
                }
            val response = mockk<HttpResponse<InputStream>> { every { body() } returns stream }
            return mockk { every { send(any(), any<HttpResponse.BodyHandler<InputStream>>()) } returns response }
        }

        fun HttpClient.readBody(): Result<BoundedResponse> =
            runCatching {
                sendWithinDeadline(
                    request = HttpRequest.newBuilder(URI.create("http://127.0.0.1/")).build(),
                    deadline = Duration.ofSeconds(5L),
                    maxBodyBytes = 64,
                )
            }

        given("sendWithinDeadline over a body read that fails") {
            `when`("the read is interrupted as the JDK reports it: flag set, an IOException wrapping the interrupt") {
                val readFailure = IOException(InterruptedException())
                val outcome =
                    clientWhoseBodyRead {
                        Thread.currentThread().interrupt()
                        throw readFailure
                    }.readBody()
                val keptInterrupt = Thread.interrupted()

                then("it throws InterruptedException with the flag set, not an IOException a caller counts as failed") {
                    outcome.exceptionOrNull().shouldBeInstanceOf<InterruptedException>().cause shouldBeSameInstanceAs
                        readFailure
                    keptInterrupt shouldBe true
                }
            }

            `when`("the IOException wraps an InterruptedException but the flag was cleared") {
                val outcome = clientWhoseBodyRead { throw IOException(InterruptedException()) }.readBody()
                val keptInterrupt = Thread.interrupted()

                then("it throws InterruptedException and sets the flag again") {
                    outcome.exceptionOrNull().shouldBeInstanceOf<InterruptedException>()
                    keptInterrupt shouldBe true
                }
            }

            `when`("the thread is interrupted and the IOException has no cause") {
                val outcome =
                    clientWhoseBodyRead {
                        Thread.currentThread().interrupt()
                        throw IOException("closed")
                    }.readBody()
                val keptInterrupt = Thread.interrupted()

                then("it throws InterruptedException with the flag set") {
                    outcome.exceptionOrNull().shouldBeInstanceOf<InterruptedException>()
                    keptInterrupt shouldBe true
                }
            }

            `when`("the read fails with an IOException on a thread that is not interrupted") {
                val readFailure = IOException("connection reset")
                val outcome = clientWhoseBodyRead { throw readFailure }.readBody()
                val keptInterrupt = Thread.interrupted()

                then("the same IOException propagates and the flag stays clear") {
                    outcome.exceptionOrNull() shouldBeSameInstanceAs readFailure
                    keptInterrupt shouldBe false
                }
            }
        }

        given("parseRetryAfter over delta-seconds") {
            listOf(
                "7" to Duration.ofSeconds(7L),
                " 7 " to Duration.ofSeconds(7L),
                "0" to Duration.ZERO,
                "86400" to MAX_RETRY_AFTER,
                "86401" to MAX_RETRY_AFTER,
                "99999999999999999999999" to MAX_RETRY_AFTER,
                "-5" to Duration.ZERO,
                "-99999999999999999999999" to Duration.ZERO,
            ).forEach { (value, expected) ->
                `when`("the header is \"$value\"") {
                    then("it is $expected: clamped to [0, 24 h], so a caller never sleeps a negative time") {
                        parseRetryAfter(value = value) shouldBe expected
                    }
                }
            }
        }

        given("parseRetryAfter over an RFC 1123 HTTP date") {
            `when`("the date is an hour ahead") {
                val retryAfter = parseRetryAfter(value = httpDate(instant = Instant.now().plusSeconds(3600L)))

                then("it is the time left until that date") {
                    val parsed = retryAfter.shouldNotBeNull()
                    parsed shouldBeGreaterThan Duration.ofMinutes(55L)
                    parsed shouldBeLessThanOrEqualTo Duration.ofHours(1L)
                }
            }

            `when`("the date has already passed") {
                then("it is zero, never negative") {
                    val anHourAgo = httpDate(instant = Instant.now().minusSeconds(3600L))
                    parseRetryAfter(value = anHourAgo) shouldBe Duration.ZERO
                }
            }

            `when`("the date is years ahead") {
                then("it is capped at 24 hours") {
                    parseRetryAfter(value = "Wed, 21 Oct 2099 07:28:00 GMT") shouldBe MAX_RETRY_AFTER
                }
            }
        }

        given("parseRetryAfter over a missing or unreadable value") {
            listOf(null, "", "   ", "abc", "1e3", "+5", "7 seconds", "2026-10-15T07:28:00Z").forEach { value ->
                `when`("the header is ${value?.let { "\"$it\"" } ?: "absent"}") {
                    then("there is no hint") {
                        parseRetryAfter(value = value).shouldBeNull()
                    }
                }
            }
        }
    })
