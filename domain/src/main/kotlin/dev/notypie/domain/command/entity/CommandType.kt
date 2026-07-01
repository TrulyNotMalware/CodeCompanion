package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.form.AddParticipantContext
import dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.CancelMeetingContext
import dev.notypie.domain.command.entity.context.form.DeclineReasonSubmissionContext
import dev.notypie.domain.command.entity.context.form.MeetingApprovalResponseContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.context.form.RequestStandupSetupContext
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingContext
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupFillContext
import dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.intent.IntentQueue

enum class CommandType {
    SIMPLE,
    PIPELINE,
    RESPONSE,
    EXTERNAL_API,
}

enum class CommandDetailType {
    NOTHING,
    SIMPLE_TEXT,
    REPLACE_TEXT,
    ERROR_RESPONSE,
    APPROVAL_FORM,
    REQUEST_APPLY_FORM,

    REQUEST_MEETING_FORM,
    GET_MEETING_LIST,
    MEETING_APPROVAL_NOTICE_FORM,
    DECLINE_REASON_MODAL,
    CANCEL_MEETING,
    RESCHEDULE_MEETING,
    RESCHEDULE_MEETING_SUBMIT,
    ADD_PARTICIPANT,
    ADD_PARTICIPANT_SUBMIT,
    MEETING_REMINDER,
    DAILY_AGENDA,
    STATUS_REPORT,
    STANDUP_FILL,
    STANDUP_ANSWER_SUBMIT,
    STANDUP_SETUP_FORM,
    STANDUP_SETUP_SUBMIT,
    STANDUP_SUMMARY,
    NOTICE_FORM,
    ;

    internal fun createContext(
        commandBasicInfo: CommandBasicInfo,
        subCommand: SubCommand<NoSubCommands>,
        intents: IntentQueue,
    ): CommandContext<out SubCommandDefinition> =
        when (this) {
            APPROVAL_FORM -> {
                ApprovalFormContext(
                    commandBasicInfo = commandBasicInfo,
                    intents = intents,
                )
            }

            REQUEST_MEETING_FORM -> {
                RequestMeetingContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand =
                        SubCommand(
                            subCommandDefinition = MeetingSubCommandDefinition.NONE,
                        ),
                    intents = intents,
                )
            }

            MEETING_APPROVAL_NOTICE_FORM -> {
                MeetingApprovalResponseContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            DECLINE_REASON_MODAL -> {
                DeclineReasonSubmissionContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            CANCEL_MEETING -> {
                CancelMeetingContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            RESCHEDULE_MEETING -> {
                RescheduleMeetingContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            RESCHEDULE_MEETING_SUBMIT -> {
                RescheduleMeetingSubmissionContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            ADD_PARTICIPANT -> {
                AddParticipantContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            ADD_PARTICIPANT_SUBMIT -> {
                AddParticipantSubmissionContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            STANDUP_FILL -> {
                StandupFillContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            STANDUP_ANSWER_SUBMIT -> {
                StandupAnswerSubmissionContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            STANDUP_SETUP_FORM -> {
                // The slash entry point builds this context directly with the live trigger_id
                // (see SetupStandupCommand). This branch only exists for completeness so an
                // interaction routed here still resolves to the modal-opening context; the
                // blank trigger_id collapses to a no-op in the resolver.
                RequestStandupSetupContext(
                    commandBasicInfo = commandBasicInfo,
                    triggerId = "",
                    intents = intents,
                )
            }

            STANDUP_SETUP_SUBMIT -> {
                StandupSetupSubmissionContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            NOTICE_FORM -> {
                ApprovalCallbackContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand = subCommand,
                    intents = intents,
                )
            }

            else -> {
                EmptyContext(
                    commandBasicInfo = commandBasicInfo,
                    intents = intents,
                )
            }
        }
}
