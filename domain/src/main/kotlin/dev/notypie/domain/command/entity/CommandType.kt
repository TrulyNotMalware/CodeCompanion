package dev.notypie.domain.command.entity

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

enum class CommandType {
    SIMPLE,
    PIPELINE,
    SCHEDULED
}

enum class CommandDetailType {
    NOTHING,
    SIMPLE_TEXT,
    ERROR_RESPONSE,
    APPROVAL_FORM,
    REQUEST_APPLY_FORM,

    REQUEST_MEETING_FORM,
    MEETING_APPROVAL_NOTICE_FORM,
    NOTICE_FORM;


    internal fun createContext(
        slackEventBuilder: SlackEventBuilder,
        commandBasicInfo: CommandBasicInfo,
        events: EventQueue<CommandEvent<EventPayload>>,
        requestHeaders: SlackRequestHeaders,
    ): CommandContext = when (this) {
        APPROVAL_FORM ->
            SlackApprovalFormContext(
                slackEventBuilder = slackEventBuilder,
                commandBasicInfo = commandBasicInfo,
                events = events,
            )
        MEETING_APPROVAL_NOTICE_FORM,
        REQUEST_MEETING_FORM ->
            RequestMeetingContext(
                slackEventBuilder = slackEventBuilder,
                commandBasicInfo = commandBasicInfo,
                events = events,
            )
        NOTICE_FORM ->
            ApprovalCallbackContext(
                slackEventBuilder = slackEventBuilder,
                commandBasicInfo = commandBasicInfo,
                events = events,
            )
        else -> EmptyContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            events = events,
        )
    }
}