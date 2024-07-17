package dev.notypie.domain.command.dto.response

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.command.dto.mention.Block

@Deprecated("for removal")
data class SlackApiMessage(
    @field:JsonProperty("bot_id")
    val botId: String,
    val type: String,
    val text: String,
    val user: String,
    val ts: Double,

    @field:JsonProperty("app_id")
    val appId: String,
    val blocks: List<Block>,
    val team: String,

    @field:JsonProperty("bot_profile")
    val botProfile: BotProfile
)