package dev.notypie.application.common

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

    fun create(data: Any, currentTimeMillis: Long = System.currentTimeMillis()): UUID =
        create(data = DefaultIdempotencyDataSerializer.serialize(data), currentTimeMillis = currentTimeMillis)
}

interface IdempotencyDataSerializer {
    fun serialize(data: Any): String
}

object DefaultIdempotencyDataSerializer : IdempotencyDataSerializer {
    override fun serialize(data: Any): String {
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
