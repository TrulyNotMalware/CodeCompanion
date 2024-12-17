package dev.notypie.repository.outbox.schema

import com.fasterxml.jackson.module.kotlin.readValue
import dev.notypie.common.JPAJsonConverter
import dev.notypie.common.objectMapper
import dev.notypie.domain.command.dto.ActionEventContents
import dev.notypie.domain.command.dto.MessageType
import dev.notypie.domain.command.dto.PostEventContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime

@Entity
class OutboxMessage(
    @field:Id
    val idempotencyKey: String,

    @field:Column(name = "publisher_id", nullable = false)
    val publisherId: String,

    @field:Convert(converter = JPAJsonConverter::class)
    @field:Column(name = "payload", columnDefinition = "JSON")
    val payload: Map<String, Any>,

    @field:Convert(converter = JPAJsonConverter::class)
    @field:Column(name = "metadata", columnDefinition = "JSON")
    val metadata: Map<String, Any>,

    @field:Column(name = "command_detail_type")
    @field:Enumerated(value = EnumType.STRING)
    val commandDetailType: CommandDetailType,

    @field:Column(name = "type")
    @field:Enumerated(value = EnumType.STRING)
    val type: MessageType,

    @field:Column(name = "status")
    @field:Enumerated(value = EnumType.STRING)
    val status: MessageStatus,

    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    @field:LastModifiedDate
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
){
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0L
        protected set


    fun updateMessageStatus(status: MessageStatus) =
        OutboxMessage(
            idempotencyKey = this.idempotencyKey,
            publisherId = this.publisherId,
            payload = this.payload,
            metadata = this.metadata,
            commandDetailType = this.commandDetailType,
            type = this.type,
            status = status,
            createdAt = this.createdAt
        )
}

fun PostEventContents.toOutboxMessage(status: MessageStatus = MessageStatus.PENDING) =
    NewMessagePublishedEvent(
        outboxMessage = OutboxMessage(
            idempotencyKey = this.idempotencyKey,
            publisherId = this.publisherId,
            commandDetailType = this.commandDetailType,
            payload = this.body,
            metadata = mapOf(),
            type = this.messageType,
            status = status,
            createdAt = LocalDateTime.now()
        ),
        reason = "PostEventContents"
    )

fun ActionEventContents.toOutboxMessage(status: MessageStatus = MessageStatus.PENDING) =
    NewMessagePublishedEvent(
        outboxMessage = OutboxMessage(
            idempotencyKey = this.idempotencyKey,
            publisherId = this.publisherId,
            commandDetailType = this.commandDetailType,
            payload = objectMapper.readValue<Map<String, Any>>(this.body),
            metadata = mapOf(),
            type = MessageType.ACTION_RESPONSE,
            status = status,
            createdAt = LocalDateTime.now()
        ),
        reason = "ActionEventContents"
    )