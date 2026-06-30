package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.AddParticipantEvent
import dev.notypie.domain.command.entity.event.AddParticipantPayload
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.CancelMeetingPayload
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutineEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutinePayload
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.GetMeetingEventPayload
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerPayload
import dev.notypie.domain.command.entity.event.RescheduleMeetingEvent
import dev.notypie.domain.command.entity.event.RescheduleMeetingPayload
import dev.notypie.domain.command.entity.event.StatusReportPayload
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendancePayload
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class SlackIntentResolver(
    private val slackEventBuilder: SlackApiEventConstructor,
    private val standupRepository: StandupRepository,
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

    private fun resolve(intent: CommandIntent, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (intent) {
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
                )
            }

            is CommandIntent.ApprovalForm -> {
                slackEventBuilder.simpleApprovalFormRequest(
                    commandDetailType = intent.commandDetailType,
                    headLineText = intent.headLine,
                    commandBasicInfo = basicInfo,
                    selectionFields = intent.selectionFields,
                    reasonInput = intent.reasonInput,
                    approvalContents = intent.approvalContents,
                )
            }

            is CommandIntent.MeetingForm -> {
                slackEventBuilder.requestMeetingFormRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                    approvalContents = intent.approvalContents,
                )
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
                            absentReasonDetail = intent.absentReasonDetail,
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

            is CommandIntent.OpenRescheduleMeetingModal -> {
                if (intent.triggerId.isBlank()) {
                    log.warn { "Blank triggerId; cannot open reschedule modal for meetingUid=${intent.meetingUid}" }
                    null
                } else {
                    slackEventBuilder.openRescheduleMeetingModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = intent.commandDetailType,
                        triggerId = intent.triggerId,
                        meetingUid = intent.meetingUid,
                        requesterId = intent.requesterId,
                        channel = intent.channel,
                        // The open-modal intent does not carry the meeting's stored start; defaulting
                        // the pickers to "now" is sufficient since the host adjusts both before submit.
                        currentStartAt = java.time.LocalDateTime.now(),
                    )
                }
            }

            is CommandIntent.RescheduleMeeting -> {
                RescheduleMeetingEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        RescheduleMeetingPayload(
                            meetingUid = intent.meetingUid,
                            requesterId = intent.requesterId,
                            newStartAt = intent.newStartAt,
                            // A view_submission carries no channel; use the one ferried through the
                            // modal so the host's confirmation posts back into the list's channel.
                            responseBasicInfo =
                                basicInfo.copy(channel = intent.channel.ifBlank { basicInfo.channel }),
                        ),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.OpenAddParticipantModal -> {
                if (intent.triggerId.isBlank()) {
                    log.warn {
                        "Blank triggerId; cannot open add-participant modal for meetingUid=${intent.meetingUid}"
                    }
                    null
                } else {
                    slackEventBuilder.openAddParticipantModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = intent.commandDetailType,
                        triggerId = intent.triggerId,
                        meetingUid = intent.meetingUid,
                        requesterId = intent.requesterId,
                        channel = intent.channel,
                    )
                }
            }

            is CommandIntent.AddParticipant -> {
                AddParticipantEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        AddParticipantPayload(
                            meetingUid = intent.meetingUid,
                            requesterId = intent.requesterId,
                            participantUserIds = intent.participantUserIds,
                            // A view_submission carries no channel; use the one ferried through the
                            // modal so the host's confirmation posts back into the list's channel.
                            responseBasicInfo =
                                basicInfo.copy(channel = intent.channel.ifBlank { basicInfo.channel }),
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
                )
            }

            is CommandIntent.UpdateNoticeMessage -> {
                slackEventBuilder.updateNoticeMessageRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                    channel = intent.channel,
                    messageTs = intent.messageTs,
                    markdownText = intent.markdownText,
                )
            }

            is CommandIntent.ReplaceMessage -> {
                slackEventBuilder.replaceOriginalText(
                    markdownText = intent.markdownText,
                    responseUrl = intent.responseUrl,
                    commandBasicInfo = basicInfo,
                    commandDetailType = intent.commandDetailType,
                )
            }

            is CommandIntent.OpenStandupModal -> {
                resolveOpenStandupModal(intent = intent, basicInfo = basicInfo)
            }

            is CommandIntent.RecordStandupAnswer -> {
                RecordStandupAnswerEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        RecordStandupAnswerPayload(
                            sessionUid = intent.sessionUid,
                            userId = intent.userId,
                            responses = intent.responses,
                        ),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.OpenStandupSetupModal -> {
                if (intent.triggerId.isBlank()) {
                    log.warn { "Blank triggerId; cannot open standup setup modal for creatorId=${intent.creatorId}" }
                    null
                } else {
                    slackEventBuilder.openStandupSetupModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = intent.commandDetailType,
                        triggerId = intent.triggerId,
                        creatorId = intent.creatorId,
                        commandChannel = intent.commandChannel,
                    )
                }
            }

            is CommandIntent.CreateStandupRoutine -> {
                CreateStandupRoutineEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CreateStandupRoutinePayload(
                            name = intent.name,
                            creatorId = intent.creatorId,
                            commandChannel = intent.commandChannel,
                            summaryChannel = intent.summaryChannel,
                            questions = intent.questions,
                            memberIds = intent.memberIds,
                            weekdays = intent.weekdays,
                            triggerLocalTime = intent.triggerLocalTime,
                            cutoffMinutes = intent.cutoffMinutes,
                            timezone = intent.timezone,
                            responseBasicInfo = basicInfo,
                        ),
                    type = intent.commandDetailType,
                )
            }

            is CommandIntent.Nothing -> {
                null
            }
        }

    private fun resolveOpenStandupModal(
        intent: CommandIntent.OpenStandupModal,
        basicInfo: CommandBasicInfo,
    ): CommandEvent<EventPayload>? {
        if (intent.triggerId.isBlank()) {
            log.warn { "Blank triggerId; cannot open standup modal for sessionUid=${intent.sessionUid}" }
            return null
        }
        val routine = standupRepository.getRoutine(routineUid = intent.routineUid)
        val session =
            standupRepository.findSession(sessionUid = intent.sessionUid)
                ?: run {
                    log.warn { "Standup session not found: sessionUid=${intent.sessionUid}" }
                    return null
                }
        return slackEventBuilder.openStandupModalRequest(
            commandBasicInfo = basicInfo,
            commandDetailType = intent.commandDetailType,
            triggerId = intent.triggerId,
            sessionUid = intent.sessionUid,
            routineName = routine.name,
            sessionDate = session.sessionDate,
            questions = routine.questions,
            userId = intent.requesterId,
            noticeChannel = intent.noticeChannel,
            noticeMessageTs = intent.noticeMessageTs,
        )
    }
}
