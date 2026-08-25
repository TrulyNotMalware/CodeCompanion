package dev.notypie.application.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SlackRetryDeduplicatorTest :
    BehaviorSpec({
        given("InMemorySlackRetryDeduplicator") {
            val fingerprint =
                SlackRequestFingerprint(
                    method = "POST",
                    requestUri = "/api/slack/events",
                    timestamp = "1714280000",
                    signature = "v0=signature",
                )

            `when`("the original request is followed by a Slack retry") {
                val deduplicator =
                    InMemorySlackRetryDeduplicator(
                        clock = Clock.fixed(Instant.ofEpochSecond(1714280000), ZoneOffset.UTC),
                    )

                then("only the retry should be considered duplicate") {
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = null) shouldBe false
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = "1") shouldBe true
                }
            }

            `when`("a retry arrives without a previously seen original request") {
                val deduplicator =
                    InMemorySlackRetryDeduplicator(
                        clock = Clock.fixed(Instant.ofEpochSecond(1714280000), ZoneOffset.UTC),
                    )

                then("it should be allowed to continue") {
                    deduplicator.isDuplicateRetry(fingerprint = fingerprint, retryNum = "1") shouldBe false
                }
            }
        }
    })
