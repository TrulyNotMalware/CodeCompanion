package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class Element(
    val type: String,
    val text: String,

    @field:JsonProperty("user_id")
    val userId: String,

    val elements: List<Element>
)