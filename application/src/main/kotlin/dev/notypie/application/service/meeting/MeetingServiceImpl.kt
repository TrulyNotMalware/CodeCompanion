package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.controllers.dto.GetMeetupListRequestDto
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.MeetingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.util.MultiValueMap

@Service
class MeetingServiceImpl(
    private val meetingRepository: MeetingRepository,
    private val retryService: RetryService,
    private val commandExecutor: CommandExecutor,
    private val slackEventBuilder: SlackApiEventConstructor,
    private val eventPublisher: EventPublisher,
) : MeetingService {
    private val log = KotlinLogging.logger {}

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
            )
        commandExecutor.execute(command = command)
    }

    override fun getMyMeetingList(meetingRequestDto: GetMeetupListRequestDto) {
        // Used by MCP tool only — returns DTOs directly.
        meetingRepository.getAllMeetingByUserId(userId = meetingRequestDto.userId)
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = false)
    fun createNewMeeting(event: RequestMeetingContextResult) {
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

    /**
     * Persists a participant's Accept/Decline decision atomically with the enclosing
     * `@Transactional` boundary of the interaction handler that produced this event.
     *
     * Throws if zero rows matched so the enclosing transaction rolls back instead of
     * silently acknowledging a decision that was never recorded. Practical triggers:
     * meeting deleted, participant removed, idempotencyKey corruption in button value.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = false)
    fun updateParticipantAttendance(event: UpdateMeetingAttendanceEvent) {
        val payload = event.payload
        val rowsUpdated =
            retryService.execute(
                action = {
                    meetingRepository.updateParticipantAttendance(
                        meetingIdempotencyKey = payload.meetingIdempotencyKey,
                        userId = payload.participantUserId,
                        isAttending = payload.isAttending,
                        absentReason = payload.absentReason,
                    )
                },
            )
        check(rowsUpdated > 0) {
            "No participant row matched meetingIdempotencyKey=${payload.meetingIdempotencyKey} " +
                "userId=${payload.participantUserId}; refusing to acknowledge an unrecorded decision."
        }
    }

    @EventListener
    fun getMeetingListEvent(event: GetMeetingListEvent) {
        val payload = event.payload
        val slackEvent =
            runCatching {
                meetingRepository.getMeetingsByUserIdInRange(
                    userId = payload.publisherId,
                    startAt = payload.startDate,
                    endAt = payload.endDate,
                )
            }.fold(
                onSuccess = { meetings ->
                    slackEventBuilder.getMeetingListFormRequest(
                        myMeetings = meetings,
                        commandBasicInfo = payload.responseBasicInfo,
                        commandType = CommandType.PIPELINE,
                        commandDetailType = CommandDetailType.GET_MEETING_LIST,
                    )
                },
                onFailure = { exception ->
                    log.error(exception) {
                        "Failed to fetch meeting list for publisherId=${payload.publisherId} idempotencyKey=${event.idempotencyKey}"
                    }
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = "Failed to fetch your meetings. Please try again later.",
                        commandBasicInfo = payload.responseBasicInfo,
                        commandType = CommandType.PIPELINE,
                        commandDetailType = CommandDetailType.ERROR_RESPONSE,
                    )
                },
            )
        val queue = DefaultEventQueue<CommandEvent<EventPayload>>()
        @Suppress("UNCHECKED_CAST")
        queue.offer(event = slackEvent as CommandEvent<EventPayload>)
        eventPublisher.publishEvent(events = queue)
    }
}
