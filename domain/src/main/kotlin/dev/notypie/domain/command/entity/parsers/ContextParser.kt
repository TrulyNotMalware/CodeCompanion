package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.entity.context.CommandContext

internal interface ContextParser {
    fun parseContext(): CommandContext
}