package dev.notypie.templates

import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.TopicOption
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

interface SlackTemplateBuilder {
    fun onlyTextTemplate(message: String, isMarkDown: Boolean): LayoutBlocks

    fun simpleTextResponseTemplate(headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks

    fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks

    fun approvalTemplate(
        headLineText: String,
        approvalContents: ApprovalContents,
        idempotencyKey: UUID,
        commandDetailType: CommandDetailType,
    ): LayoutBlocks

    fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks

    fun requestApprovalFormTemplate(
        headLineText: String,
        selectionFields: List<SelectionContents>,
        approvalContents: ApprovalContents,
        approvalTargetUser: MultiUserSelectContents? = null,
        reasonInput: TextInputContents? = null,
    ): LayoutBlocks

    fun meetingListFormTemplate(
        meetings: List<MeetingDto>,
        currentUserId: String,
        listIdempotencyKey: UUID,
    ): LayoutBlocks

    fun requestMeetingFormTemplate(approvalContents: ApprovalContents): LayoutBlocks

    fun timeScheduleNoticeTemplate(
        timeScheduleInfo: TimeScheduleAlertContents,
        approvalContents: ApprovalContents,
    ): LayoutBlocks

    fun declineReasonModalViewJson(
        meetingTitle: String,
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        noticeChannel: String,
        noticeMessageTs: String,
    ): String

    fun rescheduleMeetingModalViewJson(
        meetingUid: UUID,
        currentStartAt: LocalDateTime,
        requesterId: String,
        channel: String,
    ): String

    fun addParticipantModalViewJson(meetingUid: UUID, requesterId: String, channel: String): String

    fun standupModalViewJson(
        routineName: String,
        sessionDate: LocalDate,
        sessionUid: UUID,
        userId: String,
        noticeChannel: String,
        noticeMessageTs: String,
        questions: List<String>,
    ): String

    fun standupSetupModalViewJson(idempotencyKey: UUID, creatorId: String, commandChannel: String): String

    fun cveSubscribeModalViewJson(idempotencyKey: UUID, topics: List<TopicOption>): String

    fun cveUnsubscribeModalViewJson(idempotencyKey: UUID, topics: List<TopicOption>): String

    fun standupSummaryTemplate(
        routineName: String,
        sessionDate: LocalDate,
        members: List<RoutineMemberDto>,
        answers: List<StandupAnswerDto>,
        questions: List<String>,
    ): LayoutBlocks
}
