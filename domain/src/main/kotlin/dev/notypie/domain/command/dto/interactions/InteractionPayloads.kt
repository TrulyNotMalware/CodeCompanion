package dev.notypie.domain.command.dto.interactions

// Reference from Slack SDK - BlockActionPayload
data class InteractionPayloads(
    val team: Team,
    val user: User,
    val triggerId: String,
    val isEnterprise: Boolean,
    val enterprise: Enterprise? = null,

    //Application information
    val apiAppId: String,
    val token: String,
    val container: Container,
    val channel: Channel,

    val responseUrl: String,
)