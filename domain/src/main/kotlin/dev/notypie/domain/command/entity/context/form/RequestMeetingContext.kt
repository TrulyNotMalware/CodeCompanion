package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.isCompleted
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReplaceMessageContext
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

    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.requestMeetingFormRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandType = this.commandType,
            commandDetailType = this.commandDetailType
            )

    override fun handleInteraction(interactionPayload: InteractionPayload): SlackApiResponse {
        val timeString = interactionPayload.states
            .first { it.type == ActionElementTypes.TIME_PICKER }.selectedValue
        val dateString = interactionPayload.states
            .first { it.type == ActionElementTypes.DATE_PICKER }.selectedValue
        if( !this.isFutureTime(dateString = dateString, timeString = timeString) )
            return this.createErrorResponse(errorMessage = "Make sure to choose a time in the *future* rather than now.")
        if( !interactionPayload.isCompleted() )
            return this.createErrorResponse(errorMessage = "Please select *all options.*")

        return ReplaceMessageContext(
            commandBasicInfo = this.commandBasicInfo, requestHeaders = this.requestHeaders,
            slackApiRequester = this.slackApiRequester, responseUrl = interactionPayload.responseUrl,
            markdownMessage = "Successfully processed."
        ).runCommand()
    }

    private fun isFutureTime(dateString: String, timeString: String): Boolean{
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val date = LocalDate.parse(dateString, dateFormatter)
        val time = LocalTime.parse(timeString, timeFormatter)
        return LocalDateTime.of(date, time).isAfter(LocalDateTime.now())
    }
}