package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.asEchoedToken
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.STANDUP_COMMAND_IDENTIFIER
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.outbound.directFallbackOf
import dev.notypie.domain.common.escapeMarkup
import dev.notypie.domain.standup.dto.StandupAnswerCandidate
import dev.notypie.domain.standup.entity.Routine

internal class StandupSlashContext(
    commandBasicInfo: CommandBasicInfo,
    private val triggerHandle: String,
    private val answerCandidates: List<StandupAnswerCandidate> = emptyList(),
    subCommand: SubCommand<StandupSubCommandDefinition> =
        SubCommand.of(definition = StandupSubCommandDefinition.SETUP),
    intents: IntentQueue,
) : ReactionContext<StandupSubCommandDefinition>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    companion object {
        internal val STOP_USAGE_MESSAGE: String = "Usage: `${StandupSubCommandDefinition.STOP.usage.escapeMarkup()}`"
        internal const val NO_COLLECTING_STANDUP_MESSAGE: String =
            "No standup is collecting your answer in this channel right now."

        internal fun unknownCollectingStandupMessage(routineName: String): String =
            "No standup named `${routineName.asEchoedToken()}` is collecting your answer here."

        internal fun severalCollectingStandupsMessage(routineNames: List<String>): String =
            "Several standups are open: " +
                routineNames.joinToString(separator = ", ") { name -> "`${name.asRoutineNameToken()}`" } +
                ". Use `${"/$STANDUP_COMMAND_IDENTIFIER answer <routine-name>".escapeMarkup()}`."

        internal fun duplicateCollectingStandupsMessage(routineName: String): String =
            "Several standups named `${routineName.asRoutineNameToken()}` are open here; " +
                "use the *Fill in standup* button in your DM."

        private fun String.asRoutineNameToken(): String = replace(oldValue = "`", newValue = "'").escapeMarkup()
    }

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        when (subCommand.subCommandDefinition) {
            StandupSubCommandDefinition.NONE,
            StandupSubCommandDefinition.SETUP,
            -> openSetupModal()

            StandupSubCommandDefinition.LIST -> addIntent(intent = CommandIntent.ListStandupRoutines)

            StandupSubCommandDefinition.STOP -> {
                val routineName = Routine.normalizeName(raw = subCommand.options.joinToString(separator = " "))
                if (routineName.isBlank()) {
                    return usageError(message = STOP_USAGE_MESSAGE, commandDetailType = commandDetailType)
                }
                addIntent(intent = CommandIntent.StopStandupRoutine(routineName = routineName))
            }

            StandupSubCommandDefinition.ANSWER -> return answer(commandDetailType = commandDetailType)
        }
        return success(commandDetailType = commandDetailType)
    }

    private fun answer(commandDetailType: CommandDetailType): CommandOutput {
        if (answerCandidates.isEmpty()) {
            replyToRequester(markdown = NO_COLLECTING_STANDUP_MESSAGE)
            return success(commandDetailType = commandDetailType)
        }
        val routineName = Routine.normalizeName(raw = subCommand.options.joinToString(separator = " "))
        val matches =
            if (routineName.isBlank()) {
                answerCandidates
            } else {
                answerCandidates.filter { candidate -> candidate.isNamed(name = routineName) }
            }
        val firstMatch =
            matches.firstOrNull()
                ?: return usageError(
                    message = unknownCollectingStandupMessage(routineName = routineName),
                    commandDetailType = commandDetailType,
                )
        if (matches.size == 1) {
            openAnswerModal(candidate = firstMatch)
            return success(commandDetailType = commandDetailType)
        }
        val firstName = Routine.normalizeName(raw = firstMatch.routineName)
        val message =
            if (matches.all { candidate -> candidate.isNamed(name = firstName) }) {
                duplicateCollectingStandupsMessage(routineName = firstMatch.routineName)
            } else {
                severalCollectingStandupsMessage(
                    routineNames =
                        matches
                            .distinctBy { Routine.normalizeName(raw = it.routineName).lowercase() }
                            .map { it.routineName },
                )
            }
        return usageError(message = message, commandDetailType = commandDetailType)
    }

    private fun StandupAnswerCandidate.isNamed(name: String): Boolean =
        Routine.normalizeName(raw = routineName).equals(other = name, ignoreCase = true)

    private fun openSetupModal() {
        addOutbound(
            message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = triggerHandle),
                    form =
                        ModalForm.StandupSetup(
                            creatorId = commandBasicInfo.publisherId,
                            commandChannel = ConversationTarget(id = commandBasicInfo.channel),
                        ),
                ),
        )
    }

    private fun openAnswerModal(candidate: StandupAnswerCandidate) {
        addOutbound(
            message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = triggerHandle),
                    form =
                        ModalForm.StandupFill(
                            sessionUid = candidate.sessionUid,
                            routineUid = candidate.routineUid,
                            requesterId = commandBasicInfo.publisherId,
                            originNotice =
                                MessageRef(
                                    conversation = ConversationTarget(id = commandBasicInfo.channel),
                                    messageId = "",
                                ),
                        ),
                ),
        )
    }

    private fun replyToRequester(markdown: String) {
        val channel = ConversationTarget(id = commandBasicInfo.channel)
        val requester = UserRef(id = commandBasicInfo.publisherId)
        val content = MessageContent.Text(headline = null, markdown = markdown)
        addOutbound(
            message =
                OutboundMessage.Ephemeral(
                    target = channel,
                    recipient = requester,
                    content = content,
                    fallback = directFallbackOf(channel = channel, recipient = requester, content = content),
                ),
        )
    }

    private fun usageError(message: String, commandDetailType: CommandDetailType): CommandOutput {
        replyToRequester(markdown = message)
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
            reason = message,
        )
    }

    private fun success(commandDetailType: CommandDetailType): CommandOutput =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
}
