package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

internal class StandupSetupSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: StandupSetupParsed,
) : SubmissionContext<StandupSetupParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT

    override fun accept(model: StandupSetupParsed) =
        addIntent(
            CommandIntent.CreateStandupRoutine(
                name = model.name,
                creatorId = model.creatorId,
                commandChannel = model.commandChannel,
                summaryChannel = model.summaryChannel,
                questions = model.questions,
                memberIds = model.memberIds,
                weekdays = model.weekdays,
                triggerLocalTime = model.triggerLocalTime,
                cutoffMinutes = model.cutoffMinutes,
                timezone = model.timezone,
            ),
        )
}
