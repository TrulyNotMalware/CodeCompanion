package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.entity.context.CommandContext
import java.util.UUID

internal interface ContextParser {
    fun parseContext(idempotencyKey: UUID): CommandContext
}