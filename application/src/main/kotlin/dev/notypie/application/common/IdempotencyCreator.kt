package dev.notypie.application.common

import dev.notypie.common.jsonMapper
import dev.notypie.domain.common.IdempotencyData
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.util.UUID

private const val DEFAULT_IDEMPOTENCY_TIME_WINDOW_MS = 1000L
private const val DEFAULT_IDEMPOTENCY_DELIMITER = "|"

object IdempotencyCreator {
    fun create(data: String, currentTimeMillis: Long = System.currentTimeMillis()): UUID {
        val seed = currentTimeMillis / DEFAULT_IDEMPOTENCY_TIME_WINDOW_MS
        val combinedInput = "$data$DEFAULT_IDEMPOTENCY_DELIMITER$seed"
        return UUID.nameUUIDFromBytes(combinedInput.toByteArray())
    }

    fun create(data: IdempotencyData, currentTimeMillis: Long = System.currentTimeMillis()): UUID =
        create(data = DefaultIdempotencyDataSerializer.serialize(data), currentTimeMillis = currentTimeMillis)
}

interface IdempotencyDataSerializer {
    fun serialize(data: IdempotencyData): String
}

object DefaultIdempotencyDataSerializer : IdempotencyDataSerializer {
    override fun serialize(data: IdempotencyData): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        ObjectOutputStream(byteArrayOutputStream).use {
            it.writeObject(data)
        }
        val bytes = byteArrayOutputStream.toByteArray()

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)

        return hashBytes.joinToString(separator = "") { "%02x".format(it) }
    }
}

class JacksonIdempotencyDataSerializer(
    private val mapper: JsonMapper = jsonMapper,
) : IdempotencyDataSerializer {
    override fun serialize(data: IdempotencyData): String {
        val json = mapper.writeValueAsBytes(data)

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(json)

        return hashBytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
