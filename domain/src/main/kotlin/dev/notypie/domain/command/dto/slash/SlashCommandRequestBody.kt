package dev.notypie.domain.command.dto.slash

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders

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
    val isEnterpriseInstall : String,

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
    val triggerId: String
){
    fun toSlackCommandData(
        rawBody: Map<String, String> = mapOf(),
        rawHeader: SlackRequestHeaders = SlackRequestHeaders(),
    ) = SlackCommandData(
        appId = this.apiAppId,
        appToken = this.token,
        channel = this.channel,
        publisherId = this.userId,
        slackCommandType = SlackCommandType.SLASH,
        body = this,
        rawBody = rawBody,
        rawHeader = rawHeader
    )
}