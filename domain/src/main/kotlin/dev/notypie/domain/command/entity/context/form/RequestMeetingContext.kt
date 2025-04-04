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
        val participants = this.getParticipants(
            states = interactionPayload.states, publisher = interactionPayload.user.id
        )
        if( participants.isEmpty() ) return this.createErrorResponse(errorMessage = "Select participants")
        val timeString = interactionPayload.states
            .first { it.type == ActionElementTypes.TIME_PICKER }.selectedValue
        val dateString = interactionPayload.states
            .first { it.type == ActionElementTypes.DATE_PICKER }.selectedValue
        if( !this.isFutureTime(dateString = dateString, timeString = timeString) )
            return this.createErrorResponse(errorMessage = "Make sure to choose a time in the *future* rather than now.")
        if( !interactionPayload.isCompleted() )
            return this.createErrorResponse(errorMessage = "Please select *all options.*")
        val nameAndReasons = interactionPayload.states.filter{ it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
        val (title, reason) = nameAndReasons
            .takeIf { it.size >= 2 }
            ?.let {
                val titleValue = it[0].selectedValue.ifBlank { "Request Meeting" }
                val reasonValue = it[1].selectedValue.ifBlank { "request meeting" }
                titleValue to reasonValue
            }
            ?: ("Request Meeting" to "request meeting")
        // send notice
        if(interactionPayload.states.first { state -> state.type == ActionElementTypes.CHECKBOX }.isSelected){
            ApprovalCallbackContext(
                slackApiRequester = this.slackApiRequester, participants = participants,
                commandBasicInfo = this.commandBasicInfo,
                approvalContents = ApprovalContents(
                    headLineText = "Meeting Request!", reason = reason, subTitle = title,
                    idempotencyKey = this.commandBasicInfo.idempotencyKey,
                    publisherId = this.commandBasicInfo.publisherId,
                    commandDetailType = CommandDetailType.NOTICE_FORM
                )
            ).runCommand()
        }
        return this.interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
    }

    private fun getParticipants(states: List<States>, publisher: String): Set<String> =
        states.firstOrNull { state -> state.type == ActionElementTypes.MULTI_USERS_SELECT }
            ?.takeIf { state -> state.selectedValue.isNotEmpty() }
            ?.selectedValue
            ?.split(",")
            ?.filter { participant -> participant != publisher }
            ?.toSet()
        ?: emptySet()


    private fun isFutureTime(dateString: String, timeString: String): Boolean{
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val date = LocalDate.parse(dateString, dateFormatter)
        val time = LocalTime.parse(timeString, timeFormatter)
        return LocalDateTime.of(date, time).isAfter(LocalDateTime.now())
    }
}