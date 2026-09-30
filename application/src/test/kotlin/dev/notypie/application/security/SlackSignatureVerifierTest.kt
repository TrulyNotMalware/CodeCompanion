package dev.notypie.application.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SlackSignatureVerifierTest :
    BehaviorSpec({
        given("SlackSignatureVerifier") {
            val signingSecret = "secret"
            val timestamp = "1714280000"
            val body = "team_id=T123&command=%2Fmeetup&text=hello+world".toByteArray(Charsets.UTF_8)
            val clock = Clock.fixed(Instant.ofEpochSecond(timestamp.toLong()), ZoneOffset.UTC)
            val verifier = SlackSignatureVerifier(clock = clock)
            val validSignature =
                verifier.createSignature(
                    signingSecret = signingSecret,
                    requestTimestamp = timestamp,
                    body = body,
                )

            `when`("request timestamp, signature, and raw body match") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = timestamp,
                        requestSignature = validSignature,
                        body = body,
                        toleranceSeconds = 300,
                    )

                then("the request should be valid") {
                    result.valid shouldBe true
                    result.reason shouldBe null
                }
            }

            `when`("request body changes after signing") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = timestamp,
                        requestSignature = validSignature,
                        body = "team_id=T123&command=%2Fmeetup&text=hello world".toByteArray(Charsets.UTF_8),
                        toleranceSeconds = 300,
                    )

                then("the request should be rejected") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.INVALID_SIGNATURE
                }
            }

            `when`("the timestamp is outside the replay window") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = (timestamp.toLong() - 301).toString(),
                        requestSignature = validSignature,
                        body = body,
                        toleranceSeconds = 300,
                    )

                then("the request should be rejected") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.EXPIRED_TIMESTAMP
                }
            }

            `when`("the timestamp is far enough in the past that now - timestamp overflows a Long") {
                val overflowingTimestamp = Long.MIN_VALUE + timestamp.toLong()
                val result =
                    verifier.checkHeaders(
                        requestTimestamp = overflowingTimestamp.toString(),
                        requestSignature = validSignature,
                        toleranceSeconds = 300,
                    )

                then("it is rejected as expired instead of wrapping around to fresh") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.EXPIRED_TIMESTAMP
                }
            }

            listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { extreme ->
                `when`("the timestamp is $extreme") {
                    val result =
                        verifier.checkHeaders(
                            requestTimestamp = extreme.toString(),
                            requestSignature = validSignature,
                            toleranceSeconds = 300,
                        )

                    then("it is rejected as expired") {
                        result.reason shouldBe SlackSignatureVerificationFailureReason.EXPIRED_TIMESTAMP
                    }
                }
            }

            `when`("the timestamp is exactly at either edge of the tolerance") {
                val edges =
                    listOf(timestamp.toLong() - 300, timestamp.toLong() + 300).map { edge ->
                        verifier.checkHeaders(
                            requestTimestamp = edge.toString(),
                            requestSignature = validSignature,
                            toleranceSeconds = 300,
                        )
                    }

                then("both are still fresh") {
                    edges.map { it.valid } shouldBe listOf(true, true)
                }
            }

            `when`("the signature is not v0= followed by 64 lower-case hex digits") {
                val result =
                    verifier.checkHeaders(
                        requestTimestamp = timestamp,
                        requestSignature = validSignature.uppercase(),
                        toleranceSeconds = 300,
                    )

                then("the headers are rejected as malformed") {
                    result.reason shouldBe SlackSignatureVerificationFailureReason.MALFORMED_SIGNATURE
                }
            }

            `when`("only the headers of a valid request are checked") {
                val result =
                    verifier.checkHeaders(
                        requestTimestamp = timestamp,
                        requestSignature = validSignature,
                        toleranceSeconds = 300,
                    )

                then("they pass") {
                    result.valid shouldBe true
                }
            }

            `when`("required headers are missing") {
                then("missing timestamp should be rejected") {
                    val result =
                        verifier.verify(
                            signingSecret = signingSecret,
                            requestTimestamp = null,
                            requestSignature = validSignature,
                            body = body,
                            toleranceSeconds = 300,
                        )

                    result.reason shouldBe SlackSignatureVerificationFailureReason.MISSING_TIMESTAMP
                }

                then("missing signature should be rejected") {
                    val result =
                        verifier.verify(
                            signingSecret = signingSecret,
                            requestTimestamp = timestamp,
                            requestSignature = null,
                            body = body,
                            toleranceSeconds = 300,
                        )

                    result.reason shouldBe SlackSignatureVerificationFailureReason.MISSING_SIGNATURE
                }
            }
        }
    })
