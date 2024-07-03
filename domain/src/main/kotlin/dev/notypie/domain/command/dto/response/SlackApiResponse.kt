package dev.notypie.domain.command.dto.response

data class SlackApiResponse(
    val ok: Boolean,
    val channel: String?,
    val ts: Double,
    val message: SlackApiMessage?
)
