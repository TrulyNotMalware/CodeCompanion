package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.intent.StandupAnswerConfirmation
import dev.notypie.domain.command.outbound.MessageContent
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
                intent =
                    CommandIntent.RecordStandupAnswer(
                        sessionUid = model.sessionUid,
                        userId = model.userId,
                        responses = model.responses,
                        confirmation = model.confirmation,
                    ),
            )
            return
        }
        when (val confirmation = model.confirmation) {
            is StandupAnswerConfirmation.UpdateNotice ->
                addOutbound(
                    message =
                        OutboundMessage.UpdateMessage(
                            ref = confirmation.ref,
                            content = MessageContent.Text(headline = null, markdown = "Standup submitted."),
                            detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                        ),
                )

            is StandupAnswerConfirmation.Ephemeral, StandupAnswerConfirmation.None -> Unit
        }
    }
}
