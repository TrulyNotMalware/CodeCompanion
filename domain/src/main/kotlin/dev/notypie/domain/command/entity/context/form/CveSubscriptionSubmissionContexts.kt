package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

internal class CveSubscribeSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: CveSubscribeParsed,
) : SubmissionContext<CveSubscribeParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT

    override fun accept(model: CveSubscribeParsed) =
        addIntent(
            CommandIntent.CveSubscribe(
                userId = model.userId,
                topicKeys = model.topicKeys,
            ),
        )
}

internal class CveUnsubscribeSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: CveUnsubscribeParsed,
) : SubmissionContext<CveUnsubscribeParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT

    override fun accept(model: CveUnsubscribeParsed) =
        addIntent(
            CommandIntent.CveUnsubscribe(
                userId = model.userId,
                topicKeys = model.topicKeys,
            ),
        )
}
