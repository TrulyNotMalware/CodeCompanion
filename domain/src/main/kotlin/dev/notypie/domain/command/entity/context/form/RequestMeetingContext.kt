package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.isCanceled
import dev.notypie.domain.command.dto.interactions.isCompleted
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.MeetingListRange
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
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
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    subCommand: SubCommand<MeetingSubCommandDefinition> =
        SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
    intents: IntentQueue,
) : ReactionContext<MeetingSubCommandDefinition>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REQUEST_MEETING_FORM

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
        // Leave recipient null so the ephemeral posts into the command's channel and is
        // visible only to publisherId — chat.postEphemeral requires a channel ID, not a user ID.
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

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        // Deny (reject button) cancels the form outright — no field validation, no meeting created.
        if (interactionPayload.isCanceled()) {
            return interactionSuccessResponse(
                responseUrl = interactionPayload.responseUrl,
                mkdMessage = "Meeting request canceled.",
            )
        }

        val formInput = MeetingFormInput.from(payload = interactionPayload)
        validationErrorOrNull(formInput = formInput, payload = interactionPayload)?.let { return it }

        // The Meeting entity owns its own invariants (title/reason length, participant count, …).
        // Surface a violation as a user-visible ephemeral instead of letting the constructor throw,
        // which would fail the command silently with no response back to the user.
        val meeting =
            try {
                formInput.toMeeting()
            } catch (exception: CodeCompanionRuntimeException) {
                return createErrorResponse(errMessage = meetingValidationMessage(exception = exception))
            }

        // send notice
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
            responseUrl = interactionPayload.responseUrl,
            results =
                interactionResults(
                    status = Status.SUCCESS,
                    meeting = meeting,
                ),
        )
    }

    /**
     * Returns an error CommandOutput when the meeting form input is invalid, or null when it passes
     * validation and can be converted into a Meeting entity safely. The `isCompleted` check stays on
     * the raw payload because it inspects whether every interactive element was answered.
     */
    private fun validationErrorOrNull(formInput: MeetingFormInput, payload: InteractionPayload): CommandOutput? {
        val errorMessage =
            when {
                formInput.participants.isEmpty() -> "Select participants"

                formInput.startAt == null -> "Make sure to choose a time in the *future* rather than now."

                formInput.endAt != null && !formInput.endAt.isAfter(formInput.startAt) -> {
                    "End time must be after start time."
                }

                !payload.isCompleted() -> "Please select *all options.*"

                else -> return null
            }
        return createErrorResponse(errMessage = errorMessage)
    }

    /**
     * Renders a domain validation failure (raised by the Meeting entity) into a single user-facing
     * line. Falls back to a generic message when the exception carries no field details.
     */
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
                    // Must match the runCommand commandDetailType below so that the button
                    // value embedded by the Slack template routes clicks to the same context.
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                ),
            subCommand = SubCommand.empty(),
            intents = intents,
        ).runCommand(commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM)
            .status == Status.SUCCESS
}
