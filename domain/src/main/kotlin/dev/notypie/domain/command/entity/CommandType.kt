package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.Queue

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
        slackApiRequester: SlackApiRequester,
        commandBasicInfo: CommandBasicInfo,
        events: Queue<CommandEvent<EventPayload>>,
        requestHeaders: SlackRequestHeaders,
    ): CommandContext = when (this) {
        APPROVAL_FORM ->
            SlackApprovalFormContext(
                slackApiRequester = slackApiRequester,
                commandBasicInfo = commandBasicInfo,
                events = events,
            )
        MEETING_APPROVAL_NOTICE_FORM,
        REQUEST_MEETING_FORM ->
            RequestMeetingContext(
                slackApiRequester = slackApiRequester,
                commandBasicInfo = commandBasicInfo,
                events = events,
            )
        NOTICE_FORM ->
            ApprovalCallbackContext(
                slackApiRequester = slackApiRequester,
                commandBasicInfo = commandBasicInfo,
                events = events,
            )
        else -> EmptyContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackApiRequester = slackApiRequester,
            events = events,
        )
    }
}