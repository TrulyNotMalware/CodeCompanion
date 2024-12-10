package dev.notypie.repository.history.schema

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status
import jakarta.persistence.*
import java.util.UUID

@Entity
class JpaHistoryEntity(
    @field:Id
    val id: UUID,// IdempotencyKey

    @field:Column(name = "publisher_id", nullable = false)
    val publisherId: String,

    @field:Column(name = "channel", nullable = false)
    val channel: String,

    @field:Column(name = "api_app_id", nullable = false)
    val apiAppId: String,

    @field:Column(name = "command_type", nullable = false)
    @field:Enumerated(value = EnumType.STRING)
    val commandType: CommandType,

    @field:Column(name = "command_detail_type", nullable = false)
    @field:Enumerated(value = EnumType.STRING)
    val commandDetailType: CommandDetailType,

    @field:Column(name = "status", nullable = false)
    @field:Enumerated(value = EnumType.STRING)
    val status: Status,

//    @field:Column(name = "token", nullable = false)
//    val token: String
)