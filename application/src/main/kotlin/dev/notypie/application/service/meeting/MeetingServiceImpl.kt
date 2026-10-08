package dev.notypie.application.service.meeting

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.calendar.MeetingCalendarMirror
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
import dev.notypie.repository.meeting.AddParticipantResult
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.meeting.isMeetingWriteConflict
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.MultiValueMap

@Service
class MeetingServiceImpl(
    private val meetingRepository: MeetingRepository,
    private val commandExecutor: CommandExecutor,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    transactionManager: PlatformTransactionManager,
    private val calendarMirror: MeetingCalendarMirror,
) : MeetingService {
    private val log = KotlinLogging.logger {}
    private val writeTemplate = isolatedWriteTemplate(transactionManager = transactionManager)
    private val replyTemplate = TransactionTemplate(transactionManager)

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
        meetingRepository.createNewMeeting(
            meeting = event.meeting,
            idempotencyKey = event.idempotencyKey,
            channel = event.commandBasicInfo.channel,
        )
        calendarMirror.onMeetingCreated(
            meetingIdempotencyKey = event.idempotencyKey,
            hostId = event.meeting.host.userId,
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = false)
    fun updateParticipantAttendance(event: UpdateMeetingAttendanceEvent) {
        val payload = event.payload
        val rowsUpdated =
            meetingRepository.updateParticipantAttendance(
                meetingIdempotencyKey = payload.meetingIdempotencyKey,
                userId = payload.participantUserId,
                isAttending = payload.isAttending,
                absentReason = payload.absentReason,
                absentReasonDetail = payload.absentReasonDetail,
            )
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
        if (rowsUpdated == 0) return
        calendarMirror.onAttendanceChanged(
            meetingIdempotencyKey = payload.meetingIdempotencyKey,
            userId = payload.participantUserId,
            attending = payload.isAttending,
        )
    }

    @EventListener
    @Transactional
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

    @EventListener
    fun cancelMeeting(event: CancelMeetingEvent) = MeetingWriteDeferral.runOrDefer { cancel(event = event) }

    private fun cancel(event: CancelMeetingEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        writeTemplate
            .executeRetryingOnConflict {
                val canceled =
                    meetingRepository.markMeetingCanceled(
                        meetingUid = payload.meetingUid,
                        requesterId = payload.requesterId,
                    )
                if (canceled) calendarMirror.onMeetingCanceled(meetingUid = payload.meetingUid)
                publishCancelEphemeral(
                    message =
                        if (canceled) "Meeting canceled." else "Meeting was already canceled, or you are not the host.",
                    basicInfo = basicInfo,
                    targetUserId = payload.requesterId,
                )
            }.onFailure { exception ->
                replyTemplate.stageFailureReply(failure = exception) {
                    publishCancelEphemeral(
                        message = "Failed to cancel the meeting. Please try again later.",
                        basicInfo = basicInfo,
                        targetUserId = payload.requesterId,
                    )
                }
                log.error(exception) {
                    "Failed to cancel meeting meetingUid=${payload.meetingUid} " +
                        "requesterId=${payload.requesterId} idempotencyKey=${event.idempotencyKey}"
                }
            }
    }

    private fun publishCancelEphemeral(message: String, basicInfo: CommandBasicInfo, targetUserId: String) {
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = UserRef(id = targetUserId),
                        content = MessageContent.Text(headline = null, markdown = message),
                        detailType = CommandDetailType.CANCEL_MEETING,
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    @EventListener
    fun addParticipants(event: AddParticipantEvent) =
        MeetingWriteDeferral.runOrDefer { addParticipantsNow(event = event) }

    private fun addParticipantsNow(event: AddParticipantEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        writeTemplate
            .executeRetryingOnConflict {
                val result =
                    meetingRepository.addParticipants(
                        meetingUid = payload.meetingUid,
                        requesterId = payload.requesterId,
                        participantUserIds = payload.participantUserIds,
                    )
                val meeting = result.meeting
                if (result.outcome == AddParticipantResult.Outcome.ADDED && meeting != null) {
                    notifyAddedParticipants(
                        meeting = meeting,
                        addedUserIds = result.addedUserIds,
                        basicInfo = basicInfo,
                    )
                }
                publishHostEphemeral(
                    message = addParticipantMessage(result = result),
                    basicInfo = basicInfo,
                    targetUserId = payload.requesterId,
                )
            }.onFailure { exception ->
                replyTemplate.stageFailureReply(failure = exception) {
                    publishHostEphemeral(
                        message = "Failed to add participants. Please try again later.",
                        basicInfo = basicInfo,
                        targetUserId = payload.requesterId,
                    )
                }
                log.error(exception) {
                    "Failed to add participants meetingUid=${payload.meetingUid} " +
                        "requesterId=${payload.requesterId} idempotencyKey=${event.idempotencyKey}"
                }
            }
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
        val meetings =
            meetingRepository.getMeetingsByUserIdInRange(
                userId = payload.publisherId,
                startAt = payload.startDate,
                endAt = payload.endDate,
            )
        val message =
            OutboundMessage.Ephemeral(
                target = ConversationTarget(id = basicInfo.channel),
                content = MessageContent.MeetingList(meetings = meetings, currentUserId = basicInfo.publisherId),
            )
        outboundStager.stage(message = message, basicInfo = basicInfo)?.let { eventPublisher.publishOne(event = it) }
    }
}

internal fun isolatedWriteTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
    TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

internal fun TransactionTemplate.executeRetryingOnConflict(action: () -> Unit): Result<Unit> {
    val firstAttempt = attempt(action = action)
    val failure = firstAttempt.exceptionOrNull()
    if (failure !is RuntimeException || !failure.isMeetingWriteConflict()) return firstAttempt
    return attempt(action = action)
}

internal fun TransactionTemplate.stageFailureReply(failure: Throwable, reply: () -> Unit) {
    try {
        executeWithoutResult { reply() }
    } catch (replyFailure: RuntimeException) {
        failure.addSuppressed(replyFailure)
    }
}

private fun TransactionTemplate.attempt(action: () -> Unit): Result<Unit> =
    try {
        executeWithoutResult { action() }
        Result.success(Unit)
    } catch (exception: RuntimeException) {
        Result.failure(exception)
    }
