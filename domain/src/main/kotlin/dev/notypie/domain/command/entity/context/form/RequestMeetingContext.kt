package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
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
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.GetMeetingEventPayload
import dev.notypie.domain.common.event.GetMeetingListEvent
import dev.notypie.domain.common.event.SendSlackMessageEvent
import dev.notypie.domain.history.entity.Status
import dev.notypie.domain.meet.dto.Meeting
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal class RequestMeetingContext(
    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    events: EventQueue<CommandEvent<EventPayload>>,
    subCommand: SubCommand,
) : ReactionContext(
        slackEventBuilder = slackEventBuilder,
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        events = events,
        subCommand = subCommand,
        requiredSubCommandType = MeetingSubCommandDefinition::class,
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
        val event =
            slackEventBuilder.requestMeetingFormRequest(
                commandBasicInfo = commandBasicInfo,
                commandType = commandType,
                commandDetailType = commandDetailType,
            )
        when (subCommand.subCommandDefinition) {
            MeetingSubCommandDefinition.LIST -> addNewEvent(commandEvent = getListCommand())
            else -> addNewEvent(commandEvent = event)
        }

        return CommandOutput.success(payload = event.payload, commandType = commandType)
    }

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    private fun getListCommand() =
        GetMeetingListEvent(
            idempotencyKey = commandBasicInfo.idempotencyKey,
            payload =
                GetMeetingEventPayload(
                    slackEventModifier = this::apply,
                    publisherId = commandBasicInfo.publisherId,
                ),
            type = CommandDetailType.GET_MEETING_LIST,
        )

    private fun apply(myMeetings: List<Meeting>): SendSlackMessageEvent =
        slackEventBuilder.getMeetingListFormRequest(
            commandBasicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
            myMeetings = myMeetings,
        )

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val meetingRequest =
            validateAndExtractMeetingRequest(interactionPayload)
                ?: return createValidationErrorResponse(interactionPayload)

        // send notice
        if (isNoticeRequired(interactionPayload = interactionPayload) &&
            !sendNotice(meetingRequest = meetingRequest)
        ) {
            return createErrorResponse(
                errMessage = "Failed to send notice. Please try again later",
                results =
                    interactionResults(
                        status = Status.FAILED,
                        participants = meetingRequest.participants,
                        name = meetingRequest.title,
                        startAt = meetingRequest.startAt,
                    ),
            )
        }

        return interactionSuccessResponse(
            responseUrl = interactionPayload.responseUrl,
            results =
                interactionResults(
                    status = Status.SUCCESS,
                    name = meetingRequest.title,
                    participants = meetingRequest.participants,
                    startAt = meetingRequest.startAt,
                ),
        )
    }

    private fun createValidationErrorResponse(payload: InteractionPayload): CommandOutput {
        val errorMessage =
            when {
                getParticipants(states = payload.states, publisher = payload.user.id).isEmpty() -> "Select participants"
                getDateTimeOrNull(
                    interactionPayload = payload,
                ) == null -> "Make sure to choose a time in the *future* rather than now."
                !payload.isCompleted() -> "Please select *all options.*"
                else -> "Unknown error occurred. Please try again later."
            }
        return createErrorResponse(errMessage = errorMessage)
    }

    private fun validateAndExtractMeetingRequest(payload: InteractionPayload): MeetingRequest? {
        val participants = getParticipants(states = payload.states, publisher = payload.user.id)
        val startAt = getDateTimeOrNull(interactionPayload = payload)
        val (title, reason) = getTitleAndReason(interactionPayload = payload)

        return when {
            participants.isEmpty() -> null
            startAt == null -> null
            !payload.isCompleted() -> null
            else -> MeetingRequest(participants, startAt, title, reason, payload.user.id)
        }
    }

    private fun interactionResults(
        status: Status,
        name: String,
        participants: Set<String>,
        startAt: LocalDateTime,
    ) = RequestMeetingContextResult(
        ok = true,
        status = status,
        commandBasicInfo = commandBasicInfo,
        participants = participants,
        startAt = startAt,
        name = name,
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

    private fun getDateTimeOrNull(interactionPayload: InteractionPayload): LocalDateTime? {
        val timeString =
            interactionPayload.states
                .firstOrNull { it.type == ActionElementTypes.TIME_PICKER }
                ?.selectedValue
        val dateString =
            interactionPayload.states
                .firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
                ?.selectedValue
        return if (timeString != null && dateString != null && isFutureTime(dateString, timeString)) {
            LocalDateTime.parse(
                "$dateString $timeString",
                DateTimeFormatter.ofPattern("$DATE_PATTERN $SIMPLE_TIME_PATTERN"),
            )
        } else {
            null
        }
    }

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

    private fun sendNotice(meetingRequest: MeetingRequest): Boolean =
        ApprovalCallbackContext(
            slackEventBuilder = slackEventBuilder,
            participants = meetingRequest.participants,
            commandBasicInfo = commandBasicInfo,
            approvalContents =
                ApprovalContents(
                    headLineText = "Meeting Request!",
                    reason = meetingRequest.reason,
                    subTitle = meetingRequest.title,
                    idempotencyKey = commandBasicInfo.idempotencyKey,
                    publisherId = commandBasicInfo.publisherId,
                    commandDetailType = CommandDetailType.NOTICE_FORM,
                ),
            events = events,
        ).runCommand(commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM)
            .status == Status.SUCCESS
}

private data class MeetingRequest(
    val participants: Set<String>,
    val startAt: LocalDateTime,
    val title: String,
    val reason: String,
    val publisherId: String,
)

data class RequestMeetingContextResult(
    override val ok: Boolean,
    override val status: Status,
    val commandBasicInfo: CommandBasicInfo,
    val participants: Set<String> = emptySet(),
    val startAt: LocalDateTime,
    val name: String,
) : CommandOutput(
        ok = ok,
        status = status,
        apiAppId = commandBasicInfo.appId,
        idempotencyKey = commandBasicInfo.idempotencyKey,
        publisherId = commandBasicInfo.publisherId,
        channel = commandBasicInfo.channel,
        token = commandBasicInfo.appToken,
        commandType = CommandType.PIPELINE,
        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
    )
