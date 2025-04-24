package dev.notypie.impl.command.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class SlackUserProfileDto(
    val ok: Boolean,
    val profile: Profile
)

data class Profile(
    val title: String,
    val phone: String,
    val skype: String,
    @field:JsonProperty("real_name")
    val realName: String,
    @field:JsonProperty("real_name_normalized")
    val realNameNormalized: String,
    @field:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("display_name_normalized")
    val displayNameNormalized: String,
    val fields: Map<String, Field>,
    @field:JsonProperty("status_text")
    val statusText: String,
    @field:JsonProperty("status_emoji")
    val statusEmoji: String,
    @field:JsonProperty("status_emoji_display_info")
    val statusEmojiDisplayInfo: List<Any> = listOf(),
    @field:JsonProperty("status_expiration")
    val statusExpiration: Int,
    @field:JsonProperty("avatar_hash")
    val avatarHash: String,
    val email: String,
    @field:JsonProperty("first_name")
    val firstName: String,
    @field:JsonProperty("last_name")
    val lastName: String,
    @field:JsonProperty("image_24")
    val imageSize24: String,
    @field:JsonProperty("image_32")
    val imageSize32: String,
    @field:JsonProperty("image_48")
    val imageSize48: String,
    @field:JsonProperty("image_72")
    val imageSize72: String,
    @field:JsonProperty("image_192")
    val imageSize192: String,
    @field:JsonProperty("image_512")
    val imageSize512: String,
    @field:JsonProperty("status_text_canonical")
    val statusTextCanonical: String
)

data class Field(
    val value: String,
    val alt: String
)
