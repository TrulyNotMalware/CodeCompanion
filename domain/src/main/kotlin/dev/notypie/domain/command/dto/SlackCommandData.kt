package dev.notypie.domain.command.dto

import dev.notypie.domain.command.SlackCommandType
import java.time.LocalDateTime
import java.util.UUID

data class SlackCommandData(
    val appId: String,
    val appToken: String,
    val publisherId: String,
    val channel: String,

    val slackCommandType: SlackCommandType,
    val rawHeader: SlackRequestHeaders,
    val rawBody: Map<String, Any>,

    val body: Any,
    val seeds: String = LocalDateTime.now().toString()
){
    fun extractBasicInfo(
        idempotencyKey: UUID
    ): CommandBasicInfo =
        CommandBasicInfo(
            appId = this.appId, appToken = this.appToken,
            publisherId = this.publisherId, channel = this.channel,
            idempotencyKey = idempotencyKey,
        )

}