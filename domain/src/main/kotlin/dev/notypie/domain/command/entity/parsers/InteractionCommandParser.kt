package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.entity.context.CommandContext

class InteractionCommandParser(

): ContextParser{
    override fun parseContext(idempotencyKey: String): CommandContext {
        TODO("Not yet implemented")
    }

}