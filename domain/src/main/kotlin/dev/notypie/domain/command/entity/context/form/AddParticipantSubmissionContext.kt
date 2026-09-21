package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Executes the parsed add-participant submission from the modal opened by [AddParticipantContext].
 * Parsing and rejection live at the routing seam (SubmissionRouter / [AddParticipantParsed]); the
 * repository still defends host-only + capacity invariants downstream.
 */
internal class AddParticipantSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: AddParticipantParsed,
) : SubmissionContext<AddParticipantParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT

    override fun accept(model: AddParticipantParsed) =
        addIntent(
            CommandIntent.AddParticipant(
                meetingUid = model.meetingUid,
                requesterId = model.requesterId,
                participantUserIds = model.participantUserIds,
            ),
        )
}
