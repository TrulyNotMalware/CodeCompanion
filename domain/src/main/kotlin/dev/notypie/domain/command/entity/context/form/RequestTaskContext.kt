package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.interactions.isCompleted
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.history.entity.Status
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

internal class RequestTaskContext(
    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    events: EventQueue<CommandEvent<EventPayload>>,
    subCommand: SubCommand
) : CommandContext(
    commandBasicInfo = commandBasicInfo,
    slackEventBuilder = slackEventBuilder,
    requestHeaders = requestHeaders,
    events = events,
    subCommand = subCommand
) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REQUEST_TASK_FORM

    override fun runCommand(): CommandOutput {
        val event = this.slackEventBuilder.requestTaskFormRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandType = this.commandType,
            commandDetailType = this.commandDetailType
        )
        this.addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload)
    }

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        // assignees는 empty여도 됨
        val assignees = this.getAssignees(interactionPayload.states)
        val dueDate = this.getDueDate(interactionPayload.states)
        if(!interactionPayload.isCompleted())
            return this.createErrorResponse(errMessage = "")

        // 여기에는 검증
            // 필요한 값

            this.commandBasicInfo.publisherId
        return super.handleInteraction(interactionPayload)
    }

    private fun getTitle(states: List<States>): String? {
        return null
    }

    private fun getDueDate(states: List<States>): LocalDateTime? {
        val timeString = states.firstOrNull { it.type == ActionElementTypes.TIME_PICKER }
            ?.selectedValue
        val dateString = states.firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
            ?.selectedValue
        return if (timeString != null && dateString != null)
            LocalDateTime.parse("$dateString $timeString", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        else null
    }

    private fun getAssignees(states: List<States>): Set<String> =
        states.firstOrNull { state -> state.type == ActionElementTypes.MULTI_USERS_SELECT }
            ?.takeIf { state -> state.selectedValue.isNotEmpty() }
            ?.selectedValue
            ?.split(",")
            ?.toSet()
            ?: emptySet()
}

data class RequestTaskContextResult(
    override val ok: Boolean,
    override val status: Status,
    val commandBasicInfo: CommandBasicInfo,
    val assignees: Set<String> = emptySet(),
    val title: String
) : CommandOutput(
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

/*
open val ok: Boolean,
val apiAppId: String,
open val status: Status,
val commandDetailType: CommandDetailType, -> CommandDetiailType
val idempotencyKey: UUID, -> commandBasicInfo
val publisherId: String, -> commandBasicInfo
val channel: String, -> commandBasicInfo
val token: String = "", -> commandBasicInfo
val commandType: CommandType, -> CommandType
val actionStates: List<States> = listOf(),

val errorReason: String = ""
*/