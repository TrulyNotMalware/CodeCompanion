package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.exceptions.UnSupportedCommandException
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.DefaultIntentQueue
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.domain.common.escapeMarkup
import java.util.UUID

abstract class Command<T : SubCommandDefinition>(
    val idempotencyKey: UUID,
    val commandData: InboundCommand,
) {
    internal val intents: IntentQueue = DefaultIntentQueue()

    val commandId: UUID = UUID.randomUUID()

    fun drainIntents(): List<CommandEffect> = intents.drainSnapshot()

    internal abstract fun parseContext(subCommand: SubCommand<T>): CommandContext<out T>

    internal abstract fun findSubCommandDefinition(): T

    internal open val slashCommandName: String = ""

    internal open val subCommandDefinitions: List<T> = emptyList()

    fun handleEvent(): CommandOutput =
        try {
            executeCommand()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (exception: Exception) {
            replyToSlashFailure(exception = exception)
            CommandOutput.fail(
                basicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                commandDetailType = CommandDetailType.ERROR_RESPONSE,
                reason = exception.toString(),
            )
        }

    private fun replyToSlashFailure(exception: Exception) {
        if (commandData.payload !is SlashInvocation) return
        val markdown =
            if (exception is SubCommandParseException) {
                subCommandHelp(exception = exception)
            } else {
                val command = slashCommandName.takeIf { it.isNotBlank() }?.let { "`$it`" } ?: "that command"
                "Something went wrong handling $command. Please try again."
            }
        intents.offer(
            effect =
                OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = commandData.channel),
                    content = MessageContent.Text(headline = null, markdown = markdown),
                ),
        )
    }

    private fun subCommandHelp(exception: SubCommandParseException): String {
        val usageLines = subCommandDefinitions.map { it.usage }.filter { it.isNotBlank() }
        val usageBlock =
            usageLines
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n", prefix = "\nUsage:\n") { "• `${it.escapeMarkup()}`" }
                .orEmpty()
        if (exception.errorCode == CommandErrorCode.SUBCOMMAND_NOT_FOUND) {
            return "Unknown subcommand `${exception.subCommandName.asEchoedToken()}`.$usageBlock"
        }
        val subCommandUsage =
            subCommandDefinitions
                .firstOrNull { it.subCommandIdentifier == exception.subCommandName }
                ?.usage
                ?.takeIf { it.isNotBlank() }
        return subCommandUsage?.let { "Usage: `${it.escapeMarkup()}`" } ?: "Invalid arguments.$usageBlock"
    }

    private fun String.asEchoedToken(): String {
        val clipped = if (length > MAX_ECHOED_TOKEN_LENGTH) take(n = MAX_ECHOED_TOKEN_LENGTH) + "…" else this
        return clipped.replace(oldValue = "`", newValue = "'").escapeMarkup()
    }

    private fun executeCommand(): CommandOutput {
        val subCommand = createSubCommand()
        val context = parseContext(subCommand = subCommand)
        return when (val payload = commandData.payload) {
            is InboundInteraction -> context.executeInteraction(interaction = payload)
            else -> context.runCommand()
        }
    }

    private fun CommandContext<out T>.executeInteraction(interaction: InboundInteraction): CommandOutput =
        if (this is ReactionContext<out T>) {
            handleInteraction(interaction)
        } else {
            throw UnSupportedCommandException(
                commandType = commandData.kind.toString(),
                errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
                details =
                    exceptionDetails {
                        "commandType" value commandData.kind.toString() because
                            "handleInteraction() is required only for reaction command type"
                    },
            )
        }

    private fun createSubCommand(): SubCommand<T> {
        val options = commandData.subCommands.drop(1)
        val subCommand =
            SubCommand(
                subCommandDefinition = findSubCommandDefinition(),
                options = options,
            )
        return if (subCommand.isValid()) {
            subCommand
        } else {
            throw SubCommandParseException(
                commandName = this::class.java.simpleName,
                subCommandName = subCommand.subCommandDefinition.subCommandIdentifier,
                errorCode = CommandErrorCode.SUBCOMMAND_NOT_VALID,
                details =
                    exceptionDetails {
                        "subcommand options" value options.joinToString { "," } because "sub command validation failed"
                    },
            )
        }
    }
}

private const val MAX_ECHOED_TOKEN_LENGTH = 40
