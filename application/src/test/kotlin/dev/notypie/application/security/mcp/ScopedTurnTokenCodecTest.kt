package dev.notypie.application.security.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ScopedTurnTokenCodecTest :
    BehaviorSpec({
        val secret = "test-signing-secret"
        val mintedAt = Instant.parse("2026-07-08T12:00:00Z")

        fun codecAt(instant: Instant, signingSecret: String = secret): ScopedTurnTokenCodec =
            ScopedTurnTokenCodec(
                signingSecret = signingSecret,
                tokenTtl = Duration.ofSeconds(300L),
                clockSkew = Duration.ofSeconds(30L),
                clock = Clock.fixed(instant, ZoneOffset.UTC),
            )

        given("a minted token") {
            val token =
                codecAt(instant = mintedAt).mint(userId = "U123", sessionKey = "C1:1751.0001", turnId = "turn-1")

            `when`("verified before expiry") {
                val decoded = codecAt(instant = mintedAt.plusSeconds(60L)).verify(token = token)

                then("every field round-trips") {
                    decoded.shouldNotBeNull()
                    decoded.userId shouldBe "U123"
                    decoded.sessionKey shouldBe "C1:1751.0001"
                    decoded.turnId shouldBe "turn-1"
                    decoded.expiresAt shouldBe mintedAt.plusSeconds(300L)
                }
            }

            `when`("verified just inside the skew window past expiry") {
                then("it still verifies") {
                    codecAt(instant = mintedAt.plusSeconds(329L)).verify(token = token).shouldNotBeNull()
                }
            }

            `when`("verified beyond expiry plus skew") {
                then("it is rejected") {
                    codecAt(instant = mintedAt.plusSeconds(331L)).verify(token = token).shouldBeNull()
                }
            }

            `when`("the payload is swapped for another token's payload") {
                val otherToken =
                    codecAt(instant = mintedAt).mint(userId = "U999", sessionKey = "C2:1751.0002", turnId = "turn-2")
                val spliced = "${token.split(".")[0]}.${otherToken.split(".")[1]}.${token.split(".")[2]}"

                then("the signature no longer matches") {
                    codecAt(instant = mintedAt).verify(token = spliced).shouldBeNull()
                }
            }

            `when`("the signature is tampered") {
                val flippedLast = if (token.last() == 'A') 'B' else 'A'
                val tampered = token.dropLast(1) + flippedLast

                then("it is rejected") {
                    codecAt(instant = mintedAt).verify(token = tampered).shouldBeNull()
                }
            }

            `when`("verified with a different secret") {
                then("it is rejected") {
                    codecAt(instant = mintedAt, signingSecret = "other-secret").verify(token = token).shouldBeNull()
                }
            }

            `when`("malformed tokens are verified") {
                then("all are rejected without throwing") {
                    codecAt(instant = mintedAt).verify(token = "garbage").shouldBeNull()
                    codecAt(instant = mintedAt).verify(token = "v2.abc.def").shouldBeNull()
                    codecAt(instant = mintedAt).verify(token = "v1.only-two-segments").shouldBeNull()
                    codecAt(instant = mintedAt).verify(token = "v1.!!!not-base64!!!.sig").shouldBeNull()
                    codecAt(instant = mintedAt).verify(token = "").shouldBeNull()
                }
            }
        }

        given("a blank signing secret") {
            `when`("minting") {
                then("it refuses") {
                    shouldThrow<IllegalStateException> {
                        codecAt(instant = mintedAt, signingSecret = "").mint(
                            userId = "U123",
                            sessionKey = "C1:1751.0001",
                            turnId = "turn-1",
                        )
                    }
                }
            }

            `when`("verifying a token minted with a real secret") {
                val token =
                    codecAt(instant = mintedAt).mint(userId = "U123", sessionKey = "C1:1751.0001", turnId = "turn-1")

                then("verification fails closed") {
                    codecAt(instant = mintedAt, signingSecret = "").verify(token = token).shouldBeNull()
                }
            }
        }
    })
