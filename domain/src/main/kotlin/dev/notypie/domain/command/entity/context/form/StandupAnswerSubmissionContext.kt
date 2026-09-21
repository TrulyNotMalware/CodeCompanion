package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage

internal class StandupAnswerSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: StandupAnswerParsed,
) : SubmissionContext<StandupAnswerParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT

    override fun accept(model: StandupAnswerParsed) {
        if (model.responses.isNotEmpty()) {
            addIntent(
                CommandIntent.RecordStandupAnswer(
                    sessionUid = model.sessionUid,
                    userId = model.userId,
                    responses = model.responses,
                ),
            )
        }
        when (val notice = model.notice) {
            is NoticeTarget.Update ->
                addOutbound(
                    OutboundMessage.UpdateMessage(
                        ref =
                            MessageRef(
                                conversation = ConversationTarget(id = notice.channel),
                                messageId = notice.messageTs,
                            ),
                        content = MessageContent.Text(headline = null, markdown = "Standup submitted."),
                        detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    ),
                )

            NoticeTarget.None -> Unit
        }
    }
}
