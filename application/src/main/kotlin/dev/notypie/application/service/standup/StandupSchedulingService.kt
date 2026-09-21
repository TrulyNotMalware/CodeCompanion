package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.entity.SessionDispatch
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.standup.NudgeCandidateSession
import dev.notypie.repository.standup.ReadyDispatch
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = KotlinLogging.logger {}

private val DISPATCH_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val NUDGE_CUTOFF_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Service
class StandupSchedulingService(
    private val standupRepository: StandupRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
    private val applicationEventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { },
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val stuckSendingThresholdMinutes: Long = appConfig.standup.scheduler.stuckSendingThresholdMinutes
    private val dispatchBatchSize: Int = appConfig.standup.scheduler.dispatchBatchSize
    private val nudgeOffsetMinutes: Long = appConfig.standup.nudge.offsetMinutes

    fun openSessionsForToday() {
        val now = clock.instant()
        standupRepository.listActiveRoutines().forEach { routine ->
            openSessionForRoutine(routine = routine, now = now)
        }
    }

    private fun openSessionForRoutine(routine: RoutineDto, now: Instant) {
        val today = LocalDate.ofInstant(now, routine.routineTimezone)
        if (today.dayOfWeek !in routine.weekdays) return
        if (standupRepository.findSession(routineUid = routine.routineUid, sessionDate = today) != null) return

        val memberDispatches =
            routine.members.map { member ->
                val dmTriggerAt =
                    LocalDateTime
                        .of(today, routine.triggerLocalTime)
                        .atZone(member.userTimezone)
                        .toInstant()
                SessionDispatch(userId = member.userId, dmTriggerAt = dmTriggerAt)
            }

        val routineFallbackTrigger =
            LocalDateTime.of(today, routine.triggerLocalTime).atZone(routine.routineTimezone).toInstant()
        val cutoffAnchor =
            memberDispatches.maxOfOrNull { it.dmTriggerAt } ?: routineFallbackTrigger
        val cutoffAt = cutoffAnchor.plus(routine.cutoffOffset)

        val session =
            StandupSession(
                routineUid = routine.routineUid,
                sessionDate = today,
                cutoffAt = cutoffAt,
            )
        memberDispatches.forEach(session::addDispatch)

        try {
            standupRepository.createSession(session = session)
            log.info {
                "Standup session opened: routine=${routine.routineUid} date=$today members=${routine.members.size}"
            }
        } catch (ex: DataIntegrityViolationException) {
            // Unique-constraint violation is the expected race; confirm the row exists before swallowing.
            val existing =
                standupRepository.findSession(routineUid = routine.routineUid, sessionDate = today)
            if (existing != null) {
                log.debug { "Session race lost (concurrent tick): routine=${routine.routineUid} date=$today" }
            } else {
                throw ex
            }
        }
    }

    // Queries absolute UTC across all sessions so cross-timezone members aren't stranded on the wrong day.
    fun sendPendingDispatches() {
        val now = clock.instant()
        val stuckCutoff = now.minus(Duration.ofMinutes(stuckSendingThresholdMinutes))
        val reset = standupRepository.resetStuckDispatches(olderThan = stuckCutoff)
        if (reset > 0) log.warn { "Reset $reset stuck SENDING dispatch(es) to PENDING" }

        val ready = standupRepository.findPendingDispatchesBefore(before = now, limit = dispatchBatchSize)
        if (ready.isEmpty()) return

        val routinesByUid = standupRepository.listActiveRoutines().associateBy { it.routineUid }

        ready.forEach { item ->
            val routine = routinesByUid[item.routineUid]
            if (routine == null) {
                log.warn {
                    "Pending dispatch points at unknown/inactive routine: " +
                        "dispatchId=${item.dispatch.id} routineUid=${item.routineUid}"
                }
                return@forEach
            }
            processDispatch(item = item, routine = routine, sentAt = now)
        }
    }

    // Claim, save+markSent, and failure-record each run in their own tx; the claim token gates the CAS.
    private fun processDispatch(item: ReadyDispatch, routine: RoutineDto, sentAt: Instant) {
        val dispatchId = item.dispatch.id
        val userId = item.dispatch.userId
        val claimToken = UUID.randomUUID().toString()

        if (!standupRepository.claimDispatch(dispatchId = dispatchId, claimToken = claimToken)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                val commandBasicInfo =
                    CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                val message =
                    buildDmNotice(
                        sessionUid = item.sessionUid,
                        sessionDate = item.sessionDate,
                        routineUid = routine.routineUid,
                        routineName = routine.name,
                        memberId = userId,
                        commandBasicInfo = commandBasicInfo,
                    )
                outboxRepository.save(
                    outboundMessagePort.toRow(message = message, basicInfo = commandBasicInfo),
                )
                if (!standupRepository.markDispatchSent(
                        dispatchId = dispatchId,
                        claimToken = claimToken,
                        sentAt = sentAt,
                    )
                ) {
                    error("markDispatchSent had no effect for dispatch $dispatchId — rolling back.")
                }
            }

        if (outcome.isFailure) {
            val ex = outcome.exceptionOrNull()!!
            log.error(ex) { "Standup DM dispatch failed: dispatchId=$dispatchId userId=$userId" }
            if (!standupRepository.markDispatchFailed(
                    dispatchId = dispatchId,
                    claimToken = claimToken,
                    reason = ex.message ?: "unknown",
                )
            ) {
                log.warn { "markDispatchFailed no-op for dispatch $dispatchId — recovery already reset or re-claimed." }
            }
        } else {
            log.info { "Standup DM enqueued: dispatchId=$dispatchId userId=$userId" }
        }
    }

    fun nudgeNonResponders() {
        if (nudgeOffsetMinutes <= 0L) return

        val now = clock.instant()
        val nudgeWindowEnd = now.plus(Duration.ofMinutes(nudgeOffsetMinutes))
        val candidates =
            standupRepository.findCollectingSessionsForNudge(now = now, nudgeWindowEnd = nudgeWindowEnd)
        if (candidates.isEmpty()) return

        val routinesByUid = standupRepository.listActiveRoutines().associateBy { it.routineUid }

        candidates.forEach { candidate ->
            nudgeCandidate(candidate = candidate, routinesByUid = routinesByUid)
        }
    }

    private fun nudgeCandidate(candidate: NudgeCandidateSession, routinesByUid: Map<UUID, RoutineDto>) {
        val routine = routinesByUid[candidate.routineUid]
        if (routine == null) {
            log.warn {
                "Nudge candidate points at unknown/inactive routine: " +
                    "sessionUid=${candidate.sessionUid} routineUid=${candidate.routineUid}"
            }
            return
        }

        val nonResponders = candidate.sentMemberIds - candidate.answeredUserIds
        if (nonResponders.isEmpty()) return

        if (!standupRepository.claimNudge(sessionId = candidate.sessionId)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                nonResponders.forEach { userId ->
                    val commandBasicInfo =
                        CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                    val message =
                        buildNudgeNotice(
                            routineName = routine.name,
                            cutoffAt = candidate.cutoffAt,
                            routineTimezone = routine.routineTimezone,
                            commandBasicInfo = commandBasicInfo,
                        )
                    outboxRepository.save(
                        outboundMessagePort.toRow(message = message, basicInfo = commandBasicInfo),
                    )
                }
            }

        if (outcome.isFailure) {
            log.error(outcome.exceptionOrNull()) {
                "Standup nudge enqueue failed after claim: sessionUid=${candidate.sessionUid}"
            }
        } else {
            log.info {
                "Standup nudge enqueued: sessionUid=${candidate.sessionUid} " +
                    "routineUid=${candidate.routineUid} nonResponders=${nonResponders.size}"
            }
        }
    }

    fun detectCutoffs() {
        val now = clock.instant()
        standupRepository.findCollectingSessionsPastCutoff(before = now).forEach { session ->
            log.info { "Standup cutoff reached: sessionUid=${session.sessionUid} routineUid=${session.routineUid}" }
            applicationEventPublisher.publishEvent(
                StandupCutoffEvent(
                    sessionId = session.sessionId,
                    sessionUid = session.sessionUid,
                    routineUid = session.routineUid,
                    sessionDate = session.sessionDate,
                ),
            )
        }
    }
}

