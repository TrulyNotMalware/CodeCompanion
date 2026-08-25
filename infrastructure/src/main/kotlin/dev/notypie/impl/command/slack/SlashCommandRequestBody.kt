package dev.notypie.impl.command.slack

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.inbound.TriggerHandle

data class SlashCommandRequestBody(
    val token: String,
    @field:JsonProperty("team_id")
    val teamId: String,
    @field:JsonProperty("team_domain")
    val teamDomainName: String,
    @field:JsonProperty("channel_id")
    val channel: String,
    @field:JsonProperty("channel_name")
    val channelName: String,
    @field:JsonProperty("api_app_id")
    val apiAppId: String,
    @field:JsonProperty("is_enterprise_install")
    val isEnterpriseInstall: String,
    @field:JsonProperty("user_id")
    val userId: String,
    @field:JsonProperty("user_name")
    val userName: String,
    val command: String,
    @field:JsonProperty("text")
    val subCommands: String,
    @field:JsonProperty("response_url")
    val responseUrl: String,
    @field:JsonProperty("trigger_id")
    val triggerId: String,
) {
    fun toInboundCommand() =
        InboundCommand(
            appId = apiAppId,
            appToken = token,
            channel = channel,
            channelName = channelName,
            actorId = userId,
            actorName = userName,
            kind = InboundKind.SLASH,
            payload = SlashInvocation(trigger = TriggerHandle(raw = triggerId)),
            subCommands = subCommandList(),
            teamId = teamId,
        )

    fun subCommandList() = subCommands.split(" ")
}
