package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
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
    NOTICE_FORM,
    ;

    internal fun createContext(
        commandBasicInfo: CommandBasicInfo,
        requestHeaders: SlackRequestHeaders,
        subCommand: SubCommand<NoSubCommands>,
        intents: IntentQueue,
    ): CommandContext<out SubCommandDefinition> =
        when (this) {
            APPROVAL_FORM -> {
                SlackApprovalFormContext(
                    commandBasicInfo = commandBasicInfo,
                    intents = intents,
                )
            }

            MEETING_APPROVAL_NOTICE_FORM, REQUEST_MEETING_FORM,
            -> {
                RequestMeetingContext(
                    commandBasicInfo = commandBasicInfo,
                    subCommand =
                        SubCommand(
                            subCommandDefinition = MeetingSubCommandDefinition.NONE,
                        ),
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
                    requestHeaders = requestHeaders,
                    intents = intents,
                )
            }
        }
}
