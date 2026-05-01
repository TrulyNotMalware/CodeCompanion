package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.CancelMeetingPayload
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.GetMeetingEventPayload
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.StatusReportPayload
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendancePayload
import dev.notypie.domain.command.intent.CommandIntent
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class SlackIntentResolver(
    private val slackEventBuilder: SlackApiEventConstructor,
) {
    /**
     * Resolves each intent individually using [CommandIntent.commandDetailType] so that a
     * heterogeneous batch produces events with correctly-typed routing metadata.
     */
    fun resolveAll(intents: List<CommandIntent>, basicInfo: CommandBasicInfo): List<CommandEvent<EventPayload>> =
        intents.mapNotNull { intent ->
            resolve(
                intent = intent,
                basicInfo = basicInfo,
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(intent: CommandIntent, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (intent) {
            is CommandIntent.TextResponse -> {
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = intent.commandDetailType,
                    headLineText = intent.headLine,
                    commandBasicInfo = basicInfo,
                    simpleString = intent.message,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.EphemeralResponse -> {
                slackEventBuilder.simpleEphemeralTextRequest(
                    textMessage = intent.message,
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                    targetUserId = intent.targetUserId,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.ErrorDetail -> {
                slackEventBuilder.detailErrorTextRequest(
                    commandDetailType = intent.commandDetailType,
                    errorClassName = intent.errorClassName,
                    errorMessage = intent.errorMessage,
                    details = intent.details,
                    commandBasicInfo = basicInfo,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.TimeSchedule -> {
                slackEventBuilder.simpleTimeScheduleRequest(
                    commandDetailType = intent.commandDetailType,
                    headLineText = intent.headLine,
                    commandBasicInfo = basicInfo,
                    timeScheduleInfo = intent.timeScheduleInfo,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.ApplyReject -> {
                // Propagate the human-readable subtitle (meeting title for notice DMs) through
                // the routing text so context handlers can surface it in follow-up UI without
                // a separate DB lookup. Blank subtitles are filtered out to keep the routing
                // token stable for flows that don't use subTitle.
                val extras =
                    listOf(intent.approvalContents.subTitle)
                        .filter { it.isNotBlank() }
                slackEventBuilder.simpleApplyRejectRequest(
                    commandDetailType = intent.commandDetailType,
                    commandBasicInfo = basicInfo,
                    approvalContents = intent.approvalContents,
                    targetUserId = intent.targetUserId,
                    routingExtras = extras,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.ApprovalForm -> {
                slackEventBuilder.simpleApprovalFormRequest(
                    commandDetailType = intent.commandDetailType,
                    headLineText = intent.headLine,
                    commandBasicInfo = basicInfo,
                    selectionFields = intent.selectionFields,
                    reasonInput = intent.reasonInput,
                    approvalContents = intent.approvalContents,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.Notice -> {
                val userMentions = intent.targetUserIds.joinToString(" ") { "<@$it>" }
                val noticeText = "[Notice] $userMentions ${intent.message}"
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = intent.commandDetailType,
                    headLineText = "Notice!",
                    commandBasicInfo = basicInfo,
                    simpleString = noticeText,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.MeetingForm -> {
                slackEventBuilder.requestMeetingFormRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                    approvalContents = intent.approvalContents,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.MeetingListRequest -> {
                GetMeetingListEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        GetMeetingEventPayload(
                            publisherId = intent.publisherId,
                            startDate = intent.startDate,
                            endDate = intent.endDate,
                            responseBasicInfo = basicInfo,
                        ),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.MeetingAttendanceUpdate -> {
                UpdateMeetingAttendanceEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        UpdateMeetingAttendancePayload(
                            meetingIdempotencyKey = intent.meetingIdempotencyKey,
                            participantUserId = intent.participantUserId,
                            isAttending = intent.isAttending,
                            absentReason = intent.absentReason,
                        ),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.CancelMeeting -> {
                CancelMeetingEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CancelMeetingPayload(
                            meetingUid = intent.meetingUid,
                            requesterId = intent.requesterId,
                            responseBasicInfo = basicInfo,
                        ),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.StatusReport -> {
                StatusReportRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload = StatusReportPayload(responseBasicInfo = basicInfo),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.OpenDeclineReasonModal -> {
                slackEventBuilder.openDeclineReasonModalRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                    triggerId = intent.triggerId,
                    meetingIdempotencyKey = intent.meetingIdempotencyKey,
                    participantUserId = intent.participantUserId,
                    meetingTitle = intent.meetingTitle,
                    noticeChannel = intent.noticeChannel,
                    noticeMessageTs = intent.noticeMessageTs,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.UpdateNoticeMessage -> {
                slackEventBuilder.updateNoticeMessageRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                    channel = intent.channel,
                    messageTs = intent.messageTs,
                    markdownText = intent.markdownText,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.ReplaceMessage -> {
                slackEventBuilder.replaceOriginalText(
                    markdownText = intent.markdownText,
                    responseUrl = intent.responseUrl,
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                ) as CommandEvent<EventPayload>
            }

            is CommandIntent.Nothing -> {
                null
            }
        }
}
