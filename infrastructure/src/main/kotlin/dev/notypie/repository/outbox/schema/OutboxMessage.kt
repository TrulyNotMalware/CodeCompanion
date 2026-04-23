package dev.notypie.repository.outbox.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.common.JPAJsonConverter
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.DelayHandleEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.OpenViewPayloadContents
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.SlackEventPayload
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

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
    @field:Convert(converter = JPAJsonConverter::class)
    @field:Column(name = "payload", columnDefinition = "JSON")
    val payload: Map<String, Any>,
    @field:Convert(converter = JPAJsonConverter::class)
    @field:Column(name = "metadata", columnDefinition = "JSON")
    val metadata: Map<String, Any>,
    @field:Column(name = "command_detail_type")
//    @field:Enumerated(value = EnumType.STRING)
    // Debezium cdc enum type cause Null pointer exception.
    @field:JsonProperty("command_detail_type")
    val commandDetailType: String,
//    @field:Enumerated(value = EnumType.STRING)
    @field:Column(name = "type")
    val type: String,
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,
    @field:UpdateTimestamp
    @field:JsonProperty("updated_at")
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
) {
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0L
        protected set

    //    @field:Enumerated(value = EnumType.STRING)
    @field:Column(name = "status")
    var status: String = MessageStatus.PENDING.name
        protected set

    // FIXME change final variables
    fun updateMessageStatus(status: MessageStatus) {
        this.status = status.name
    }

    fun toSlackEvent(): SlackEventPayload =
        if (type == MessageType.ACTION_RESPONSE.name) {
            ActionEventPayloadContents(
                idempotencyKey = UUID.fromString(idempotencyKey),
                publisherId = publisherId,
                commandDetailType = CommandDetailType.valueOf(commandDetailType),
                body = jsonMapper.writeValueAsString(payload),
                apiAppId = metadata["api_app_id"].toString(),
                responseUrl = metadata["response_url"].toString(),
                channel = metadata["channel"].toString(),
                eventId = UUID.fromString(eventId),
            )
        } else {
            PostEventPayloadContents(
                idempotencyKey = UUID.fromString(idempotencyKey),
                publisherId = publisherId,
                messageType = MessageType.valueOf(type),
                apiAppId = metadata["api_app_id"].toString(),
                commandDetailType = CommandDetailType.valueOf(commandDetailType),
                body = payload,
                channel = metadata["channel"].toString(),
                replaceOriginal = metadata["replace_original"].toString().toBoolean(),
                eventId = UUID.fromString(eventId),
            )
        }
}

fun SendSlackMessageEvent.toOutboxMessage(): OutboxMessage =
    when (payload) {
        is PostEventPayloadContents -> {
            (payload as PostEventPayloadContents).toOutboxMessage().outboxMessage
        }

        is ActionEventPayloadContents -> {
            (payload as ActionEventPayloadContents).toOutboxMessage().outboxMessage
        }

        is DelayHandleEventPayloadContents -> {
            throw UnsupportedOperationException(
                "DelayHandleEventPayloadContents is not persisted to the outbox; " +
                    "schedule it via TaskScheduler instead. idempotencyKey=$idempotencyKey",
            )
        }

        is OpenViewPayloadContents -> {
            throw UnsupportedOperationException(
                "OpenViewPayloadContents is not persisted to the outbox — trigger_id expires in 3s. " +
                    "Use MessageDispatcher.dispatchImmediate on the request thread. " +
                    "idempotencyKey=$idempotencyKey",
            )
        }
    }

fun PostEventPayloadContents.toOutboxMessage() =
    createNewMessagePublishedEvent(
        slackEventPayload = this,
        payload = body,
        metadata =
            mapOf(
                "api_app_id" to apiAppId,
                "channel" to channel,
                "replace_original" to replaceOriginal,
            ),
        type = messageType.name,
        reason = "PostEventContents",
    )

fun ActionEventPayloadContents.toOutboxMessage() =
    createNewMessagePublishedEvent(
        slackEventPayload = this,
        payload = jsonMapper.readValue<Map<String, Any>>(content = body),
        metadata =
            mapOf(
                "api_app_id" to apiAppId,
                "channel" to channel,
                "response_url" to responseUrl,
            ),
        type = MessageType.ACTION_RESPONSE.name,
        reason = "ActionEventContents",
    )

private fun createNewMessagePublishedEvent(
    slackEventPayload: SlackEventPayload,
    payload: Map<String, Any>,
    metadata: Map<String, Any>,
    type: String,
    reason: String,
) = NewMessagePublishedEvent(
    outboxMessage =
        OutboxMessage(
            eventId = slackEventPayload.eventId.toString(),
            idempotencyKey = slackEventPayload.idempotencyKey.toString(),
            publisherId = slackEventPayload.publisherId,
            commandDetailType = slackEventPayload.commandDetailType.name,
            payload = payload,
            metadata = metadata,
            type = type,
            createdAt = LocalDateTime.now(),
        ),
    reason = reason,
    slackEventPayload = slackEventPayload,
)

fun MutableMap<String, Any>.toOutboxMessage(): OutboxMessage =
    runCatching {
        parseJsonField(key = "metadata")
        parseJsonField(key = "payload")

        val createdAt = this["created_at"]
        val updatedAt = this["updated_at"]
        if (createdAt is Long) this["created_at"] = createdAt.toLocalDateTime()
        if (updatedAt is Long) this["updated_at"] = updatedAt.toLocalDateTime()

        jsonMapper.convertValue(this, OutboxMessage::class.java)
    }.getOrElse { e ->
        logger.error { "Failed to convert to OutboxMessage. ${e.message}" }
        throw RuntimeException("Failed to convert to OutboxMessage. ${e.message}", e)
    }

private fun MutableMap<String, Any>.parseJsonField(key: String) {
    this[key]?.takeIf { it is String }?.let {
        this[key] = jsonMapper.readValue<Map<String, Any>>(content = it as String)
    }
}

private fun Long.toLocalDateTime(): LocalDateTime {
    val seconds = this / 1_000_000
    val nanos = (this % 1_000_000) * 1_000
    return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneId.systemDefault()).toLocalDateTime()
}
