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
import java.util.*

internal class InteractionCommandParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val slackApiRequester: SlackApiRequester
): ContextParser{
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(idempotencyKey: UUID): CommandContext =
        when(this.interactionPayload.type){
            CommandDetailType.APPROVAL_FORM ->
                SlackApprovalFormContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey)
                )
            CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
            CommandDetailType.REQUEST_MEETING_FORM ->
                RequestMeetingContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey)
                )
            CommandDetailType.NOTICE_FORM ->
                ApprovalCallbackContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey)
                )
            else -> EmptyContext(
                commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                requestHeaders = slackCommandData.rawHeader,
                slackApiRequester = slackApiRequester
            )
        }


}