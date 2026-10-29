package dev.notypie.application.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal const val REFERENCE_SIGNING_SECRET = "secret"
internal const val REFERENCE_TIMESTAMP = "1714280000"
internal const val REFERENCE_FORM_BODY = "team_id=T123&command=%2Fmeetup&text=hello+world"
internal const val REFERENCE_FORM_SIGNATURE = "v0=f1c90b909de61387a9e77d8df674db12a490d68757212f7bf550588ee29950b8"
internal const val REFERENCE_UTF8_BODY =
    "{\"event\":{\"type\":\"app_mention\",\"text\":\"caf\u00e9 \u2615 \uD83D\uDE80 na\u00efve\"}}"
internal const val REFERENCE_UTF8_SIGNATURE = "v0=c76dbc8cf055a7f535e4b08541668279936accf0309f633de9700cd09781f132"

class SlackSignatureVerifierTest :
    BehaviorSpec({
        given("SlackSignatureVerifier") {
            val signingSecret = REFERENCE_SIGNING_SECRET
            val timestamp = REFERENCE_TIMESTAMP
            val body = REFERENCE_FORM_BODY.toByteArray(Charsets.UTF_8)
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

            `when`("a form body is checked against a signature computed outside the JVM") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = timestamp,
                        requestSignature = REFERENCE_FORM_SIGNATURE,
                        body = body,
                        toleranceSeconds = 300,
                    )

                then("it matches, so the HMAC covers v0:timestamp:body keyed by the UTF-8 secret") {
                    result.valid shouldBe true
                    result.reason shouldBe null
                }
            }

            `when`("a multi-byte UTF-8 body is checked against a signature computed outside the JVM") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = timestamp,
                        requestSignature = REFERENCE_UTF8_SIGNATURE,
                        body = REFERENCE_UTF8_BODY.toByteArray(Charsets.UTF_8),
                        toleranceSeconds = 300,
                    )

                then("it matches, so the raw UTF-8 bytes are signed as they arrived") {
                    result.valid shouldBe true
                    result.reason shouldBe null
                }
            }

            `when`("the same body and signature are verified under a different secret") {
                val result =
                    verifier.verify(
                        signingSecret = "other-secret",
                        requestTimestamp = timestamp,
                        requestSignature = validSignature,
                        body = body,
                        toleranceSeconds = 300,
                    )

                then("the signature mismatches, so the secret keys the HMAC") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.INVALID_SIGNATURE
                }
            }

            `when`("the same body and signature arrive with a different timestamp inside the window") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = (timestamp.toLong() - 60).toString(),
                        requestSignature = validSignature,
                        body = body,
                        toleranceSeconds = 300,
                    )

                then("the signature mismatches, so the timestamp is part of the signed input") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.INVALID_SIGNATURE
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

            `when`("the timestamp is one second beyond the future edge of the replay window") {
                val result =
                    verifier.checkHeaders(
                        requestTimestamp = (timestamp.toLong() + 301).toString(),
                        requestSignature = validSignature,
                        toleranceSeconds = 300,
                    )

                then("it is rejected as expired, like one second beyond the past edge") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.EXPIRED_TIMESTAMP
                }
            }

            `when`("the timestamp header is not an integer") {
                val result =
                    verifier.verify(
                        signingSecret = signingSecret,
                        requestTimestamp = "soon",
                        requestSignature = validSignature,
                        body = body,
                        toleranceSeconds = 300,
                    )

                then("the request is rejected as an invalid timestamp") {
                    result.valid shouldBe false
                    result.reason shouldBe SlackSignatureVerificationFailureReason.INVALID_TIMESTAMP
                }
            }

            `when`("the timestamp is far enough in the past that now - timestamp overflows a Long") {
                val result =
                    verifier.checkHeaders(
                        requestTimestamp = (Long.MIN_VALUE + timestamp.toLong()).toString(),
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
