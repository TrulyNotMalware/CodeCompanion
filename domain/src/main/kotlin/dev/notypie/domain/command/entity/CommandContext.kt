package dev.notypie.domain.command.entity

import dev.notypie.domain.command.dto.SlackRequestHeaders

class CommandContext(
    val channel: String,
    val botToken: String,

    val tracking: Boolean = true,

    val requestHeaders: SlackRequestHeaders
) {
}