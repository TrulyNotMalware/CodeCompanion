package dev.notypie.domain.history.entity

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import java.util.*

class History(
    val historyId: UUID = UUID.randomUUID(),//IdempotencyKey
    val publisherId: String,
    val channel: String,
    val status: Status,

    val apiAppId: String,
    val commandType: CommandType,
    val commandDetailType: CommandDetailType
)