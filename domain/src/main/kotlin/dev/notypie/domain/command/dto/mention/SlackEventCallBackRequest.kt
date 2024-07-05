package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class SlackEventCallBackRequest(
    val token: String,

    @field:JsonProperty("team_id")
    val teamId: String,

    @field:JsonProperty("api_app_id")
    val apiAppId: String,

    val event: EventCallbackData,
    val type: String,

    @field:JsonProperty("event_id")
    val eventId: String,

    @field:JsonProperty("event_time")
    val eventTime: String,

    val authorizations: List<Authorization>,

    @field:JsonProperty("is_ext_shared_channel")
    val isExtSharedChannel: Boolean,

    @field:JsonProperty("event_context")
    val eventContext: String
)