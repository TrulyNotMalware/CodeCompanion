package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.form.AddParticipantContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.CancelMeetingContext
import dev.notypie.domain.command.entity.context.form.MeetingApprovalResponseContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingContext
import dev.notypie.domain.command.entity.context.form.StandupFillContext
import dev.notypie.domain.command.entity.context.form.StandupSlashContext
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.intent.IntentQueue

enum class CommandType {
    SIMPLE,
    PIPELINE,
    RESPONSE,
    EXTERNAL_API,
}

// Renaming a value breaks persisted rows and invalidates buttons already posted to Slack.
enum class CommandDetailType {
    NOTHING,
    SIMPLE_TEXT,
    REPLACE_TEXT,
    ERROR_RESPONSE,
    APPROVAL_REQUEST,

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
    CALENDAR_CONNECTION,
    STATUS_REPORT,
    AGENT_USAGE_REPORT,
    AGENT_CONVERSE,
    STANDUP_PROMPT,
    STANDUP_ANSWER_SUBMIT,
    STANDUP_SETUP_REQUEST,
    STANDUP_SETUP_SUBMIT,
    STANDUP_SUMMARY,
    STANDUP_ROUTINE_LIST,
    STANDUP_ROUTINE_STOP,
    APPROVAL_CALLBACK,

    CVE_SUBSCRIBE_REQUEST,
    CVE_SUBSCRIBE_SUBMIT,
    CVE_UNSUBSCRIBE_REQUEST,
    CVE_UNSUBSCRIBE_SUBMIT,
    CVE_SUBSCRIPTIONS_LIST,
    CVE_LATEST,
}

internal fun CommandDetailType.createContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands>,
    intents: IntentQueue,
): CommandContext<out SubCommandDefinition> =
    when (this) {
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

        CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST -> {
            AddParticipantContext(
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

        CommandDetailType.STANDUP_SETUP_REQUEST -> {
            StandupSlashContext(
                commandBasicInfo = commandBasicInfo,
                triggerHandle = "",
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

        CommandDetailType.NOTHING,
        CommandDetailType.SIMPLE_TEXT,
        CommandDetailType.REPLACE_TEXT,
        CommandDetailType.ERROR_RESPONSE,
        CommandDetailType.APPROVAL_REQUEST,
        CommandDetailType.GET_MEETING_LIST,
        CommandDetailType.MEETING_DECLINE_REASON,
        CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
        CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
        CommandDetailType.MEETING_REMINDER,
        CommandDetailType.DAILY_AGENDA,
        CommandDetailType.CALENDAR_CONNECTION,
        CommandDetailType.STATUS_REPORT,
        CommandDetailType.AGENT_USAGE_REPORT,
        CommandDetailType.AGENT_CONVERSE,
        CommandDetailType.STANDUP_ANSWER_SUBMIT,
        CommandDetailType.STANDUP_SETUP_SUBMIT,
        CommandDetailType.STANDUP_SUMMARY,
        CommandDetailType.STANDUP_ROUTINE_LIST,
        CommandDetailType.STANDUP_ROUTINE_STOP,
        CommandDetailType.CVE_SUBSCRIBE_REQUEST,
        CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
        CommandDetailType.CVE_UNSUBSCRIBE_REQUEST,
        CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
        CommandDetailType.CVE_SUBSCRIPTIONS_LIST,
        CommandDetailType.CVE_LATEST,
        -> {
            EmptyContext(
                commandBasicInfo = commandBasicInfo,
                intents = intents,
            )
        }
    }
