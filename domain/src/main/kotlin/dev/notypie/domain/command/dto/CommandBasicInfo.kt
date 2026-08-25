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
        /**
         * Builds a [CommandBasicInfo] for bot-initiated outbound Slack events that don't
         * originate from a user-driven HTTP request — e.g. fallback ephemerals from
         * `views.open` failures, scheduler-driven DM dispatches, channel summary posts.
         *
         * `appToken` is intentionally blank: outbound messages authenticate via the bot
         * token already configured on the Slack API client, not the per-request app token
         * carried on inbound payloads.
         */
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
