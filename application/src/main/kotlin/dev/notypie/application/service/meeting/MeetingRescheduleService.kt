package dev.notypie.application.service.meeting

import dev.notypie.application.service.calendar.MeetingCalendarMirror
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RescheduleMeetingEvent
import dev.notypie.domain.command.entity.event.RescheduleMeetingPayload
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.meeting.RescheduleResult
import dev.notypie.templates.escapeMrkdwn
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Service
class MeetingRescheduleService(
    private val meetingRepository: MeetingRepository,
    private val reminderRepository: MeetingReminderRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val calendarMirror: MeetingCalendarMirror,
) {
    private val log = KotlinLogging.logger {}
    private val writeTemplate = isolatedWriteTemplate(transactionManager = transactionManager)
    private val replyTemplate = TransactionTemplate(transactionManager)

    @EventListener
    fun rescheduleMeeting(event: RescheduleMeetingEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        if (!payload.newStartAt.isAfter(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES))) {
            publishEphemeral(
                message = "Pick a future time. The meeting was not rescheduled.",
                basicInfo = basicInfo,
                targetUserId = payload.requesterId,
            )
            return
        }
        MeetingWriteDeferral.runOrDefer { reschedule(event = event) }
    }

    private fun reschedule(event: RescheduleMeetingEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        writeTemplate
            .executeRetryingOnConflict { applyReschedule(payload = payload) }
            .onFailure { exception ->
                replyTemplate.stageFailureReply(failure = exception) {
                    publishEphemeral(
                        message = "Failed to reschedule the meeting. Please try again later.",
                        basicInfo = basicInfo,
                        targetUserId = payload.requesterId,
                    )
                }
                log.error(exception) {
                    "Failed to reschedule meeting meetingUid=${payload.meetingUid} " +
                        "requesterId=${payload.requesterId} idempotencyKey=${event.idempotencyKey}"
                }
            }
    }

    private fun applyReschedule(payload: RescheduleMeetingPayload) {
        val basicInfo = payload.responseBasicInfo
        val formattedStart = payload.newStartAt.format(RESCHEDULE_TIMESTAMP_FORMAT)
        val result =
            meetingRepository.rescheduleMeeting(
                meetingUid = payload.meetingUid,
                requesterId = payload.requesterId,
                newStartAt = payload.newStartAt,
            )
        val hostMessage =
            when (result) {
                RescheduleResult.NotAuthorized ->
                    "Meeting was canceled, or you are not the host."

                RescheduleResult.AlreadyAtRequestedTime ->
                    "The meeting is already scheduled for $formattedStart. Nothing was changed."

                is RescheduleResult.Rescheduled -> {
                    reminderRepository.deleteByMeetingId(meetingId = result.meeting.meetingId)
                    calendarMirror.onMeetingRescheduled(meetingId = result.meeting.meetingId)
                    publishParticipantReNotification(
                        meetingTitle = result.meeting.title,
                        participantUserIds = result.meeting.participants.map { it.userId },
                        newStartAt = payload.newStartAt,
                        basicInfo = basicInfo,
                    )
                    "Meeting rescheduled to $formattedStart."
                }
            }
        publishEphemeral(message = hostMessage, basicInfo = basicInfo, targetUserId = payload.requesterId)
    }

    private fun publishParticipantReNotification(
        meetingTitle: String,
        participantUserIds: List<String>,
        newStartAt: LocalDateTime,
        basicInfo: CommandBasicInfo,
    ) {
        if (participantUserIds.isEmpty()) return
        val mentions = participantUserIds.joinToString(" ") { "<@$it>" }
        val notice =
            "[Notice] $mentions *${meetingTitle.escapeMrkdwn()}* has been rescheduled to " +
                newStartAt.format(RESCHEDULE_TIMESTAMP_FORMAT) + "."
        outboundStager
            .stage(
                message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = basicInfo.channel),
                        content = MessageContent.Text(headline = "Meeting rescheduled", markdown = notice),
                        detailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    private fun publishEphemeral(message: String, basicInfo: CommandBasicInfo, targetUserId: String) {
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = UserRef(id = targetUserId),
                        content = MessageContent.Text(headline = null, markdown = message),
                        detailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    companion object {
        private val RESCHEDULE_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
