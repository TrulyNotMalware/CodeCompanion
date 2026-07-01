package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.entity.SessionDispatch
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.event.SendSlackMessageEvent
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
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
    private val slackEventBuilder: SlackApiEventConstructor,
    transactionManager: PlatformTransactionManager,
    private val applicationEventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { },
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val stuckSendingThresholdMinutes: Long = appConfig.standup.scheduler.stuckSendingThresholdMinutes
    private val dispatchBatchSize: Int = appConfig.standup.scheduler.dispatchBatchSize
    private val nudgeOffsetMinutes: Long = appConfig.standup.nudge.offsetMinutes

    /**
     * Phase 1: opens today's session + per-member dispatch rows for every active routine firing on
     * today's weekday. The unique constraint `(routine_uid, session_date)` keeps it idempotent.
     */
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

        // Each member's DM trigger is evaluated in their own zone — an LA member of a Seoul routine
        // is prompted at LA 10:00, not Seoul 10:00.
        val memberDispatches =
            routine.members.map { member ->
                val dmTriggerAt =
                    LocalDateTime
                        .of(today, routine.triggerLocalTime)
                        .atZone(member.userTimezone)
                        .toInstant()
                SessionDispatch(userId = member.userId, dmTriggerAt = dmTriggerAt)
            }

        // Cutoff anchors on the LATEST member trigger so every member gets at least cutoffOffset to
        // respond; a routine-zone-only cutoff could pass before a westward member's DM even fires.
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
            // Unique-constraint violation is the expected race. Confirm the row exists before
            // swallowing — any other violation must surface.
            val existing =
                standupRepository.findSession(routineUid = routine.routineUid, sessionDate = today)
            if (existing != null) {
                log.debug { "Session race lost (concurrent tick): routine=${routine.routineUid} date=$today" }
            } else {
                throw ex
            }
        }
    }

    /**
     * Phase 2: sends DM prompts for dispatches whose `dmTriggerAt` has passed. Queries by absolute
     * UTC time across ALL sessions (not "today in the routine zone") so members whose local trigger
     * lands on a different calendar day than the routine creator aren't stranded. Stuck SENDING rows
     * are reset first; each dispatch is claimed atomically before sending.
     */
    fun sendPendingDispatches() {
        val now = clock.instant()
        val stuckCutoff = now.minus(Duration.ofMinutes(stuckSendingThresholdMinutes))
        val reset = standupRepository.resetStuckDispatches(olderThan = stuckCutoff)
        if (reset > 0) log.warn { "Reset $reset stuck SENDING dispatch(es) to PENDING" }

        val ready = standupRepository.findPendingDispatchesBefore(before = now, limit = dispatchBatchSize)
        if (ready.isEmpty()) return

        // One-shot lookup: minimise DB chatter when many dispatches share a routine.
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

    /**
     * Sends one dispatch across three transaction boundaries: claim commits in its own tx; build +
     * outbox.save + markDispatchSent run in one tx that rolls back if markDispatchSent is a no-op
     * (recovery raced us); on failure markDispatchFailed records the audit in a fresh tx. The
     * per-claim token gates every CAS so only our own claim's outcome can be acknowledged.
     */
    private fun processDispatch(item: ReadyDispatch, routine: RoutineDto, sentAt: Instant) {
        val dispatchId = item.dispatch.id
        val userId = item.dispatch.userId
        val claimToken = UUID.randomUUID().toString()

        if (!standupRepository.claimDispatch(dispatchId = dispatchId, claimToken = claimToken)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                val commandBasicInfo =
                    CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                val dmEvent =
                    buildDmNotice(
                        slackEventBuilder = slackEventBuilder,
                        sessionUid = item.sessionUid,
                        sessionDate = item.sessionDate,
                        routineUid = routine.routineUid,
                        routineName = routine.name,
                        memberId = userId,
                        commandBasicInfo = commandBasicInfo,
                    )
                outboxRepository.save(dmEvent.toOutboxMessage())
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

    /**
     * Phase 4: DMs members who got the prompt but haven't answered, once per session, within
     * `[cutoffAt - nudgeOffset, cutoffAt)`. [StandupRepository.claimNudge]'s atomic CAS makes it
     * fire exactly once across ticks/restarts. We claim only when non-responders actually exist, so
     * an all-answered session leaves `nudged_at` NULL. Offset <= 0 disables the phase.
     */
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

        // Atomic once-only gate. Lost race / already nudged → another tick owns it.
        if (!standupRepository.claimNudge(sessionId = candidate.sessionId)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                nonResponders.forEach { userId ->
                    val commandBasicInfo =
                        CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                    val nudgeEvent =
                        buildNudgeNotice(
                            slackEventBuilder = slackEventBuilder,
                            routineName = routine.name,
                            cutoffAt = candidate.cutoffAt,
                            routineTimezone = routine.routineTimezone,
                            commandBasicInfo = commandBasicInfo,
                        )
                    outboxRepository.save(nudgeEvent.toOutboxMessage())
                }
            }

        if (outcome.isFailure) {
            // The claim already committed, so this can't be re-nudged — at-most-once, the safer
            // default for a reminder. Surface for the tick wrapper to log.
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

    /** Phase 3: detects COLLECTING sessions past their cutoff and queues summaries. */
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

/**
 * Builds the standup-prompt DM with a "Fill in standup" button; clicking it yields the trigger_id
 * the follow-up [dev.notypie.domain.command.entity.context.form.StandupFillContext] needs to open
 * the modal (a scheduler tick has no trigger_id of its own).
 */
internal fun buildDmNotice(
    slackEventBuilder: SlackApiEventConstructor,
    sessionUid: UUID,
    sessionDate: LocalDate,
    routineUid: UUID,
    routineName: String,
    memberId: String,
    commandBasicInfo: CommandBasicInfo,
): SendSlackMessageEvent {
    val approvalContents =
        ApprovalContents(
            headLineText = "$routineName — ${sessionDate.format(DISPATCH_DATE_FORMAT)}",
            reason = routineName,
            publisherId = commandBasicInfo.publisherId,
            approvalButtonName = "Fill in standup",
            rejectButtonName = "Skip",
            idempotencyKey = sessionUid,
            commandDetailType = CommandDetailType.STANDUP_FILL,
        )
    return slackEventBuilder.simpleApplyRejectRequest(
        commandDetailType = CommandDetailType.STANDUP_FILL,
        commandBasicInfo = commandBasicInfo,
        approvalContents = approvalContents,
        targetUserId = memberId,
        routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
    )
}

/**
 * Builds the once-per-session non-responder reminder: a plain `chat.postMessage` (no buttons) that
 * points the member back to the original prompt's "Fill in standup" button.
 */
internal fun buildNudgeNotice(
    slackEventBuilder: SlackApiEventConstructor,
    routineName: String,
    cutoffAt: Instant,
    routineTimezone: ZoneId,
    commandBasicInfo: CommandBasicInfo,
): SendSlackMessageEvent {
    val cutoffText = NUDGE_CUTOFF_TIME_FORMAT.format(cutoffAt.atZone(routineTimezone))
    val body =
        "⏰ Standup for *$routineName* closes at $cutoffText — you haven't responded yet. " +
            "Tap the *Fill in standup* button in your DM."
    return slackEventBuilder.simpleTextRequest(
        commandDetailType = CommandDetailType.STANDUP_FILL,
        headLineText = "Standup reminder",
        commandBasicInfo = commandBasicInfo,
        simpleString = body,
    )
}
