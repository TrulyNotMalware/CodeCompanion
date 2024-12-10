package dev.notypie.repository.history.dto

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status

data class HistoryContents(
    val idempotencyKey: String,
    val publisherId: String,
    val channel: String,
    val apiAppId: String,
    val status: Status,

    val commandDetailType: CommandDetailType,
    val commandType: CommandType
)
