package dev.notypie.repository.outbox.schema

import dev.notypie.common.JPAJsonConverter
import dev.notypie.repository.outbox.SlackRequestType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime

@Entity
class MessageOutbox(

    @field:Id
    val idempotencyKey: String,

    @Convert(converter = JPAJsonConverter::class)
    @field:Column(name = "payload", columnDefinition = "JSON")
    val payload: Map<String, Any>,

    @Convert(converter = JPAJsonConverter::class)
    @field:Column(name = "metadata", columnDefinition = "JSON")
    val metadata: Map<String, Any>,

    @field:Column(name = "type")
    val type: SlackRequestType,

    @field:Column(name = "status")
    val status: MessageStatus,

    @field:Column(name = "partition_key")
    val partitionKey: Int,

    @CreatedDate
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    @LastModifiedDate
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime,
)