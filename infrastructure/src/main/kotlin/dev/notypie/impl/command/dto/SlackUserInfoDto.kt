package dev.notypie.impl.command.dto

data class SlackUserInfoDto(
    val ok: Boolean,
    val error: String? = null,
    val user: SlackUserInfo? = null,
)

data class SlackUserInfo(
    val id: String,
    val profile: SlackUserInfoProfile,
)

data class SlackUserInfoProfile(
    val email: String? = null,
)
