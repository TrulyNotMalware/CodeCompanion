package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import java.time.LocalDate

sealed interface MessageContent {
    data class Text(
        val headline: String?,
        val markdown: String,
    ) : MessageContent

    data class ErrorNotice(
        val className: String,
        val message: String,
        val details: String?,
    ) : MessageContent

    data class Schedule(
        val headline: String,
        val info: TimeScheduleInfo,
    ) : MessageContent

    data class Form(
        val headline: String,
        val fields: List<SelectionContents>,
        val reason: TextInputContents?,
        val approval: ApprovalContents?,
    ) : MessageContent

    data class MeetingRequest(
        val approval: ApprovalContents?,
    ) : MessageContent

    data class MeetingList(
        val meetings: List<MeetingDto>,
        val currentUserId: String,
    ) : MessageContent

    data class StandupSummary(
        val routineName: String,
        val sessionDate: LocalDate,
        val members: List<RoutineMemberDto>,
        val answers: List<StandupAnswerDto>,
        val questions: List<String>,
    ) : MessageContent
}
