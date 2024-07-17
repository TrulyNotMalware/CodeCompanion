package dev.notypie.domain.command.dto

data class SlackEventContents(
    val ok: Boolean,
    val warning: String? = null,
    val error: String? = null,
    val needed: String? = null,
    val provided: String? = null,

    //EventResponse type
    val type: String,
    val data: Any
)