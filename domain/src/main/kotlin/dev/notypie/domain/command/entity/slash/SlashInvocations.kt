package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.UnSupportedCommandException
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.common.error.exceptionDetails

/**
 * Recovers the slash payload from the envelope without a cast expression. Called lazily inside
 * `parseContext`, so a mis-built command still fails within `Command.handleEvent()`'s exception
 * boundary and surfaces as an ERROR_RESPONSE output, exactly like the pre-Phase-11 cast did —
 * only with a typed reason instead of a ClassCastException.
 */
internal fun InboundCommand.slashInvocation(commandName: String): SlashInvocation =
    when (val slashPayload = payload) {
        is SlashInvocation -> slashPayload
        else ->
            throw UnSupportedCommandException(
                commandType = kind.toString(),
                errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
                details =
                    exceptionDetails {
                        "kind" value kind.toString() because "$commandName requires a SLASH payload"
                    },
            )
    }
