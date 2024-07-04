package dev.notypie.domain.command.dto.response

import com.fasterxml.jackson.annotation.JsonProperty

data class SlackApiResponse(
    val ok: Boolean,
    val channel: String?,
    val ts: Double,
    @field:JsonProperty("message")
    val message: SlackApiMessage?
)
