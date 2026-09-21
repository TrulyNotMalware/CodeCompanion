package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.entity.SubmissionRouter
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.createContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

internal class InteractionContextParser(
    private val commandData: InboundCommand,
    private val interaction: InboundInteraction,
    val idempotencyKey: UUID,
    private val intents: IntentQueue,
    private val observer: SubmissionParseObserver = SubmissionParseObserver.NONE,
) : ContextParser {
    private val subCommand = SubCommand.empty()

    override fun parseContext(idempotencyKey: UUID): CommandContext<out SubCommandDefinition> {
        val basicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        val router =
            SubmissionRouter(
                commandBasicInfo = basicInfo,
                intents = intents,
                observer = observer,
            )
        return router.route(interaction = interaction)
            ?: interaction.detailType.createContext(
                commandBasicInfo = basicInfo,
                subCommand = subCommand,
                intents = intents,
            )
    }
}
