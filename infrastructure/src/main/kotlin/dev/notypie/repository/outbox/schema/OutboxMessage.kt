package dev.notypie.repository.outbox.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.common.jsonMapper
import dev.notypie.repository.outbox.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val logger = KotlinLogging.logger { }

@Entity
@Table(
    name = "outbox_message",
    indexes = [
        Index(name = "idx_outbox_idempotency_key", columnList = "idempotency_key"),
    ],
)
class OutboxMessage(
    @field:Id
    @field:Column(name = "event_id")
    @field:JsonProperty("event_id")
    val eventId: String,
    @field:Column(name = "idempotency_key", nullable = false)
    @field:JsonProperty("idempotency_key")
    val idempotencyKey: String,
    @field:Column(name = "publisher_id", nullable = false)
    @field:JsonProperty("publisher_id")
    val publisherId: String,
    // Debezium CDC delivers enum types as null, so transport rides as a plain string column.
    @field:Column(name = "transport", nullable = false)
    val transport: String = Transport.SLACK.name,
    // Codec-encoded OutboundEnvelope. Stored as opaque TEXT (not JSON): the relay decodes it via
    // OutboundMessageCodec at deliver time, so the DB never needs to reason about its structure.
    @field:Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    val payload: String,
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,
    @field:UpdateTimestamp
    @field:JsonProperty("updated_at")
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
    /**
     * Payload schema version. New rows are written with [OutboxSchemaVersion.CURRENT]; the relay
     * refuses to decode a row whose version is not in [OutboxSchemaVersion.SUPPORTED] so a
     * future-binary downgrade (or an attacker-injected row) cannot trigger a malformed request.
     */
    @field:JsonProperty("schema_version")
    @field:Column(
        name = "schema_version",
        nullable = false,
        columnDefinition = "INT NOT NULL DEFAULT ${OutboxSchemaVersion.V2}",
    )
    val schemaVersion: Int = OutboxSchemaVersion.CURRENT,
) {
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0L
        protected set

    @field:Column(name = "status")
    var status: String = MessageStatus.PENDING.name
        protected set

    fun updateMessageStatus(status: MessageStatus) {
        this.status = status.name
    }
}

fun MutableMap<String, Any>.toOutboxMessage(): OutboxMessage =
    runCatching {
        val createdAt = this["created_at"]
        val updatedAt = this["updated_at"]
        if (createdAt is Long) this["created_at"] = createdAt.toLocalDateTime()
        if (updatedAt is Long) this["updated_at"] = updatedAt.toLocalDateTime()

        jsonMapper.convertValue(this, OutboxMessage::class.java)
    }.getOrElse { e ->
        logger.error { "Failed to convert to OutboxMessage. ${e.message}" }
        throw RuntimeException("Failed to convert to OutboxMessage. ${e.message}", e)
    }

private fun Long.toLocalDateTime(): LocalDateTime {
    val seconds = this / 1_000_000
    val nanos = (this % 1_000_000) * 1_000
    return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneId.systemDefault()).toLocalDateTime()
}
