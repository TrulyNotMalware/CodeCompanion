package dev.notypie.domain.command.dto.response

import com.fasterxml.jackson.annotation.JsonProperty

@Deprecated("for removal")
data class BotProfile(
    val id: String,

    @field:JsonProperty("app_id")
    val appId: String,
    val name: String,

    val icons: Map<String, String>,
    val deleted: Boolean,
    val updated: Int,

    @field:JsonProperty("team_id")
    val teamId: String
)