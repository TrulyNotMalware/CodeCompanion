package dev.notypie.impl.calendar

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.util.Base64

class TokenCipherTest :
    BehaviorSpec({
        fun key(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })

        val cipher = TokenCipher(keyBase64 = key(seed = 0))

        given("a refresh token") {
            val plaintext = "1//0g-refresh-token-value"

            `when`("it is encrypted twice") {
                val first = cipher.encrypt(plaintext = plaintext)
                val second = cipher.encrypt(plaintext = plaintext)

                then("both decrypt to the plaintext and differ because the IV is random") {
                    first shouldStartWith "v1."
                    first shouldNotBe second
                    cipher.decrypt(token = first) shouldBe plaintext
                    cipher.decrypt(token = second) shouldBe plaintext
                }
            }

            `when`("the ciphertext is tampered with") {
                val (version, iv, ciphertext) = cipher.encrypt(plaintext = plaintext).split(".")
                val flipped = Base64.getUrlDecoder().decode(ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
                val tampered = "$version.$iv.${Base64.getUrlEncoder().withoutPadding().encodeToString(flipped)}"

                then("decrypt rejects it instead of returning garbage") {
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = tampered) }
                }
            }

            `when`("it is decrypted with another key") {
                val encrypted = cipher.encrypt(plaintext = plaintext)

                then("decrypt rejects it") {
                    shouldThrow<IllegalArgumentException> {
                        TokenCipher(
                            keyBase64 = key(seed = 1),
                        ).decrypt(token = encrypted)
                    }
                }
            }

            `when`("the stored value has an unknown format") {
                then("decrypt rejects it") {
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = "plain-text-token") }
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = "v2.abc.def") }
                }
            }
        }

        given("a key that is not 32 bytes") {
            then("construction fails and names the size") {
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        TokenCipher(keyBase64 = Base64.getEncoder().encodeToString(ByteArray(16)))
                    }
                failure.message shouldBe "token encryption key must decode to 32 bytes, got 16"
            }
        }

        given("a key that is not base64") {
            then("construction fails") {
                shouldThrow<IllegalArgumentException> { TokenCipher(keyBase64 = "not base64!") }
            }
        }
    })
