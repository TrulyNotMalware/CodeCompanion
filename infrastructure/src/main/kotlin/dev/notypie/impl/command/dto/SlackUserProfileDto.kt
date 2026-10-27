package dev.notypie.impl.command.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class SlackUserProfileDto(
    val ok: Boolean,
    val error: String? = null,
    val profile: Profile? = null,
)

data class Profile(
    @field:JsonProperty("real_name")
    val realName: String? = null,
    @field:JsonProperty("display_name")
    val displayName: String? = null,
    @field:JsonProperty("image_24")
    val imageSize24: String? = null,
)
