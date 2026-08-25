package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AddParticipantEvent
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.AddParticipantResult
import dev.notypie.repository.meeting.MeetingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.util.MultiValueMap

@Service
class MeetingServiceImpl(
    private val meetingRepository: MeetingRepository,
    private val retryService: RetryService,
    private val commandExecutor: CommandExecutor,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) : MeetingService {
    private val log = KotlinLogging.logger {}

    @Transactional
    override fun handleMeeting(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val command =
            RequestMeetingCommand(
                commandData = commandData,
                idempotencyKey = idempotencyKey,
            )
        commandExecutor.execute(command = command)
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
                        absentReasonDetail = payload.absentReasonDetail,
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
        val basicInfo =
            CommandBasicInfo.forOutbound(
                appId = event.apiAppId,
                publisherId = event.participantUserId,
                channel = event.channel,
                idempotencyKey = event.idempotencyKey,
            )
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = UserRef(id = event.participantUserId),
                        content =
                            MessageContent.Text(
                                headline = null,
                                markdown =
                                    "We couldn't open the reason picker. Your decline was noted as *Other*. " +
                                        "_Tip: Click Deny again to pick a specific reason._",
                            ),
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    /**
     * Cancels a meeting on behalf of a host who clicked the inline Cancel button on
     * `/meetup list`. Authorization is enforced atomically by the repository's WHERE clause —
     * the UPDATE only matches when [CancelMeetingEvent.payload.requesterId] equals
     * `meetings.publisherId` AND `is_canceled = false`. Returning `false` collapses three
     * failure modes (missing meeting, non-host, already-canceled) into a single no-op
     * branch that surfaces a friendly ephemeral instead of an error.
     */
    @EventListener
    fun cancelMeeting(event: CancelMeetingEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        val message =
            runCatching {
                meetingRepository.markMeetingCanceled(
                    meetingUid = payload.meetingUid,
                    requesterId = payload.requesterId,
                )
            }.fold(
                onSuccess = { canceled ->
                    if (canceled) {
                        "Meeting canceled."
                    } else {
                        "Meeting was already canceled, or you are not the host."
                    }
                },
                onFailure = { exception ->
                    log.error(exception) {
                        "Failed to cancel meeting meetingUid=${payload.meetingUid} " +
                            "requesterId=${payload.requesterId} idempotencyKey=${event.idempotencyKey}"
                    }
                    "Failed to cancel the meeting. Please try again later."
                },
            )
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = UserRef(id = payload.requesterId),
                        content = MessageContent.Text(headline = null, markdown = message),
                        detailType = CommandDetailType.CANCEL_MEETING,
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    /**
     * Adds participants to an existing meeting on behalf of a host who submitted the add-participant
     * modal opened from `/meetup list`. Authorization (host-only), de-duplication, the already-started
     * guard, and the `MAX_PARTICIPANTS` invariant are all enforced by [MeetingRepository.addParticipants]
     * through the Meeting aggregate. On success each newly added user receives the same Accept/Decline
     * approval notice the creation flow sends (keyed by the meeting's idempotency key so their decision
     * updates the right meeting), and the host gets an in-channel confirmation ephemeral.
     */
    @EventListener
    fun addParticipants(event: AddParticipantEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        val result =
            runCatching {
                meetingRepository.addParticipants(
                    meetingUid = payload.meetingUid,
                    requesterId = payload.requesterId,
                    participantUserIds = payload.participantUserIds,
                )
            }.getOrElse { exception ->
                log.error(exception) {
                    "Failed to add participants meetingUid=${payload.meetingUid} " +
                        "requesterId=${payload.requesterId} idempotencyKey=${event.idempotencyKey}"
                }
                publishHostEphemeral(
                    message = "Failed to add participants. Please try again later.",
                    basicInfo = basicInfo,
                    targetUserId = payload.requesterId,
                )
                return
            }

        val meeting = result.meeting
        if (result.outcome == AddParticipantResult.Outcome.ADDED && meeting != null) {
            notifyAddedParticipants(meeting = meeting, addedUserIds = result.addedUserIds, basicInfo = basicInfo)
        }
        publishHostEphemeral(
            message = addParticipantMessage(result = result),
            basicInfo = basicInfo,
            targetUserId = payload.requesterId,
        )
    }

    private fun addParticipantMessage(result: AddParticipantResult): String =
        when (result.outcome) {
            AddParticipantResult.Outcome.ADDED -> {
                val mentions = result.addedUserIds.joinToString(" ") { "<@$it>" }
                "Added $mentions to the meeting."
            }

            AddParticipantResult.Outcome.NO_NEW_PARTICIPANTS ->
                "Those people are already on this meeting."

            AddParticipantResult.Outcome.OVER_CAPACITY ->
                "That would exceed the ${Meeting.MAX_PARTICIPANTS}-participant limit for a meeting."

            AddParticipantResult.Outcome.MEETING_STARTED ->
                "This meeting has already started, so participants can no longer be added."

            AddParticipantResult.Outcome.NOT_AUTHORIZED ->
                "Meeting was canceled, or you are not the host."

            AddParticipantResult.Outcome.MEETING_NOT_FOUND ->
                "That meeting no longer exists."
        }

    private fun notifyAddedParticipants(meeting: MeetingDto, addedUserIds: List<String>, basicInfo: CommandBasicInfo) {
        val approvalContents =
            ApprovalContents(
                headLineText = "Meeting Request!",
                reason = "You've been added to this meeting.",
                subTitle = meeting.title,
                // Key the notice by the meeting's own idempotencyKey so the recipient's Accept/Decline
                // updates this meeting's participant row (mirrors the creation-time notice).
                idempotencyKey = meeting.idempotencyKey,
                publisherId = meeting.creator,
                commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
            )
        val noticeBasicInfo =
            CommandBasicInfo.forOutbound(
                publisherId = meeting.creator,
                channel = basicInfo.channel,
                appId = basicInfo.appId,
                idempotencyKey = meeting.idempotencyKey,
            )
        addedUserIds.forEach { userId ->
            outboundStager
                .stage(
                    message =
                        OutboundMessage.Approval(
                            target = ConversationTarget(id = noticeBasicInfo.channel),
                            recipient = UserRef(id = userId),
                            approval = approvalContents,
                            routingExtras = emptyList(),
                        ),
                    basicInfo = noticeBasicInfo,
                )?.let { eventPublisher.publishOne(event = it) }
        }
    }

    private fun publishHostEphemeral(message: String, basicInfo: CommandBasicInfo, targetUserId: String) {
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = UserRef(id = targetUserId),
                        content = MessageContent.Text(headline = null, markdown = message),
                        detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    @EventListener
    fun getMeetingListEvent(event: GetMeetingListEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        val target = ConversationTarget(id = basicInfo.channel)
        val message =
            runCatching {
                meetingRepository.getMeetingsByUserIdInRange(
                    userId = payload.publisherId,
                    startAt = payload.startDate,
                    endAt = payload.endDate,
                )
            }.fold(
                onSuccess = { meetings ->
                    OutboundMessage.Ephemeral(
                        target = target,
                        content =
                            MessageContent.MeetingList(
                                meetings = meetings,
                                currentUserId = basicInfo.publisherId,
                            ),
                    )
                },
                onFailure = { exception ->
                    log.error(exception) {
                        "Failed to fetch meeting list for publisherId=${payload.publisherId} idempotencyKey=${event.idempotencyKey}"
                    }
                    OutboundMessage.Ephemeral(
                        target = target,
                        content =
                            MessageContent.Text(
                                headline = null,
                                markdown = "Failed to fetch your meetings. Please try again later.",
                            ),
                        detailType = CommandDetailType.ERROR_RESPONSE,
                    )
                },
            )
        outboundStager.stage(message = message, basicInfo = basicInfo)?.let { eventPublisher.publishOne(event = it) }
    }
}
