package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.controllers.dto.GetMeetupListRequestDto
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
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
        // MariaDB's default CLIENT_FOUND_ROWS=false makes UPDATE return 0 both when
        // no row matches AND when the row already holds the requested values (a no-op).
        // A no-op is legitimate here — it happens every time a user re-submits the same
        // reason, or picks OTHER after the provisional-OTHER write recorded by Deny click.
        // We only fail the transaction when the participant row truly doesn't exist.
        if (rowsUpdated == 0 &&
            !meetingRepository.participantExists(
                meetingIdempotencyKey = payload.meetingIdempotencyKey,
                userId = payload.participantUserId,
            )
        ) {
            error(
                "No participant row matched meetingIdempotencyKey=${payload.meetingIdempotencyKey} " +
                    "userId=${payload.participantUserId}; refusing to acknowledge an unrecorded decision.",
            )
        }
    }

    /**
     * Fallback path invoked when `views.open` for the decline-reason modal fails (trigger_id
     * expired, Slack API error, network). Persistence is NOT re-published here — the Deny
     * click already emitted a provisional [UpdateMeetingAttendanceEvent] with
     * [RejectReason.OTHER] from [MeetingApprovalResponseContext.handleDecline], so by the
     * time this listener runs the decline is either already durable in the txn or about to
     * be committed alongside it. We only surface the failure to the user so they can retry
     * and pick a specific reason.
     */
    @EventListener
    fun onDeclineModalOpenFailed(event: DeclineModalOpenFailedEvent) {
        log.warn {
            "views.open fallback triggered: meetingIdempotencyKey=${event.meetingIdempotencyKey} " +
                "participantUserId=${event.participantUserId} reason=${event.reason}"
        }
        val ephemeralEvent =
            slackEventBuilder.simpleEphemeralTextRequest(
                textMessage =
                    "We couldn't open the reason picker. Your decline was noted as *Other*. " +
                        "_Tip: Click Deny again to pick a specific reason._",
                commandBasicInfo =
                    CommandBasicInfo(
                        appId = event.apiAppId,
                        appToken = "",
                        publisherId = event.participantUserId,
                        channel = event.channel,
                        idempotencyKey = event.idempotencyKey,
                    ),
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                targetUserId = event.participantUserId,
            )
        val queue = DefaultEventQueue<CommandEvent<EventPayload>>()
        @Suppress("UNCHECKED_CAST")
        queue.offer(event = ephemeralEvent as CommandEvent<EventPayload>)
        eventPublisher.publishEvent(events = queue)
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
