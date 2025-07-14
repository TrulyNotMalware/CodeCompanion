package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue
import java.util.UUID

internal interface ContextParser {
    fun parseContext(idempotencyKey: UUID): CommandContext
}