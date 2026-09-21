package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.UnSupportedCommandException
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.common.error.exceptionDetails

// Call only from parseContext — failures must stay inside handleEvent's exception boundary.
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
