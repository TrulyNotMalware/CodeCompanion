package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.*

internal class InteractionCommandParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val slackApiRequester: SlackApiRequester,
    private val events: Queue<CommandEvent<EventPayload>>
): ContextParser{
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(events: Queue<CommandEvent<EventPayload>>, idempotencyKey: UUID): CommandContext =
        when(this.interactionPayload.type){
            CommandDetailType.APPROVAL_FORM ->
                SlackApprovalFormContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                    events = events,
                )
            CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
            CommandDetailType.REQUEST_MEETING_FORM ->
                RequestMeetingContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                    events = events,
                )
            CommandDetailType.NOTICE_FORM ->
                ApprovalCallbackContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                    events = events,
                )
            else -> EmptyContext(
                commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                requestHeaders = slackCommandData.rawHeader,
                slackApiRequester = slackApiRequester,
                events = events,
            )
        }


}