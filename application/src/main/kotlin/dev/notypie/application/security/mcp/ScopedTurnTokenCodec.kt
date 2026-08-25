package dev.notypie.application.security.mcp

import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints and verifies the per-turn MCP token: `v1.<base64url(payload)>.<base64url(hmac-sha256)>`.
 * Deliberately not JWT — this app is the only issuer and the only audience, so a fixed
 * algorithm plus a constant-time compare avoids the alg-confusion surface of a JWT library.
 */
class ScopedTurnTokenCodec(
    private val signingSecret: String,
    private val tokenTtl: Duration,
    private val clockSkew: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        private const val VERSION = "v1"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun mint(userId: String, sessionKey: String, turnId: String): String {
        check(signingSecret.isNotBlank()) { "MCP turn-token minting requires a non-blank signing secret" }
        val issuedAt = clock.instant()
        val payload =
            mapOf(
                "sub" to userId,
                "sk" to sessionKey,
                "tid" to turnId,
                "iat" to issuedAt.epochSecond,
                "exp" to issuedAt.plus(tokenTtl).epochSecond,
            )
        val encodedPayload = encoder.encodeToString(jsonMapper.writeValueAsBytes(payload))
        return "$VERSION.$encodedPayload.${sign(signedPart = "$VERSION.$encodedPayload")}"
    }

    fun verify(token: String): ScopedTurnToken? {
        if (signingSecret.isBlank()) return null
        val segments = token.split(".")
        if (segments.size != 3 || segments[0] != VERSION) return null
        val signedPart = "${segments[0]}.${segments[1]}"
        val expected = sign(signedPart = signedPart).toByteArray()
        if (!MessageDigest.isEqual(segments[2].toByteArray(), expected)) return null
        return runCatching { parsePayload(encodedPayload = segments[1]) }
            .getOrNull()
            ?.takeUnless { clock.instant().isAfter(it.expiresAt.plus(clockSkew)) }
    }

    private fun parsePayload(encodedPayload: String): ScopedTurnToken {
        val payload = jsonMapper.readValue(decoder.decode(encodedPayload), Map::class.java)
        return ScopedTurnToken(
            userId = payload["sub"] as String,
            sessionKey = payload["sk"] as String,
            turnId = payload["tid"] as String,
            expiresAt = Instant.ofEpochSecond((payload["exp"] as Number).toLong()),
        )
    }

    private fun sign(signedPart: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(signingSecret.toByteArray(), HMAC_ALGORITHM))
        return encoder.encodeToString(mac.doFinal(signedPart.toByteArray()))
    }
}
