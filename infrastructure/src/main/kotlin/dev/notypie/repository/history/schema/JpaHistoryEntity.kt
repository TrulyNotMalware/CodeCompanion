package dev.notypie.repository.history.schema

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import java.util.UUID

@Entity
class JpaHistoryEntity(
    @field:Id
    val id: UUID,

    @field:Column(name = "publisher_id", nullable = false)
    val publisherId: String,

    @field:Column(name = "channel", nullable = false)
    val channel: String,

    @field:Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @field:Column(name = "api_app_id", nullable = false)
    val apiAppId: String,

    @field:Column(name = "type")
    val type: String,

    @field:Column(name = "command_type", nullable = false)
    val commandType: CommandType,

    @field:Column(name = "status", nullable = false)
    val status: Status,

    @field:Column(name = "token", nullable = false)
    val token: String
)