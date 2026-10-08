package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AddParticipantEvent
import dev.notypie.domain.command.entity.event.AddParticipantPayload
import dev.notypie.domain.command.entity.event.AgentConversePayload
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.AgentUsageReportPayload
import dev.notypie.domain.command.entity.event.AgentUsageReportRequestEvent
import dev.notypie.domain.command.entity.event.CalendarConnectionAction
import dev.notypie.domain.command.entity.event.CalendarConnectionPayload
import dev.notypie.domain.command.entity.event.CalendarConnectionRequestEvent
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.CancelMeetingPayload
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutineEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutinePayload
import dev.notypie.domain.command.entity.event.CveLatestPayload
import dev.notypie.domain.command.entity.event.CveLatestRequestEvent
import dev.notypie.domain.command.entity.event.CveOpsAction
import dev.notypie.domain.command.entity.event.CveOpsPayload
import dev.notypie.domain.command.entity.event.CveOpsRequestEvent
import dev.notypie.domain.command.entity.event.CveSubscriptionAction
import dev.notypie.domain.command.entity.event.CveSubscriptionPayload
import dev.notypie.domain.command.entity.event.CveSubscriptionRequestEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.GetMeetingEventPayload
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerPayload
import dev.notypie.domain.command.entity.event.RescheduleMeetingEvent
import dev.notypie.domain.command.entity.event.RescheduleMeetingPayload
import dev.notypie.domain.command.entity.event.RoleManageAction
import dev.notypie.domain.command.entity.event.RoleManagePayload
import dev.notypie.domain.command.entity.event.RoleManageRequestEvent
import dev.notypie.domain.command.entity.event.StandupOpsAction
import dev.notypie.domain.command.entity.event.StandupOpsPayload
import dev.notypie.domain.command.entity.event.StandupOpsRequestEvent
import dev.notypie.domain.command.entity.event.StatusReportPayload
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendancePayload
import dev.notypie.domain.command.intent.CommandIntent

class SlackIntentResolver {
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

            is CommandIntent.CalendarConnect ->
                calendarConnectionEvent(
                    action = CalendarConnectionAction.CONNECT,
                    userId = intent.userId,
                    basicInfo = basicInfo,
                )

            is CommandIntent.CalendarDisconnect ->
                calendarConnectionEvent(
                    action = CalendarConnectionAction.DISCONNECT,
                    userId = intent.userId,
                    basicInfo = basicInfo,
                )

            is CommandIntent.CalendarStatus ->
                calendarConnectionEvent(
                    action = CalendarConnectionAction.STATUS,
                    userId = intent.userId,
                    basicInfo = basicInfo,
                )

            is CommandIntent.StatusReport -> {
                StatusReportRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload = StatusReportPayload(responseBasicInfo = basicInfo),
                    type = CommandDetailType.STATUS_REPORT,
                )
            }

            is CommandIntent.AgentUsageReport -> {
                AgentUsageReportRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload = AgentUsageReportPayload(days = intent.days, responseBasicInfo = basicInfo),
                    type = CommandDetailType.AGENT_USAGE_REPORT,
                )
            }

            is CommandIntent.GrantRole -> {
                RoleManageRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        RoleManagePayload(
                            action = RoleManageAction.GRANT,
                            targetUserId = intent.targetUserId,
                            role = intent.role,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.RevokeRole -> {
                RoleManageRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        RoleManagePayload(
                            action = RoleManageAction.REVOKE,
                            targetUserId = intent.targetUserId,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.ListRoles -> {
                RoleManageRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        RoleManagePayload(
                            action = RoleManageAction.LIST,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.AgentConverse -> {
                AgentConverseRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        AgentConversePayload(
                            prompt = intent.prompt,
                            threadId = intent.threadId,
                            requesterName = intent.requesterName,
                            channelName = intent.channelName,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.AGENT_CONVERSE,
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
                            notice = intent.notice,
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

            is CommandIntent.ListStandupRoutines -> {
                StandupOpsRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        StandupOpsPayload(
                            action = StandupOpsAction.LIST,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.STANDUP_ROUTINE_LIST,
                )
            }

            is CommandIntent.StopStandupRoutine -> {
                StandupOpsRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        StandupOpsPayload(
                            action = StandupOpsAction.STOP,
                            routineName = intent.routineName,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.STANDUP_ROUTINE_STOP,
                )
            }

            is CommandIntent.CveSubscribe -> {
                CveSubscriptionRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveSubscriptionPayload(
                            action = CveSubscriptionAction.SUBSCRIBE,
                            userId = intent.userId,
                            topicKeys = intent.topicKeys,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                )
            }

            is CommandIntent.CveUnsubscribe -> {
                CveSubscriptionRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveSubscriptionPayload(
                            action = CveSubscriptionAction.UNSUBSCRIBE,
                            userId = intent.userId,
                            topicKeys = intent.topicKeys,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
                )
            }

            is CommandIntent.CveListSubscriptions -> {
                CveSubscriptionRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveSubscriptionPayload(
                            action = CveSubscriptionAction.LIST,
                            userId = intent.userId,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.CVE_SUBSCRIPTIONS_LIST,
                )
            }

            is CommandIntent.CveLatest -> {
                CveLatestRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveLatestPayload(
                            userId = intent.userId,
                            topicKey = intent.topicKey,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.CVE_LATEST,
                )
            }

            is CommandIntent.CveListTopics -> {
                CveOpsRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveOpsPayload(
                            action = CveOpsAction.LIST_TOPICS,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.CveSetTopicActive -> {
                CveOpsRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveOpsPayload(
                            action =
                                if (intent.active) CveOpsAction.ACTIVATE_TOPIC else CveOpsAction.DEACTIVATE_TOPIC,
                            topicKey = intent.topicKey,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.CveRetryDeadLetters -> {
                CveOpsRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveOpsPayload(
                            action = CveOpsAction.RETRY_ALL,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.CveRetryDeadLetter -> {
                CveOpsRequestEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload =
                        CveOpsPayload(
                            action = CveOpsAction.RETRY_EVENT,
                            targetEventId = intent.eventId,
                            responseBasicInfo = basicInfo,
                        ),
                    type = CommandDetailType.SIMPLE_TEXT,
                )
            }

            is CommandIntent.Nothing -> {
                null
            }
        }

    private fun calendarConnectionEvent(
        action: CalendarConnectionAction,
        userId: String,
        basicInfo: CommandBasicInfo,
    ): CalendarConnectionRequestEvent =
        CalendarConnectionRequestEvent(
            idempotencyKey = basicInfo.idempotencyKey,
            payload =
                CalendarConnectionPayload(
                    action = action,
                    userId = userId,
                    responseBasicInfo = basicInfo,
                ),
        )
}
