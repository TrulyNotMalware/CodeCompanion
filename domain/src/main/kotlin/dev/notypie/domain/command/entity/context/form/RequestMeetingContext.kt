package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.MeetingListRange
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.isCanceled
import dev.notypie.domain.command.inbound.isComplete
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.meet.entity.Meeting
import java.time.LocalDateTime

internal class RequestMeetingContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<MeetingSubCommandDefinition> =
        SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
    intents: IntentQueue,
) : ReactionContext<MeetingSubCommandDefinition>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_CREATE_REQUEST

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        when (subCommand.subCommandDefinition) {
            MeetingSubCommandDefinition.LIST -> return runListSubCommand(commandDetailType = commandDetailType)

            else -> {
                addOutbound(
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = commandBasicInfo.channel),
                        content = MessageContent.MeetingRequest(approval = null),
                    ),
                )
            }
        }
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun runListSubCommand(commandDetailType: CommandDetailType): CommandOutput {
        val nonBlankOptions = subCommand.options.filter { option -> option.isNotBlank() }
        if (nonBlankOptions.size > 1) {
            return listArgumentError(
                commandDetailType = commandDetailType,
                message = "Too many arguments. Usage: /meetup list [${MeetingListRange.usageTokens()}]",
            )
        }
        val token = nonBlankOptions.firstOrNull().orEmpty()
        val range =
            when {
                token.isBlank() -> MeetingListRange.DEFAULT
                else ->
                    MeetingListRange.parseOrNull(token = token)
                        ?: return listArgumentError(
                            commandDetailType = commandDetailType,
                            message = "Unknown range '$token'. Usage: /meetup list [${MeetingListRange.usageTokens()}]",
                        )
            }
        val (startAt, endAt) = range.dateRange(now = LocalDateTime.now())
        addIntent(
            CommandIntent.MeetingListRequest(
                publisherId = commandBasicInfo.publisherId,
                startDate = startAt,
                endDate = endAt,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun listArgumentError(commandDetailType: CommandDetailType, message: String): CommandOutput {
        // Null recipient: chat.postEphemeral requires a channel ID, not a user ID.
        addOutbound(
            OutboundMessage.Ephemeral(
                target = ConversationTarget(id = commandBasicInfo.channel),
                recipient = null,
                content = MessageContent.Text(headline = null, markdown = message),
            ),
        )
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
            reason = message,
        )
    }

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        // Deny cancels outright — no validation, no meeting created.
        if (interaction.isCanceled()) {
            return interactionSuccessResponse(
                responseUrl = interaction.reply.raw,
                mkdMessage = "Meeting request canceled.",
            )
        }

        val formInput = MeetingFormInput.from(interaction = interaction)
        validationErrorOrNull(formInput = formInput, interaction = interaction)?.let { return it }

        // Surface Meeting's own invariant violations as an ephemeral instead of throwing silently.
        val meeting =
            try {
                formInput.toMeeting()
            } catch (exception: CodeCompanionRuntimeException) {
                return createErrorResponse(errMessage = meetingValidationMessage(exception = exception))
            }

        if (formInput.noticeRequired && !sendNotice(meeting = meeting)) {
            return createErrorResponse(
                errMessage = "Failed to send notice. Please try again later",
                results =
                    interactionResults(
                        status = Status.FAILED,
                        meeting = meeting,
                    ),
            )
        }

        return interactionSuccessResponse(
            responseUrl = interaction.reply.raw,
            results =
                interactionResults(
                    status = Status.SUCCESS,
                    meeting = meeting,
                ),
        )
    }

    /**
     * Error CommandOutput when the form input is invalid, else null. The `isComplete` check stays on
     * the raw interaction because it inspects whether every interactive element was answered.
     */
    private fun validationErrorOrNull(formInput: MeetingFormInput, interaction: InboundInteraction): CommandOutput? {
        val errorMessage =
            when {
                formInput.participants.isEmpty() -> "Select participants"

                formInput.startAt == null -> "Make sure to choose a time in the *future* rather than now."

                formInput.endAt != null && !formInput.endAt.isAfter(formInput.startAt) -> {
                    "End time must be after start time."
                }

                !interaction.isComplete() -> "Please select *all options.*"

                else -> return null
            }
        return createErrorResponse(errMessage = errorMessage)
    }

    /** Renders a domain validation failure into user-facing lines, or a generic message when empty. */
    private fun meetingValidationMessage(exception: CodeCompanionRuntimeException): String =
        exception.details
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { detail -> "${detail.fieldName}: ${detail.reason}" }
            ?: "Invalid meeting details. Please check your input and try again."

    private fun interactionResults(status: Status, meeting: Meeting) =
        RequestMeetingContextResult(
            ok = true,
            status = status,
            commandBasicInfo = commandBasicInfo,
            meeting = meeting,
        )

    private fun sendNotice(meeting: Meeting): Boolean =
        ApprovalCallbackContext(
            participants = meeting.memberIdSnapshot(),
            commandBasicInfo = commandBasicInfo,
            approvalContents =
                ApprovalContents(
                    headLineText = "Meeting Request!",
                    reason = meeting.reason,
                    subTitle = meeting.title,
                    idempotencyKey = commandBasicInfo.idempotencyKey,
                    publisherId = commandBasicInfo.publisherId,
                    // Must match runCommand's commandDetailType below so button clicks route back here.
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
                ),
            subCommand = SubCommand.empty(),
            intents = intents,
        ).runCommand(commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST)
            .status == Status.SUCCESS
}
