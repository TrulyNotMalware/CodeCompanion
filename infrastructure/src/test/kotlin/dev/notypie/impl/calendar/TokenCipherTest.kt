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

            `when`("it is encrypted twice for the same user") {
                val first = cipher.encrypt(plaintext = plaintext, userId = "U_A")
                val second = cipher.encrypt(plaintext = plaintext, userId = "U_A")

                then("both are v2, decrypt to the plaintext for that user and differ because the IV is random") {
                    first shouldStartWith "v2."
                    first shouldNotBe second
                    cipher.decrypt(token = first, userId = "U_A") shouldBe plaintext
                    cipher.decrypt(token = second, userId = "U_A") shouldBe plaintext
                }
            }

            `when`("the ciphertext is tampered with") {
                val (version, iv, ciphertext) = cipher.encrypt(plaintext = plaintext, userId = "U_A").split(".")
                val flipped = Base64.getUrlDecoder().decode(ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
                val tampered = "$version.$iv.${Base64.getUrlEncoder().withoutPadding().encodeToString(flipped)}"

                then("decrypt rejects it instead of returning garbage") {
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = tampered, userId = "U_A") }
                }
            }

            `when`("it is decrypted with another key") {
                val encrypted = cipher.encrypt(plaintext = plaintext, userId = "U_A")

                then("decrypt rejects it") {
                    shouldThrow<IllegalArgumentException> {
                        TokenCipher(
                            keyBase64 = key(seed = 1),
                        ).decrypt(token = encrypted, userId = "U_A")
                    }
                }
            }

            `when`("the stored value has an unknown format") {
                then("decrypt rejects it") {
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = "plain-text-token", userId = "U_A") }
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = "v2.abc.def", userId = "U_A") }
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = "v3.abc.def", userId = "U_A") }
                }
            }
        }

        given("a token encrypted for one Slack user") {
            val encrypted = cipher.encrypt(plaintext = "1//owned-by-a", userId = "U_A")

            `when`("the ciphertext is copied into another user's row") {
                then("decrypting it as that user fails authentication") {
                    val failure =
                        shouldThrow<IllegalArgumentException> { cipher.decrypt(token = encrypted, userId = "U_B") }
                    failure.message shouldBe "encrypted token failed authentication"
                }
            }

            `when`("its prefix is rewritten to the unbound v1 format") {
                val downgraded = "v1." + encrypted.substringAfter(delimiter = ".")

                then("decrypt rejects it, because the tag still covers the user id") {
                    shouldThrow<IllegalArgumentException> { cipher.decrypt(token = downgraded, userId = "U_A") }
                }
            }
        }

        given("a v1 token stored before tokens were bound to a user") {
            val legacy = "v1.AQIDBAUGBwgJCgsM.NMV15Yu5nOMrwwA-PWGPTjAmkpa6GT-uxDFtjs9orO_p2CUMlQ6pyUN4"

            then("it still decrypts, whichever user's row holds it") {
                cipher.decrypt(token = legacy, userId = "U_A") shouldBe "1//0g-legacy-refresh-token"
                cipher.decrypt(token = legacy, userId = "U_B") shouldBe "1//0g-legacy-refresh-token"
            }
        }

        given("a blank user id") {
            then("neither encrypt nor decrypt accepts it") {
                shouldThrow<IllegalArgumentException> { cipher.encrypt(plaintext = "1//token", userId = " ") }
                val encrypted = cipher.encrypt(plaintext = "1//token", userId = "U_A")
                shouldThrow<IllegalArgumentException> { cipher.decrypt(token = encrypted, userId = "") }
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
