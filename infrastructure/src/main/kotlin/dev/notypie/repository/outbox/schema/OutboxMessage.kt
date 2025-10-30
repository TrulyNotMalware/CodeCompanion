package dev.notypie.repository.outbox.schema

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import dev.notypie.common.JPAJsonConverter
import dev.notypie.common.objectMapper
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.DelayHandleEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.SlackEventPayload
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

private val logger = KotlinLogging.logger { }

@Entity
class OutboxMessage(
    @field:Id
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
                body = objectMapper.writeValueAsString(payload),
                apiAppId = metadata["api_app_id"].toString(),
                responseUrl = metadata["response_url"].toString(),
                channel = metadata["channel"].toString(),
                eventId = UUID.randomUUID(),
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
                eventId = UUID.randomUUID(),
            )
        }
}

fun SendSlackMessageEvent.toOutboxMessage(): OutboxMessage =
    when (payload) {
        is PostEventPayloadContents ->
            (payload as PostEventPayloadContents).toOutboxMessage().outboxMessage
        is ActionEventPayloadContents ->
            (payload as ActionEventPayloadContents).toOutboxMessage().outboxMessage
        is DelayHandleEventPayloadContents -> TODO()
    }

fun PostEventPayloadContents.toOutboxMessage(status: MessageStatus = MessageStatus.PENDING) =
    NewMessagePublishedEvent(
        outboxMessage =
            OutboxMessage(
                idempotencyKey = idempotencyKey.toString(),
                publisherId = publisherId,
                commandDetailType = commandDetailType.name,
                payload = body,
                metadata =
                    mapOf(
                        "api_app_id" to apiAppId,
                        "channel" to channel,
                        "replace_original" to replaceOriginal,
                    ),
                type = messageType.name,
//            status = status,
                createdAt = LocalDateTime.now(),
            ),
        reason = "PostEventContents",
        slackEventPayload = this,
    )

fun ActionEventPayloadContents.toOutboxMessage(status: MessageStatus = MessageStatus.PENDING) =
    NewMessagePublishedEvent(
        outboxMessage =
            OutboxMessage(
                idempotencyKey = idempotencyKey.toString(),
                publisherId = publisherId,
                commandDetailType = commandDetailType.name,
                payload = objectMapper.readValue<Map<String, Any>>(body),
                metadata =
                    mapOf(
                        "api_app_id" to apiAppId,
                        "channel" to channel,
                        "response_url" to responseUrl,
                    ),
                type = MessageType.ACTION_RESPONSE.name,
//            status = status,
                createdAt = LocalDateTime.now(),
            ),
        reason = "ActionEventContents",
        slackEventPayload = this,
    )

fun MutableMap<String, Any>.toOutboxMessage(): OutboxMessage =
    runCatching {
        fun MutableMap<String, Any>.parseJsonField(key: String) {
            this[key]?.takeIf { it is String }?.let {
                this[key] = objectMapper.readValue<Map<String, Any>>(it as String)
            }
        }

        fun Long.toLocalDateTime(): LocalDateTime {
            val seconds = this / 1_000_000
            val nanos = (this % 1_000_000) * 1_000
            return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }

        parseJsonField("metadata")
        parseJsonField("payload")

        val createdAt = this["created_at"]
        val updatedAt = this["updated_at"]
        if (createdAt is Long) this["created_at"] = createdAt.toLocalDateTime()
        if (updatedAt is Long) this["updated_at"] = updatedAt.toLocalDateTime()

        objectMapper.convertValue(this, OutboxMessage::class.java)
    }.getOrElse { e ->
        logger.error { "Failed to convert to OutboxMessage. ${e.message}" }
        throw RuntimeException("Failed to convert to OutboxMessage. ${e.message}", e)
    }