// Button click yields the trigger_id the follow-up modal needs — a scheduler tick has none of its own.
internal fun buildDmNotice(
    sessionUid: UUID,
    sessionDate: LocalDate,
    routineUid: UUID,
    routineName: String,
    memberId: String,
    commandBasicInfo: CommandBasicInfo,
): OutboundMessage.Approval {
    val approvalContents =
        ApprovalContents(
            headLineText = "$routineName — ${sessionDate.format(DISPATCH_DATE_FORMAT)}",
            reason = routineName,
            publisherId = commandBasicInfo.publisherId,
            approvalButtonName = "Fill in standup",
            rejectButtonName = "Skip",
            idempotencyKey = sessionUid,
            commandDetailType = CommandDetailType.STANDUP_PROMPT,
        )
    return OutboundMessage.Approval(
        target = ConversationTarget(id = commandBasicInfo.channel),
        recipient = UserRef(id = memberId),
        approval = approvalContents,
        routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
    )
}

internal fun buildNudgeNotice(
    routineName: String,
    cutoffAt: Instant,
    routineTimezone: ZoneId,
    commandBasicInfo: CommandBasicInfo,
): OutboundMessage.ChannelMessage {
    val cutoffText = NUDGE_CUTOFF_TIME_FORMAT.format(cutoffAt.atZone(routineTimezone))
    val body =
        "⏰ Standup for *$routineName* closes at $cutoffText — you haven't responded yet. " +
            "Tap the *Fill in standup* button in your DM."
    return OutboundMessage.ChannelMessage(
        target = ConversationTarget(id = commandBasicInfo.channel),
        content = MessageContent.Text(headline = "Standup reminder", markdown = body),
        detailType = CommandDetailType.STANDUP_PROMPT,
    )
}
