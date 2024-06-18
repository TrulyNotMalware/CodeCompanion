package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class Block(
    @field:JsonProperty("type")
    val type: String,

    @field:JsonProperty("block_id")
    val blockId: String,

    @field:JsonProperty("elements")
    val elements: List<Element>
)