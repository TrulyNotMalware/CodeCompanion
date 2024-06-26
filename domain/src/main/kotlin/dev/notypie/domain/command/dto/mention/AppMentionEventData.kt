package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class AppMentionEventData(
    /**
     * Required scopes : app_mention:read
     */
    @JsonProperty("client_msg_id")
    val clientMessageId: String,

    @field:JsonProperty("type")
    val type: String,

    @field:JsonProperty("text")
    val rawText: String,

    @field:JsonProperty("user")
    val userId: String,

    val ts: Double,

    @field:JsonProperty("blocks")
    val blocks: List<Block>,

    @field:JsonProperty("team")
    val team: String,

    @field:JsonProperty("channel")
    val channel: String,

    @field:JsonProperty("event_ts")
    val eventTs: Double
)