package dev.notypie.repository.outbox.schema

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import dev.notypie.common.JPAJsonConverter
import dev.notypie.common.objectMapper
import dev.notypie.domain.common.event.ActionEventPayloadContents
import dev.notypie.domain.common.event.MessageType
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SlackEventPayload
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

private val logger = KotlinLogging.logger {  }

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
    /**
     * Debezium cdc enum type cause Null pointer exception.
     */
    @field:JsonProperty("command_detail_type")
    val commandDetailType: String,

    @field:Column(name = "type")
//    @field:Enumerated(value = EnumType.STRING)
    val type: String,

    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    @field:UpdateTimestamp
    @field:JsonProperty("updated_at")
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null
){
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0L
        protected set

    @field:Column(name = "status")
//    @field:Enumerated(value = EnumType.STRING)
    var status: String = MessageStatus.PENDING.name
        protected set

    //FIXME change final variables
    fun updateMessageStatus(status: MessageStatus) {
        this.status = status.name
    }

    fun toSlackEvent(): SlackEventPayload =
        if(this.type == MessageType.ACTION_RESPONSE.name )
            ActionEventPayloadContents(
                idempotencyKey = UUID.fromString(this.idempotencyKey),
                publisherId = this.publisherId,
                commandDetailType = CommandDetailType.valueOf(this.commandDetailType),
                body = objectMapper.writeValueAsString(this.payload),
                apiAppId = this.metadata["api_app_id"].toString(),
                responseUrl = this.metadata["response_url"].toString(),
                channel = this.metadata["channel"].toString(),
                eventId = UUID.randomUUID()
            )
        else PostEventPayloadContents(
            idempotencyKey = UUID.fromString(this.idempotencyKey),
            publisherId = this.publisherId,
            messageType = MessageType.valueOf(this.type),
            apiAppId = this.metadata["api_app_id"].toString(),
            commandDetailType = CommandDetailType.valueOf(this.commandDetailType),
            body = this.payload,
            channel = this.metadata["channel"].toString(),
            replaceOriginal = this.metadata["replace_original"].toString().toBoolean(),
            eventId = UUID.randomUUID()
        )
}


fun PostEventPayloadContents.toOutboxMessage(status: MessageStatus = MessageStatus.PENDING) =
    NewMessagePublishedEvent(
        outboxMessage = OutboxMessage(
            idempotencyKey = this.idempotencyKey.toString(),
            publisherId = this.publisherId,
            commandDetailType = this.commandDetailType.name,
            payload = this.body,
            metadata = mapOf(
                "api_app_id" to this.apiAppId,
                "channel" to this.channel,
                "replace_original" to this.replaceOriginal
            ),
            type = this.messageType.name,
//            status = status,
            createdAt = LocalDateTime.now()
        ),
        reason = "PostEventContents",
        slackEventPayload = this
    )

fun ActionEventPayloadContents.toOutboxMessage(status: MessageStatus = MessageStatus.PENDING) =
    NewMessagePublishedEvent(
        outboxMessage = OutboxMessage(
            idempotencyKey = this.idempotencyKey.toString(),
            publisherId = this.publisherId,
            commandDetailType = this.commandDetailType.name,
            payload = objectMapper.readValue<Map<String, Any>>(this.body),
            metadata = mapOf(
                "api_app_id" to this.apiAppId,
                "channel" to this.channel,
                "response_url" to this.responseUrl
            ),
            type = MessageType.ACTION_RESPONSE.name,
//            status = status,
            createdAt = LocalDateTime.now()
        ),
        reason = "ActionEventContents",
        slackEventPayload = this
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
        if(createdAt is Long) this["created_at"] = createdAt.toLocalDateTime()
        if(updatedAt is Long) this["updated_at"] = updatedAt.toLocalDateTime()

        objectMapper.convertValue(this, OutboxMessage::class.java)
    }.getOrElse { e ->
        logger.error { "Failed to convert to OutboxMessage. ${e.message}" }
        throw RuntimeException("Failed to convert to OutboxMessage. ${e.message}", e)
    }

