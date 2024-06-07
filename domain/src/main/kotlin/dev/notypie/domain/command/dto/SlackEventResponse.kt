package dev.notypie.domain.command.dto

open class SlackEventResponse(
    val ok: Boolean,
    val warning: String,
    val error: String,
    val needed: String,
    val provided: String
) {
}