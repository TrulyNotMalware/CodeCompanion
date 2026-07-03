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
 * Routing discriminator for a command interaction. [wireValue] is the stable, serialized token
 * embedded in the outbox column and in Slack modal `private_metadata` / button values; it is
 * decoupled from the Kotlin identifier so the identifier can stay transport-neutral while the
 * persisted/in-flight wire string never changes. Adapters serialize via [wireValue] and read back
 * via [fromWireValue]; they must never rely on [name].
 */
enum class CommandDetailType(
    val wireValue: String,
) {
    NOTHING("NOTHING"),
    SIMPLE_TEXT("SIMPLE_TEXT"),
    REPLACE_TEXT("REPLACE_TEXT"),
    ERROR_RESPONSE("ERROR_RESPONSE"),
    APPROVAL_REQUEST("APPROVAL_FORM"),
    APPLY_REQUEST("REQUEST_APPLY_FORM"),

    MEETING_CREATE_REQUEST("REQUEST_MEETING_FORM"),
    GET_MEETING_LIST("GET_MEETING_LIST"),
    MEETING_APPROVAL_REQUEST("MEETING_APPROVAL_NOTICE_FORM"),
    MEETING_DECLINE_REASON("DECLINE_REASON_MODAL"),
    CANCEL_MEETING("CANCEL_MEETING"),
    MEETING_RESCHEDULE_REQUEST("RESCHEDULE_MEETING"),
    MEETING_RESCHEDULE_SUBMIT("RESCHEDULE_MEETING_SUBMIT"),
    MEETING_ADD_PARTICIPANT_REQUEST("ADD_PARTICIPANT"),
    MEETING_ADD_PARTICIPANT_SUBMIT("ADD_PARTICIPANT_SUBMIT"),
    MEETING_REMINDER("MEETING_REMINDER"),
    DAILY_AGENDA("DAILY_AGENDA"),
    STATUS_REPORT("STATUS_REPORT"),
    AGENT_CONVERSE("AGENT_CONVERSE"),
    STANDUP_PROMPT("STANDUP_FILL"),
    STANDUP_ANSWER_SUBMIT("STANDUP_ANSWER_SUBMIT"),
    STANDUP_SETUP_REQUEST("STANDUP_SETUP_FORM"),
    STANDUP_SETUP_SUBMIT("STANDUP_SETUP_SUBMIT"),
    STANDUP_SUMMARY("STANDUP_SUMMARY"),
    APPROVAL_CALLBACK("NOTICE_FORM"),
    ;

    companion object {
        /**
         * Tolerant reverse of [wireValue] for inbound Slack surfaces: an interaction can carry a stale
         * or unknown token (old message in channel history), so unknown degrades to [NOTHING].
         */
        fun fromWireValue(raw: String): CommandDetailType = entries.firstOrNull { it.wireValue == raw } ?: NOTHING

        /**
         * Strict reverse of [wireValue] for trusted, already-persisted data (the outbox column). An
         * unknown token there means corruption, so fail fast rather than silently dispatching [NOTHING]
         * (preserves the pre-decoupling `valueOf` semantics).
         */
        fun requireWireValue(raw: String): CommandDetailType =
            entries.firstOrNull { it.wireValue == raw }
                ?: throw IllegalArgumentException("Unknown CommandDetailType wireValue: $raw")
    }
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
                triggerId = "",
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
