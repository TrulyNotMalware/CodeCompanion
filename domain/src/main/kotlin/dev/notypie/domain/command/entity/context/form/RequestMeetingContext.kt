package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SlackApiRequester
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
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.history.entity.Status
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal class RequestMeetingContext(
    commandBasicInfo: CommandBasicInfo,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
) : CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo
){
    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REQUEST_MEETING_FORM

    override fun runCommand(): CommandOutput =
        this.slackApiRequester.requestMeetingFormRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandType = this.commandType,
            commandDetailType = this.commandDetailType
            )

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val participants = this.getParticipantsOrNull(interactionPayload = interactionPayload)
            ?: return this.createErrorResponse(errMessage = "Select participants")
        val (dateString, timeString) = this.getDateTimeOrNull(interactionPayload = interactionPayload)
            ?: return this.createErrorResponse(errMessage = "Make sure to choose a time in the *future* rather than now.")
        if( !interactionPayload.isCompleted() )
            return this.createErrorResponse(errMessage = "Please select *all options.*")
        val (title, reason) = getTitleAndReason(interactionPayload = interactionPayload)
        // send notice
        if(isNoticeRequired(interactionPayload = interactionPayload)){
            val sendNoticeResults = sendNotice(participants = participants, title = title, reason = reason)
            if(sendNoticeResults.status != Status.SUCCESS)
                return this.createErrorResponse(
                    errMessage = "Failed to send notice. Please try again later",
                    results = this.interactionResults(Status.FAILED, participants)
                )
        }
        return this.interactionSuccessResponse(
            responseUrl = interactionPayload.responseUrl,
            results = this.interactionResults(Status.SUCCESS, participants)
        )
    }

    private fun interactionResults(status: Status, participants: Set<String>) =
        RequestMeetingContextResult(
            ok = true, status = status, commandBasicInfo = this.commandBasicInfo,
            participants = participants
        )

    private fun getParticipantsOrNull(interactionPayload: InteractionPayload): Set<String>? =
        this.getParticipants(
            states = interactionPayload.states,
            publisher = interactionPayload.user.id
        ).ifEmpty { null }


    private fun getParticipants(states: List<States>, publisher: String): Set<String> =
        states.firstOrNull { state -> state.type == ActionElementTypes.MULTI_USERS_SELECT }
            ?.takeIf { state -> state.selectedValue.isNotEmpty() }
            ?.selectedValue
            ?.split(",")
            ?.filter { participant -> participant != publisher }
            ?.toSet()
        ?: emptySet()


    private fun getDateTimeOrNull(interactionPayload: InteractionPayload): Pair<String, String>? {
        val timeString = interactionPayload.states
            .firstOrNull { it.type == ActionElementTypes.TIME_PICKER }
            ?.selectedValue
        val dateString = interactionPayload.states
            .firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
            ?.selectedValue
        return if (timeString != null && dateString != null && isFutureTime(dateString, timeString)) {
            dateString to timeString
        } else {
            null
        }
    }

    private fun getTitleAndReason(interactionPayload: InteractionPayload): Pair<String, String> =
        interactionPayload.states.filter { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
            .takeIf { it.size >= 2 }?.let {
                val title = it[0].selectedValue.ifBlank { "Request Meeting" }
                val reason = it[1].selectedValue.ifBlank { "request meeting" }
                title to reason
            } ?: ("Request Meeting" to "request meeting")


    private fun isFutureTime(dateString: String, timeString: String): Boolean{
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val date = LocalDate.parse(dateString, dateFormatter)
        val time = LocalTime.parse(timeString, timeFormatter)
        return LocalDateTime.of(date, time).isAfter(LocalDateTime.now())
    }

    private fun isNoticeRequired(interactionPayload: InteractionPayload): Boolean =
        interactionPayload.states
            .firstOrNull { it.type == ActionElementTypes.CHECKBOX }
            ?.isSelected ?: false

    private fun sendNotice(participants: Set<String>, title: String, reason: String): CommandOutput =
        ApprovalCallbackContext(
            slackApiRequester = this.slackApiRequester, participants = participants,
            commandBasicInfo = this.commandBasicInfo,
            approvalContents = ApprovalContents(
                headLineText = "Meeting Request!", reason = reason, subTitle = title,
                idempotencyKey = this.commandBasicInfo.idempotencyKey,
                publisherId = this.commandBasicInfo.publisherId,
                commandDetailType = CommandDetailType.NOTICE_FORM
            )
        ).runCommand(commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM)


}

data class RequestMeetingContextResult(
    override val ok: Boolean,
    override val status: Status,
    val commandBasicInfo: CommandBasicInfo,
    val participants: Set<String> = emptySet()
): CommandOutput(
    ok = ok,
    status = status,
    apiAppId = commandBasicInfo.appId,
    idempotencyKey = commandBasicInfo.idempotencyKey,
    publisherId = commandBasicInfo.publisherId,
    channel = commandBasicInfo.channel,
    token = commandBasicInfo.appToken,
    commandType = CommandType.PIPELINE,
    commandDetailType = CommandDetailType.REQUEST_MEETING_FORM
)