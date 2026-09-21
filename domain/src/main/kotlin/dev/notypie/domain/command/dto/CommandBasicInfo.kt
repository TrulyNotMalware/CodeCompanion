package dev.notypie.domain.command.dto

import java.util.*

data class CommandBasicInfo(
    val appId: String,
    val appToken: String,
    val publisherId: String,
    val channel: String,
    val idempotencyKey: UUID,
) {
    companion object {
        // appToken is intentionally blank; outbound calls authenticate via the client's bot token.
        fun forOutbound(
            publisherId: String,
            channel: String,
            appId: String = "",
            idempotencyKey: UUID = UUID.randomUUID(),
        ): CommandBasicInfo =
            CommandBasicInfo(
                appId = appId,
                appToken = "",
                publisherId = publisherId,
                channel = channel,
                idempotencyKey = idempotencyKey,
            )
    }
}

internal fun CommandBasicInfo.withNewKey(): CommandBasicInfo =
    CommandBasicInfo(appId, appToken, publisherId, channel, UUID.randomUUID())
