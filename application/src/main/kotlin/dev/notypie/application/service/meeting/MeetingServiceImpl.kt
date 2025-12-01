package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.controllers.dto.GetMeetupListRequestDto
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.MeetingRepository
import jakarta.transaction.Transactional
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.util.MultiValueMap

@Service
class MeetingServiceImpl(
    private val slackEventBuilder: SlackEventBuilder,
    private val meetingRepository: MeetingRepository,
    private val retryService: RetryService,
    private val eventPublisher: EventPublisher,
) : MeetingService {
    @Transactional
    override fun handleMeeting(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        slackCommandData: SlackCommandData,
    ) {
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command =
            RequestMeetingCommand(
                commandData = slackCommandData,
                idempotencyKey = idempotencyKey,
                slackEventBuilder = slackEventBuilder,
                eventPublisher = eventPublisher,
            )
        val result = command.handleEvent()
    }

    override fun getMyMeetingList(meetingRequestDto: GetMeetupListRequestDto) {
        val meetings = meetingRepository.getAllMeetingByUserId(userId = meetingRequestDto.userId)
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = false)
    fun createNewMeeting(event: RequestMeetingContextResult) {
        val meeting =
            retryService.execute(
                action = {
                    meetingRepository.createNewMeeting(
                        meeting = event.meeting,
                        idempotencyKey = event.idempotencyKey,
                        channel = event.commandBasicInfo.channel,
                    )
                },
            )
    }

    @EventListener
    fun getMeetingListEvent(getMeetingListEvent: GetMeetingListEvent) {
        val meetings = meetingRepository.getAllMeetingByUserId(getMeetingListEvent.payload.publisherId)
//        getMeetingListEvent.payload.slackEventModifier()
    }
}
