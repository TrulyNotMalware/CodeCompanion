package dev.notypie.domain.command.dto

import java.util.*

data class CommandBasicInfo(
    val appId: String,
    val publisherId: String,
    val channel: String,
    val idempotencyKey: UUID,
) {
    companion object {
        fun forOutbound(
            publisherId: String,
            channel: String,
            appId: String = "",
            idempotencyKey: UUID = UUID.randomUUID(),
        ): CommandBasicInfo =
            CommandBasicInfo(
                appId = appId,
                publisherId = publisherId,
                channel = channel,
                idempotencyKey = idempotencyKey,
            )
    }
}
