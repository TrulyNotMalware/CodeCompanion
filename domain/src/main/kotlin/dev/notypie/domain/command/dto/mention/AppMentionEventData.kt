package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class AppMentionEventData(
    /**
     * Required scopes : app_mention:read
     */
    @JsonProperty("client_msg_id")
    val clientMessageId: String,

    @field:JsonProperty("type")
    private val type: String,

    @field:JsonProperty("text")
    private val rawText: String,

    @field:JsonProperty("user")
    private val userId: String,

    private val ts: Double,

    @field:JsonProperty("blocks")
    private val blocks: List<Block>,

    @field:JsonProperty("team")
    private val team: String,

    @field:JsonProperty("channel")
    private val channel: String,

    @field:JsonProperty("event_ts")
    private val eventTs: Double
)