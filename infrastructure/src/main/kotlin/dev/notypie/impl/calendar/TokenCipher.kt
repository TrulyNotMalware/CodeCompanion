package dev.notypie.impl.calendar

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TokenCipher(
    keyBase64: String,
) {
    companion object {
        private const val BOUND_VERSION = "v2"
        private const val UNBOUND_VERSION = "v1"
        private const val UNSUPPORTED_FORMAT = "unsupported encrypted token format"
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

    fun encrypt(plaintext: String, userId: String): String {
        val associatedData = associatedDataOf(userId = userId)
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        val ciphertext: ByteArray = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$BOUND_VERSION.${encoder.encodeToString(iv)}.${encoder.encodeToString(ciphertext)}"
    }

    fun decrypt(token: String, userId: String): String {
        val associatedData = associatedDataOf(userId = userId)
        val segments = token.split(".")
        require(segments.size == 3) { UNSUPPORTED_FORMAT }
        val bound =
            when (segments[0]) {
                BOUND_VERSION -> true
                UNBOUND_VERSION -> false
                else -> throw IllegalArgumentException(UNSUPPORTED_FORMAT)
            }
        val iv: ByteArray = decoder.decode(segments[1])
        require(iv.size == IV_BYTES) { UNSUPPORTED_FORMAT }
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (bound) cipher.updateAAD(associatedData)
        return try {
            cipher.doFinal(decoder.decode(segments[2])).toString(Charsets.UTF_8)
        } catch (exception: AEADBadTagException) {
            throw IllegalArgumentException("encrypted token failed authentication", exception)
        }
    }

    private fun associatedDataOf(userId: String): ByteArray {
        require(userId.isNotBlank()) { "an encrypted token must be bound to a user id" }
        return userId.toByteArray(Charsets.UTF_8)
    }
}
