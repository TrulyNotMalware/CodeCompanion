package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.form.RequestMeetingContextResult
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.domain.common.event.EventPublisher
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.meeting.schema.newMeeting
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.util.MultiValueMap

@Service
class MeetingServiceImpl(
    private val slackEventBuilder: SlackEventBuilder,
    private val meetingRepository: MeetingRepository,
    private val retryService: RetryService,
    private val eventPublisher: EventPublisher
): MeetingService {

    @Transactional
    override fun handleMeeting(headers: MultiValueMap<String, String>,
                               payload: SlashCommandRequestBody, slackCommandData: SlackCommandData) {
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command = RequestMeetingCommand(
            commandData = slackCommandData, idempotencyKey = idempotencyKey,
            slackEventBuilder = this.slackEventBuilder, eventPublisher = this.eventPublisher
        )
        val result = command.handleEvent()
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun createNewMeeting(result: RequestMeetingContextResult){
        val meeting = result.newMeeting()
        this.retryService.execute(
            action = { meetingRepository.createNewMeeting(meetingSchema = meeting) },
            recoveryCallBack = {
//                slackApiRequester.simpleTextRequest()
            }
        )
    }
}