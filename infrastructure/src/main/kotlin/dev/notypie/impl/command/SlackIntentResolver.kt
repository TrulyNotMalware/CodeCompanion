package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
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

class SlackIntentResolver {
    /** Resolves each intent individually, assigning its per-variant routing detail type. */
    fun resolveAll(intents: List<CommandIntent>, basicInfo: CommandBasicInfo): List<CommandEvent<EventPayload>> =
        intents.mapNotNull { intent ->
            resolve(
                intent = intent,
                basicInfo = basicInfo,
            )
        }

    private fun resolve(intent: CommandIntent, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (intent) {
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
                    type = CommandDetailType.GET_MEETING_LIST,
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
                    type = CommandDetailType.MEETING_APPROVAL_REQUEST,
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
                    type = CommandDetailType.CANCEL_MEETING,
                )
            }

            is CommandIntent.RescheduleMeeting -> {
                RescheduleMeetingEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        RescheduleMeetingPayload(
                            meetingUid = intent.meetingUid,
                            requesterId = intent.requesterId,
                            newStartAt = intent.newStartAt,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                )
            }

            is CommandIntent.AddParticipant -> {
                AddParticipantEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        AddParticipantPayload(
                            meetingUid = intent.meetingUid,
                            requesterId = intent.requesterId,
                            participantUserIds = intent.participantUserIds,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                )
            }

            is CommandIntent.StatusReport -> {
                StatusReportRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload = StatusReportPayload(responseBasicInfo = basicInfo),
                    type = CommandDetailType.STATUS_REPORT,
                )
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
                    type = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                )
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
                    type = CommandDetailType.STANDUP_SETUP_SUBMIT,
                )
            }

            is CommandIntent.Nothing -> {
                null
            }
        }
}
