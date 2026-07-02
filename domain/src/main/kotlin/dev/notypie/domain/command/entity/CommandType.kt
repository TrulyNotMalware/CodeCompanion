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

/**
 * Routing discriminator for a command interaction, serialized by [name] into the outbox column and
 * Slack modal `private_metadata` / button values, read back with [valueOf] (unknown tokens fail
 * fast). Renaming a value requires a local DB reset and invalidates buttons already posted to Slack.
 */
enum class CommandDetailType {
    NOTHING,
    SIMPLE_TEXT,
    REPLACE_TEXT,
    ERROR_RESPONSE,
    APPROVAL_REQUEST,
    APPLY_REQUEST,

    MEETING_CREATE_REQUEST,
    GET_MEETING_LIST,
    MEETING_APPROVAL_REQUEST,
    MEETING_DECLINE_REASON,
    CANCEL_MEETING,
    MEETING_RESCHEDULE_REQUEST,
    MEETING_RESCHEDULE_SUBMIT,
    MEETING_ADD_PARTICIPANT_REQUEST,
    MEETING_ADD_PARTICIPANT_SUBMIT,
    MEETING_REMINDER,
    DAILY_AGENDA,
    STATUS_REPORT,
    AGENT_CONVERSE,
    STANDUP_PROMPT,
    STANDUP_ANSWER_SUBMIT,
    STANDUP_SETUP_REQUEST,
    STANDUP_SETUP_SUBMIT,
    STANDUP_SUMMARY,
    APPROVAL_CALLBACK,
}

/**
 * Maps a routed interaction type to the command context that handles it. Kept out of the enum body
 * so [CommandDetailType] stays a pure routing token rather than also owning context construction.
 */
internal fun CommandDetailType.createContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands>,
    intents: IntentQueue,
): CommandContext<out SubCommandDefinition> =
    when (this) {
        CommandDetailType.APPROVAL_REQUEST -> {
            ApprovalFormContext(
                commandBasicInfo = commandBasicInfo,
                intents = intents,
            )
        }

        CommandDetailType.MEETING_CREATE_REQUEST -> {
            RequestMeetingContext(
                commandBasicInfo = commandBasicInfo,
                subCommand =
                    SubCommand(
                        subCommandDefinition = MeetingSubCommandDefinition.NONE,
                    ),
                intents = intents,
            )
        }

        CommandDetailType.MEETING_APPROVAL_REQUEST -> {
            MeetingApprovalResponseContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.MEETING_DECLINE_REASON -> {
            DeclineReasonSubmissionContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.CANCEL_MEETING -> {
            CancelMeetingContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.MEETING_RESCHEDULE_REQUEST -> {
            RescheduleMeetingContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.MEETING_RESCHEDULE_SUBMIT -> {
            RescheduleMeetingSubmissionContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST -> {
            AddParticipantContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT -> {
            AddParticipantSubmissionContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.STANDUP_PROMPT -> {
            StandupFillContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.STANDUP_ANSWER_SUBMIT -> {
            StandupAnswerSubmissionContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.STANDUP_SETUP_REQUEST -> {
            // The slash entry point builds this context directly with the live trigger_id
            // (see SetupStandupCommand). This branch only exists for completeness so an
            // interaction routed here still resolves to the modal-opening context; the
            // blank trigger_id collapses to a no-op in the resolver.
            RequestStandupSetupContext(
                commandBasicInfo = commandBasicInfo,
                triggerHandle = "",
                intents = intents,
            )
        }

        CommandDetailType.STANDUP_SETUP_SUBMIT -> {
            StandupSetupSubmissionContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        }

        CommandDetailType.APPROVAL_CALLBACK -> {
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
