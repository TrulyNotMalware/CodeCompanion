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
