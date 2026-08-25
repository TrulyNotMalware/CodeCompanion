package dev.notypie.application.security

import java.security.MessageDigest
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class SlackSignatureVerifier(
    private val clock: Clock,
) {
    fun verify(
        signingSecret: String,
        requestTimestamp: String?,
        requestSignature: String?,
        body: ByteArray,
        toleranceSeconds: Long,
    ): SlackSignatureVerificationResult {
        if (requestTimestamp.isNullOrBlank()) return SlackSignatureVerificationResult.missingTimestamp()
        if (requestSignature.isNullOrBlank()) return SlackSignatureVerificationResult.missingSignature()

        val timestamp =
            requestTimestamp.toLongOrNull()
                ?: return SlackSignatureVerificationResult.invalidTimestamp()
        val currentEpochSeconds = clock.instant().epochSecond
        if (abs(currentEpochSeconds - timestamp) > toleranceSeconds) {
            return SlackSignatureVerificationResult.expiredTimestamp()
        }

        val expectedSignature =
            createSignature(
                signingSecret = signingSecret,
                requestTimestamp = requestTimestamp,
                body = body,
            )
        val isValid =
            MessageDigest.isEqual(
                expectedSignature.toByteArray(Charsets.UTF_8),
                requestSignature.toByteArray(Charsets.UTF_8),
            )

        return if (isValid) {
            SlackSignatureVerificationResult.valid()
        } else {
            SlackSignatureVerificationResult.invalidSignature()
        }
    }

    fun createSignature(signingSecret: String, requestTimestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), HMAC_SHA256))
        mac.update("$SIGNATURE_VERSION:$requestTimestamp:".toByteArray(Charsets.UTF_8))
        mac.update(body)
        return "$SIGNATURE_VERSION=${mac.doFinal().toHexString()}"
    }

    companion object {
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val SIGNATURE_VERSION = "v0"
    }
}

data class SlackSignatureVerificationResult(
    val valid: Boolean,
    val reason: SlackSignatureVerificationFailureReason?,
) {
    companion object {
        fun valid() = SlackSignatureVerificationResult(valid = true, reason = null)

        fun missingTimestamp() =
            SlackSignatureVerificationResult(
                valid = false,
                reason = SlackSignatureVerificationFailureReason.MISSING_TIMESTAMP,
            )

        fun missingSignature() =
            SlackSignatureVerificationResult(
                valid = false,
                reason = SlackSignatureVerificationFailureReason.MISSING_SIGNATURE,
            )

        fun invalidTimestamp() =
            SlackSignatureVerificationResult(
                valid = false,
                reason = SlackSignatureVerificationFailureReason.INVALID_TIMESTAMP,
            )

        fun expiredTimestamp() =
            SlackSignatureVerificationResult(
                valid = false,
                reason = SlackSignatureVerificationFailureReason.EXPIRED_TIMESTAMP,
            )

        fun invalidSignature() =
            SlackSignatureVerificationResult(
                valid = false,
                reason = SlackSignatureVerificationFailureReason.INVALID_SIGNATURE,
            )
    }
}

enum class SlackSignatureVerificationFailureReason {
    MISSING_TIMESTAMP,
    MISSING_SIGNATURE,
    INVALID_TIMESTAMP,
    EXPIRED_TIMESTAMP,
    INVALID_SIGNATURE,
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
