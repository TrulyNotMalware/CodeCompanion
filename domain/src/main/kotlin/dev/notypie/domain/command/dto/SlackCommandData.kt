package dev.notypie.domain.command.dto

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.common.IdempotencyData
import java.util.UUID

data class SlackCommandData(
    val appId: String,
    val appToken: String,
    val publisherId: String,
    val publisherName: String,
    val channel: String,
    val channelName: String,
    val slackCommandType: SlackCommandType,
    val subCommands: List<String> = listOf(),
    val rawHeader: SlackRequestHeaders,
    val rawBody: Map<String, Any>,
    val body: Any,
    val teamId: String? = null,
) : IdempotencyData {
    fun extractBasicInfo(idempotencyKey: UUID): CommandBasicInfo =
        CommandBasicInfo(
            appId = appId,
            appToken = appToken,
            publisherId = publisherId,
            channel = channel,
            idempotencyKey = idempotencyKey,
        )
}
