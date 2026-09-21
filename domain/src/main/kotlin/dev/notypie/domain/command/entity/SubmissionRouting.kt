package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.context.IgnoredSubmissionContext
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.entity.context.form.AddParticipantParsed
import dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveSubscribeParsed
import dev.notypie.domain.command.entity.context.form.CveSubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeParsed
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.DeclineReasonParsed
import dev.notypie.domain.command.entity.context.form.DeclineReasonSubmissionContext
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingParsed
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupAnswerParsed
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupSetupParsed
import dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.inbound.SubmissionIgnoreReason
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.domain.command.intent.IntentQueue

internal val CommandDetailType.isSubmissionRoute: Boolean
    get() =
        when (this) {
            CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
            CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
            CommandDetailType.MEETING_DECLINE_REASON,
            CommandDetailType.STANDUP_ANSWER_SUBMIT,
            CommandDetailType.STANDUP_SETUP_SUBMIT,
            CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
            CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
            -> true

            else -> false
        }

internal fun InboundSubmission.detailType(): CommandDetailType =
    when (this) {
        is InboundSubmission.RescheduleMeeting -> CommandDetailType.MEETING_RESCHEDULE_SUBMIT
        is InboundSubmission.AddParticipant -> CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
        is InboundSubmission.DeclineReason -> CommandDetailType.MEETING_DECLINE_REASON
        is InboundSubmission.StandupAnswer -> CommandDetailType.STANDUP_ANSWER_SUBMIT
        is InboundSubmission.StandupSetup -> CommandDetailType.STANDUP_SETUP_SUBMIT
        is InboundSubmission.CveSubscribe -> CommandDetailType.CVE_SUBSCRIBE_SUBMIT
        is InboundSubmission.CveUnsubscribe -> CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT
    }

internal class SubmissionRouter(
    private val commandBasicInfo: CommandBasicInfo,
    private val intents: IntentQueue,
    private val observer: SubmissionParseObserver = SubmissionParseObserver.NONE,
) {
    fun route(interaction: InboundInteraction): ReactionContext<NoSubCommands>? {
        val submission =
            interaction.submission
                ?: return interaction.detailType.takeIf { it.isSubmissionRoute }?.let { detailType ->
                    ignored(detailType = detailType, reason = SubmissionIgnoreReason.MISSING_SUBMISSION)
                }
        val actorId = interaction.actor.id
        return when (submission) {
            is InboundSubmission.RescheduleMeeting ->
                accept(
                    model = RescheduleMeetingParsed.from(raw = submission, actorId = actorId),
                    submission = submission,
                ) {
                    RescheduleMeetingSubmissionContext(
                        commandBasicInfo = commandBasicInfo,
                        intents = intents,
                        model = it,
                    )
                }

            is InboundSubmission.AddParticipant ->
                accept(
                    model = AddParticipantParsed.from(raw = submission, actorId = actorId),
                    submission = submission,
                ) {
                    AddParticipantSubmissionContext(commandBasicInfo = commandBasicInfo, intents = intents, model = it)
                }

            is InboundSubmission.DeclineReason ->
                accept(model = DeclineReasonParsed.from(raw = submission, actorId = actorId), submission = submission) {
                    DeclineReasonSubmissionContext(commandBasicInfo = commandBasicInfo, intents = intents, model = it)
                }

            is InboundSubmission.StandupAnswer ->
                accept(model = StandupAnswerParsed.from(raw = submission, actorId = actorId), submission = submission) {
                    StandupAnswerSubmissionContext(commandBasicInfo = commandBasicInfo, intents = intents, model = it)
                }

            is InboundSubmission.StandupSetup ->
                accept(model = StandupSetupParsed.from(raw = submission, actorId = actorId), submission = submission) {
                    StandupSetupSubmissionContext(commandBasicInfo = commandBasicInfo, intents = intents, model = it)
                }

            is InboundSubmission.CveSubscribe ->
                accept(model = CveSubscribeParsed.from(raw = submission, actorId = actorId), submission = submission) {
                    CveSubscribeSubmissionContext(commandBasicInfo = commandBasicInfo, intents = intents, model = it)
                }

            is InboundSubmission.CveUnsubscribe ->
                accept(
                    model = CveUnsubscribeParsed.from(raw = submission, actorId = actorId),
                    submission = submission,
                ) {
                    CveUnsubscribeSubmissionContext(commandBasicInfo = commandBasicInfo, intents = intents, model = it)
                }
        }
    }

    private fun <M : Any> accept(
        model: M?,
        submission: InboundSubmission,
        build: (M) -> SubmissionContext<M>,
    ): ReactionContext<NoSubCommands> =
        model?.let(build)
            ?: ignored(detailType = submission.detailType(), reason = SubmissionIgnoreReason.PARSE_REJECTED)

    private fun ignored(detailType: CommandDetailType, reason: SubmissionIgnoreReason): IgnoredSubmissionContext {
        observer.ignored(detailType = detailType, reason = reason)
        return IgnoredSubmissionContext(
            commandBasicInfo = commandBasicInfo,
            intents = intents,
            detailType = detailType,
        )
    }
}
