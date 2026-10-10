package dev.notypie.impl.calendar

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TokenCipher(
    keyBase64: String,
) {
    companion object {
        private const val VERSION = "v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }

    private val key: SecretKeySpec
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    init {
        val keyBytes =
            try {
                Base64.getDecoder().decode(keyBase64.trim())
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("token encryption key is not valid base64", exception)
            }
        require(keyBytes.size == KEY_BYTES) {
            "token encryption key must decode to $KEY_BYTES bytes, got ${keyBytes.size}"
        }
        key = SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$VERSION.${encoder.encodeToString(iv)}.${encoder.encodeToString(ciphertext)}"
    }

    fun decrypt(token: String): String {
        val segments = token.split(".")
        require(segments.size == 3 && segments[0] == VERSION) { "unsupported encrypted token format" }
        val iv = decoder.decode(segments[1])
        require(iv.size == IV_BYTES) { "unsupported encrypted token format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(decoder.decode(segments[2])).toString(Charsets.UTF_8)
        } catch (exception: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("encrypted token failed authentication", exception)
        }
    }
}
