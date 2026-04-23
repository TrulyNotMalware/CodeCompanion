package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.interactions.isCompleted
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.MeetingListRange
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.history.entity.Status
import dev.notypie.domain.meet.entity.Meeting
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
    companion object {
        internal const val DATE_PATTERN = "yyyy-MM-dd"
        internal const val SIMPLE_TIME_PATTERN = "HH:mm"
        internal const val DEFAULT_MEETING_TITLE = "New Meeting"
        internal const val DEFAULT_MEETING_REASON = "request meeting"
    }

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REQUEST_MEETING_FORM

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        when (subCommand.subCommandDefinition) {
            MeetingSubCommandDefinition.LIST -> return runListSubCommand(commandDetailType = commandDetailType)

            else -> {
                addIntent(CommandIntent.MeetingForm())
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
        // Leave targetUserId null so the ephemeral posts into the command's channel and is
        // visible only to publisherId — chat.postEphemeral requires a channel ID, not a user ID.
        addIntent(CommandIntent.EphemeralResponse(message = message))
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
            reason = message,
        )
    }

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        validationErrorOrNull(payload = interactionPayload)?.let { return it }

        val meeting = toMeetingEntity(payload = interactionPayload)

        // send notice
        if (isNoticeRequired(interactionPayload = interactionPayload) &&
            !sendNotice(meeting = meeting)
        ) {
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
     * Returns an error CommandOutput when the meeting form input is invalid, or null when the
     * payload passes validation and can be converted into a Meeting entity safely.
     */
    private fun validationErrorOrNull(payload: InteractionPayload): CommandOutput? {
        val startAt = getStartDateTimeOrNull(interactionPayload = payload)
        val endAt = getEndDateTimeOrNull(interactionPayload = payload, startAt = startAt)
        val errorMessage =
            when {
                getParticipants(states = payload.states, publisher = payload.user.id).isEmpty() -> {
                    "Select participants"
                }

                startAt == null -> {
                    "Make sure to choose a time in the *future* rather than now."
                }

                endAt != null && !endAt.isAfter(startAt) -> {
                    "End time must be after start time."
                }

                !payload.isCompleted() -> {
                    "Please select *all options.*"
                }

                else -> {
                    return null
                }
            }
        return createErrorResponse(errMessage = errorMessage)
    }

    /**
     * Precondition: [validationErrorOrNull] returned null. Guaranteed to have a non-null future startAt
     * and (if present) an endAt strictly after startAt.
     */
    private fun toMeetingEntity(payload: InteractionPayload): Meeting {
        val publisher = payload.user.id
        val participants = getParticipants(states = payload.states, publisher = payload.user.id)
        val startAt =
            requireNotNull(getStartDateTimeOrNull(interactionPayload = payload)) {
                "Meeting startAt must be validated before building Meeting entity"
            }
        val endAt = getEndDateTimeOrNull(interactionPayload = payload, startAt = startAt)
        val (title, reason) = getTitleAndReason(interactionPayload = payload)

        return if (endAt != null) {
            Meeting(
                publisher = publisher,
                title = title,
                reason = reason,
                startAt = startAt,
                endAt = endAt,
                members = participants,
            )
        } else {
            Meeting(
                publisher = publisher,
                title = title,
                reason = reason,
                startAt = startAt,
                members = participants,
            )
        }
    }

    private fun interactionResults(status: Status, meeting: Meeting) =
        RequestMeetingContextResult(
            ok = true,
            status = status,
            commandBasicInfo = commandBasicInfo,
            meeting = meeting,
        )

    private fun getParticipants(states: List<States>, publisher: String): Set<String> =
        states
            .firstOrNull { state -> state.type == ActionElementTypes.MULTI_USERS_SELECT }
            ?.takeIf { state -> state.selectedValue.isNotEmpty() }
            ?.selectedValue
            ?.split(",")
            ?.filter { participant -> participant != publisher }
            ?.toSet()
            ?: emptySet()

    /**
     * Extracts the meeting start datetime from the payload.
     *
     * The modal exposes two TIME_PICKERs (start, end) in order. This reads the first one.
     * Returns null if date/start-time is missing or the combined moment is not strictly in the future.
     */
    private fun getStartDateTimeOrNull(interactionPayload: InteractionPayload): LocalDateTime? {
        val timeString =
            interactionPayload.states
                .filter { it.type == ActionElementTypes.TIME_PICKER && it.selectedValue.isNotBlank() }
                .getOrNull(index = 0)
                ?.selectedValue
        val dateString =
            interactionPayload.states
                .firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
                ?.selectedValue
        return if (timeString != null && dateString != null && isFutureTime(dateString, timeString)) {
            parseLocalDateTime(dateString = dateString, timeString = timeString)
        } else {
            null
        }
    }

    /**
     * Extracts the optional meeting end datetime from the payload.
     *
     * The modal exposes two TIME_PICKERs (start, end) in order. This reads the second one.
     * End uses the same DATE_PICKER as start. Returns null when the end-time picker is empty,
     * when no valid [startAt] was supplied, or when the date picker is missing.
     */
    private fun getEndDateTimeOrNull(interactionPayload: InteractionPayload, startAt: LocalDateTime?): LocalDateTime? {
        if (startAt == null) return null
        val endTimeString =
            interactionPayload.states
                .filter { it.type == ActionElementTypes.TIME_PICKER && it.selectedValue.isNotBlank() }
                .getOrNull(index = 1)
                ?.selectedValue
                ?: return null
        val dateString =
            interactionPayload.states
                .firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
                ?.selectedValue
                ?: return null
        return parseLocalDateTime(dateString = dateString, timeString = endTimeString)
    }

    private fun parseLocalDateTime(dateString: String, timeString: String): LocalDateTime =
        LocalDateTime.parse(
            "$dateString $timeString",
            DateTimeFormatter.ofPattern("$DATE_PATTERN $SIMPLE_TIME_PATTERN"),
        )

    private fun getTitleAndReason(interactionPayload: InteractionPayload): Pair<String, String> =
        interactionPayload.states
            .filter { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
            .takeIf { it.size >= 2 }
            ?.let {
                val title = it[0].selectedValue.ifBlank { DEFAULT_MEETING_TITLE }
                val reason = it[1].selectedValue.ifBlank { DEFAULT_MEETING_REASON }
                title to reason
            } ?: (DEFAULT_MEETING_TITLE to DEFAULT_MEETING_REASON)

    private fun isFutureTime(dateString: String, timeString: String): Boolean {
        val dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN)
        val timeFormatter = DateTimeFormatter.ofPattern(SIMPLE_TIME_PATTERN)

        val date = LocalDate.parse(dateString, dateFormatter)
        val time = LocalTime.parse(timeString, timeFormatter)
        return LocalDateTime.of(date, time).isAfter(LocalDateTime.now())
    }

    private fun isNoticeRequired(interactionPayload: InteractionPayload): Boolean =
        interactionPayload.states
            .firstOrNull { it.type == ActionElementTypes.CHECKBOX }
            ?.isSelected ?: false

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
