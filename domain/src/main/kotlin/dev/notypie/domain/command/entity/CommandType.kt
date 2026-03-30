package dev.notypie.domain.command.entity

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition

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
    NOTICE_FORM,
    ;

    internal fun createContext(
        slackEventBuilder: SlackEventBuilder,
        commandBasicInfo: CommandBasicInfo,
        events: EventQueue<CommandEvent<EventPayload>>,
        requestHeaders: SlackRequestHeaders,
        subCommand: SubCommand<NoSubCommands>,
    ): CommandContext<out SubCommandDefinition> =
        when (this) {
            APPROVAL_FORM -> {
                SlackApprovalFormContext(
                    slackEventBuilder = slackEventBuilder,
                    commandBasicInfo = commandBasicInfo,
                    events = events,
                )
            }

            MEETING_APPROVAL_NOTICE_FORM, REQUEST_MEETING_FORM,
            -> {
                RequestMeetingContext(
                    slackEventBuilder = slackEventBuilder,
                    commandBasicInfo = commandBasicInfo,
                    events = events,
                    subCommand =
                        SubCommand(
                            subCommandDefinition = MeetingSubCommandDefinition.NONE,
                        ),
                )
            }

            NOTICE_FORM -> {
                ApprovalCallbackContext(
                    slackEventBuilder = slackEventBuilder,
                    commandBasicInfo = commandBasicInfo,
                    events = events,
                    subCommand = subCommand,
                )
            }

            else -> {
                EmptyContext(
                    commandBasicInfo = commandBasicInfo,
                    requestHeaders = requestHeaders,
                    slackEventBuilder = slackEventBuilder,
                    events = events,
                )
            }
        }
}
