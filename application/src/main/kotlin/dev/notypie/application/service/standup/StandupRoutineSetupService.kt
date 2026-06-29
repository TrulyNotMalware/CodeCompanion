package dev.notypie.application.service.standup

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CreateStandupRoutineEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutinePayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.RoutineMember
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.format.DateTimeFormatter

private val setupLog = KotlinLogging.logger {}

/**
 * Persists a new standup [Routine] assembled by the `/standup setup` modal submission, then
 * posts a confirmation back to the command channel. Mirrors [StandupAnswerService]'s
 * `@EventListener`-driven shape: the context emitted a [CreateStandupRoutineEvent], the
 * resolver lifted it, and this service owns the repository write.
 *
 * [Routine]'s `init` block is the single validation authority; any invalid combination
 * (empty questions, no weekdays, non-positive cutoff, etc.) throws here. We catch it, skip the
 * write, and surface a friendly ephemeral so the user can retry instead of silently failing.
 */
@Service
class StandupRoutineSetupService(
    private val standupRepository: StandupRepository,
    private val slackEventBuilder: SlackApiEventConstructor,
    private val eventPublisher: EventPublisher,
) {
    @EventListener
    fun createRoutine(event: CreateStandupRoutineEvent) {
        val payload = event.payload
        val message =
            runCatching { persistRoutine(payload = payload) }
                .fold(
                    onSuccess = { routine -> confirmationMessage(routine = routine) },
                    onFailure = { exception ->
                        setupLog.warn(exception) {
                            "Standup routine setup rejected: name=${payload.name} creatorId=${payload.creatorId} " +
                                "idempotencyKey=${event.idempotencyKey}"
                        }
                        "Couldn't create the standup routine: ${exception.message ?: "invalid input"}. " +
                            "_Please run /standup setup again and review your inputs._"
                    },
                )
        val ephemeralEvent =
            slackEventBuilder.simpleEphemeralTextRequest(
                textMessage = message,
                commandBasicInfo = payload.responseBasicInfo,
                commandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
            )
        eventPublisher.publishOne(event = ephemeralEvent)
    }

    private fun persistRoutine(payload: CreateStandupRoutinePayload): Routine {
        val routine =
            Routine(
                name = payload.name,
                creatorId = payload.creatorId,
                commandChannel = payload.commandChannel,
                summaryChannel = payload.summaryChannel,
                questions = payload.questions,
                triggerLocalTime = payload.triggerLocalTime,
                cutoffOffset = Duration.ofMinutes(payload.cutoffMinutes),
                weekdays = payload.weekdays,
                routineTimezone = payload.timezone,
            )
        // v1 simplification: each member adopts the routine's timezone.
        payload.memberIds.forEach { memberId ->
            routine.addMember(
                member = RoutineMember(userId = memberId, userTimezone = payload.timezone),
            )
        }
        return standupRepository.createRoutine(routine = routine)
    }

    private fun confirmationMessage(routine: Routine): String {
        val members = routine.memberIdSnapshot().joinToString(" ") { "<@$it>" }
        val weekdays =
            routine.weekdays
                .sortedBy { it.value }
                .joinToString(", ") { day -> day.name.lowercase().replaceFirstChar { it.uppercase() } }
        val triggerTime = routine.triggerLocalTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "Standup routine *${routine.name}* created — ${routine.questions.size} questions, " +
            "members $members, weekdays $weekdays, daily at $triggerTime."
    }
}
