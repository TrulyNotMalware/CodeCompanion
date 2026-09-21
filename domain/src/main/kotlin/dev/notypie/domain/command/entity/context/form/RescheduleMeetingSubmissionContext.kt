package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Executes the parsed reschedule submission from the modal opened by [RescheduleMeetingContext].
 * Date/time combining and rejection live in [RescheduleMeetingParsed]; the repository WHERE clause
 * still defends against a bogus uid downstream.
 */
internal class RescheduleMeetingSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: RescheduleMeetingParsed,
) : SubmissionContext<RescheduleMeetingParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT

    override fun accept(model: RescheduleMeetingParsed) =
        addIntent(
            CommandIntent.RescheduleMeeting(
                meetingUid = model.meetingUid,
                requesterId = model.requesterId,
                newStartAt = model.newStartAt,
            ),
        )
}
