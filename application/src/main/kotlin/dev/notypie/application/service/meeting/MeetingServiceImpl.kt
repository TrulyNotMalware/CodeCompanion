package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.form.RequestMeetingContextResult
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.meeting.schema.newMeeting
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class MeetingServiceImpl(
    private val slackApiRequester: SlackApiRequester,
    private val meetingRepository: MeetingRepository,
    private val retryService: RetryService
): MeetingService {

    override fun handleNewMeeting(headers: MultiValueMap<String, String>,
                                  payload: SlashCommandRequestBody, slackCommandData: SlackCommandData) {
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command: Command = RequestMeetingCommand(
            commandData = slackCommandData, idempotencyKey = idempotencyKey, slackApiRequester = this.slackApiRequester
        )
        val result = command.handleEvent()

    }

    fun handleGetMeetings(payload: SlashCommandRequestBody, slackCommandData: SlackCommandData){

    }

    @EventListener
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