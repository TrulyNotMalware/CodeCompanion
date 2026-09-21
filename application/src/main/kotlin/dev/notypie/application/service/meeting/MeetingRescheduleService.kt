package dev.notypie.application.service.meeting

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RescheduleMeetingEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

@Service
class MeetingRescheduleService(
    private val meetingRepository: MeetingRepository,
    private val reminderRepository: MeetingReminderRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    @EventListener
    fun rescheduleMeeting(event: RescheduleMeetingEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo

        val rescheduled =
            runCatching {
                meetingRepository.rescheduleMeeting(
                    meetingUid = payload.meetingUid,
                    requesterId = payload.requesterId,
                    newStartAt = payload.newStartAt,
                )
            }.getOrElse { exception ->
                log.error(exception) {
                    "Failed to reschedule meeting meetingUid=${payload.meetingUid} " +
                        "requesterId=${payload.requesterId} idempotencyKey=${event.idempotencyKey}"
                }
                publishEphemeral(
                    message = "Failed to reschedule the meeting. Please try again later.",
                    basicInfo = basicInfo,
                    targetUserId = payload.requesterId,
                )
                return
            }

        if (!rescheduled) {
            publishEphemeral(
                message = "Meeting was canceled, or you are not the host.",
                basicInfo = basicInfo,
                targetUserId = payload.requesterId,
            )
            return
        }

        val meeting = meetingRepository.findMeetingByUid(meetingUid = payload.meetingUid)
        if (meeting != null) {
            reminderRepository.deleteByMeetingId(meetingId = meeting.meetingId)
            publishParticipantReNotification(
                meetingTitle = meeting.title,
                participantUserIds = meeting.participants.map { it.userId },
                newStartAt = payload.newStartAt,
                basicInfo = basicInfo,
            )
        }

        publishEphemeral(
            message =
                "Meeting rescheduled to ${payload.newStartAt.format(RESCHEDULE_TIMESTAMP_FORMAT)}.",
            basicInfo = basicInfo,
            targetUserId = payload.requesterId,
        )
    }

    private fun publishParticipantReNotification(
        meetingTitle: String,
        participantUserIds: List<String>,
        newStartAt: java.time.LocalDateTime,
        basicInfo: CommandBasicInfo,
    ) {
        if (participantUserIds.isEmpty()) return
        val mentions = participantUserIds.joinToString(" ") { "<@$it>" }
        val notice =
            "[Notice] $mentions *$meetingTitle* has been rescheduled to " +
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
