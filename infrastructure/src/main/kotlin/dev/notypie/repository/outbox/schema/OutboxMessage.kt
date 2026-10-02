package dev.notypie.repository.outbox.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.common.jsonMapper
import dev.notypie.repository.outbox.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.domain.Persistable
import java.time.LocalDateTime
import java.time.ZoneOffset

private val logger = KotlinLogging.logger { }

@Entity
@Table(
    name = "outbox_message",
    indexes = [
        Index(name = "idx_outbox_idempotency_key", columnList = "idempotency_key"),
        Index(name = "idx_outbox_status_created_at", columnList = "status, created_at"),
        Index(name = "idx_outbox_status_updated_at", columnList = "status, updated_at"),
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
    @field:Column(name = "payload", columnDefinition = "MEDIUMTEXT", nullable = false)
    val payload: String,
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,
    @field:UpdateTimestamp
    @field:JsonProperty("updated_at")
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
    @field:JsonProperty("schema_version")
    @field:Column(
        name = "schema_version",
        nullable = false,
        columnDefinition = "INT NOT NULL DEFAULT ${OutboxSchemaVersion.V2}",
    )
    val schemaVersion: Int = OutboxSchemaVersion.V2,
    @field:JsonProperty("attempt_count")
    @field:Column(
        name = "attempt_count",
        nullable = false,
        updatable = false,
        columnDefinition = "INT NOT NULL DEFAULT 0",
    )
    val attemptCount: Int = 0,
    @field:JsonProperty("send_count")
    @field:Column(
        name = "send_count",
        nullable = false,
        updatable = false,
        columnDefinition = "INT NOT NULL DEFAULT 0",
    )
    val sendCount: Int = 0,
) : Persistable<String> {
    // The id is assigned by the application, so without this save() would merge: a SELECT before every INSERT.
    @field:Transient
    private var newRow: Boolean = true

    override fun getId(): String = eventId

    override fun isNew(): Boolean = newRow

    @PostPersist
    @PostLoad
    protected fun markPersisted() {
        newRow = false
    }

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0L
        protected set

    @field:Column(name = "status", nullable = false)
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
        if (createdAt is Long) this["created_at"] = createdAt.debeziumDateTime()
        if (updatedAt is Long) this["updated_at"] = updatedAt.debeziumDateTime()

        jsonMapper.convertValue(this, OutboxMessage::class.java)
    }.getOrElse { e ->
        logger.error(e) { "Failed to convert to OutboxMessage" }
        throw RuntimeException("Failed to convert to OutboxMessage. ${e.message}", e)
    }

// Milliseconds since the epoch reach 1e14 only in the year 5138, so anything at or above it is microseconds.
private const val EPOCH_MICROS_FLOOR = 100_000_000_000_000L

// Debezium writes DATETIME as epoch time read as UTC (no zone): Timestamp (millis) for DATETIME(0-3),
// MicroTimestamp (micros) for DATETIME(4-6).
internal fun Long.debeziumDateTime(): LocalDateTime {
    val micros = if (this >= EPOCH_MICROS_FLOOR) this else this * 1_000
    return LocalDateTime.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000L),
        (Math.floorMod(micros, 1_000_000L) * 1_000).toInt(),
        ZoneOffset.UTC,
    )
}
