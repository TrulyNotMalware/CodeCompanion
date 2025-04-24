package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class EventCallbackData(
    /**
     * Required scopes : app_mention:read
     */
    @JsonProperty("client_msg_id")
    val clientMessageId: String? = null,

    @field:JsonProperty("type")
    val type: String,

    @field:JsonProperty("text")
    val rawText: String? = null,

    @field:JsonProperty("user")
    val userId: String,

    @field:JsonProperty("app_id")
    val appId: String,

    @field:JsonProperty("bot_id")
    val botId: String,

    @field:JsonProperty("bot_profile")
    val botProfile: BotProfile,
    val ts: Double,

    @field:JsonProperty("blocks")
    val blocks: List<Block>,

    @field:JsonProperty("team")
    val team: String,

    @field:JsonProperty("channel")
    val channel: String,

    @field:JsonProperty("event_ts")
    val eventTs: Double,

    @field:JsonProperty("channel_type")
    val channelType: String,
)

data class BotProfile(
    val id: String,
    val name: String,
    val deleted: Boolean,
    val updated: Long,
    @field:JsonProperty("app_id")
    val appId: String,
    @field:JsonProperty("user_id")
    val userId: String,
    @field:JsonProperty("team_id")
    val teamId: String,
    @field:JsonProperty("icons")
    val icons: Icons
)

data class Icons(
    @field:JsonProperty("image_36")
    val imageSize36: String,
    @field:JsonProperty("image_48")
    val imageSize48: String,
    @field:JsonProperty("image_72")
    val imageSize72: String,
)