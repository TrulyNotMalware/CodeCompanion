package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class Authorization(
    @field:JsonProperty("enterprise_id")
    val enterpriseId: String,

    @field:JsonProperty("team_id")
    val teamId: String,

    @field:JsonProperty("user_id")
    val userId: String,

    @field:JsonProperty("is_bot")
    val isBot:Boolean,

    @field:JsonProperty("is_enterprise_install")
    val isEnterpriseInstall: Boolean
)