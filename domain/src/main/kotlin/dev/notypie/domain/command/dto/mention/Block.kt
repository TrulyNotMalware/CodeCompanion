package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty

data class Block(
    @field:JsonProperty("type")
    val type: String,

    @field:JsonProperty("block_id")
    val blockId: String,

    @field:JsonProperty("elements")
    val elements: List<Element> = listOf(),

    @field:JsonProperty("fields")
    val fields: List<Element> = listOf(),

    @field:JsonProperty("text")
    val text: TextElement? = null
)