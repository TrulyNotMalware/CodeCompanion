package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

/**
 * Routes an outbound effect onto the event bus, splitting on the one thing that can't be relayed:
 * modals. `views.open` needs the request thread's `trigger_id` (it expires 3s after issuance), so a
 * modal is rendered here and staged synchronously into an [dev.notypie.impl.command.event.OpenViewEvent].
 *
 * Every other family is wrapped, unrendered, in an [OutboundMessageEnqueued]: it is persisted to the
 * outbox transport-neutral and rendered to a wire payload only at deliver time by [SlackOutboundRenderer].
 * This keeps rendering in exactly one place while the stager owns the modal-vs-enqueue decision.
 */
class SlackOutboundStager(
    private val slackEventBuilder: SlackApiEventConstructor,
    private val standupRepository: StandupRepository,
) : OutboundMessageStager {
    override fun stage(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (message) {
            is OutboundMessage.OpenModal -> stageModal(message = message, basicInfo = basicInfo)

            else ->
                OutboundMessageEnqueued(
                    idempotencyKey = basicInfo.idempotencyKey,
                    payload = OutboundMessageEnqueuedPayload(message = message, basicInfo = basicInfo),
                )
        }

    private fun stageModal(
        message: OutboundMessage.OpenModal,
        basicInfo: CommandBasicInfo,
    ): CommandEvent<EventPayload>? =
        when (val form = message.form) {
            is ModalForm.Reschedule ->
                if (message.handle.raw.isBlank()) {
                    log.warn { "Blank triggerId; cannot open reschedule modal for meetingUid=${form.meetingUid}" }
                    null
                } else {
                    slackEventBuilder.openRescheduleMeetingModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST,
                        triggerId = message.handle.raw,
                        meetingUid = form.meetingUid,
                        requesterId = form.requesterId,
                        channel = form.channel.id,
                        // Message carries no stored start; the host adjusts both pickers anyway.
                        currentStartAt = LocalDateTime.now(),
                    )
                }

            is ModalForm.AddParticipant ->
                if (message.handle.raw.isBlank()) {
                    log.warn { "Blank triggerId; cannot open add-participant modal for meetingUid=${form.meetingUid}" }
                    null
                } else {
                    slackEventBuilder.openAddParticipantModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST,
                        triggerId = message.handle.raw,
                        meetingUid = form.meetingUid,
                        requesterId = form.requesterId,
                        channel = form.channel.id,
                    )
                }

            is ModalForm.StandupSetup ->
                if (message.handle.raw.isBlank()) {
                    log.warn { "Blank triggerId; cannot open standup setup modal for creatorId=${form.creatorId}" }
                    null
                } else {
                    slackEventBuilder.openStandupSetupModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST,
                        triggerId = message.handle.raw,
                        creatorId = form.creatorId,
                        commandChannel = form.commandChannel.id,
                    )
                }

            is ModalForm.StandupFill ->
                if (message.handle.raw.isBlank()) {
                    log.warn { "Blank triggerId; cannot open standup modal for sessionUid=${form.sessionUid}" }
                    null
                } else {
                    val routine = standupRepository.getRoutine(routineUid = form.routineUid)
                    val session = standupRepository.findSession(sessionUid = form.sessionUid)
                    if (session == null) {
                        log.warn { "Standup session not found: sessionUid=${form.sessionUid}" }
                        null
                    } else {
                        slackEventBuilder.openStandupModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_PROMPT,
                            triggerId = message.handle.raw,
                            sessionUid = form.sessionUid,
                            routineName = routine.name,
                            sessionDate = session.sessionDate,
                            questions = routine.questions,
                            userId = form.requesterId,
                            noticeChannel = form.originNotice.conversation.id,
                            noticeMessageTs = form.originNotice.messageId,
                        )
                    }
                }

            is ModalForm.CveSubscribe ->
                if (message.handle.raw.isBlank()) {
                    log.warn { "Blank triggerId; cannot open cve subscribe modal" }
                    null
                } else {
                    slackEventBuilder.openCveSubscribeModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.CVE_SUBSCRIBE_REQUEST,
                        triggerId = message.handle.raw,
                        topics = form.topics,
                    )
                }

            is ModalForm.CveUnsubscribe ->
                if (message.handle.raw.isBlank()) {
                    log.warn { "Blank triggerId; cannot open cve unsubscribe modal" }
                    null
                } else {
                    slackEventBuilder.openCveUnsubscribeModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.CVE_UNSUBSCRIBE_REQUEST,
                        triggerId = message.handle.raw,
                        topics = form.topics,
                    )
                }

            is ModalForm.DeclineReason ->
                slackEventBuilder.openDeclineReasonModalRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
                    triggerId = message.handle.raw,
                    meetingIdempotencyKey = form.meetingIdempotencyKey,
                    participantUserId = form.participantUserId,
                    meetingTitle = form.meetingTitle,
                    noticeChannel =
                        form.originNotice
                            ?.conversation
                            ?.id
                            .orEmpty(),
                    noticeMessageTs = form.originNotice?.messageId.orEmpty(),
                )
        }
}
