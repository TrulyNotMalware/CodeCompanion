package dev.notypie.application.service.standup

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CreateStandupRoutineEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutinePayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.RoutineMember
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.escapeMrkdwn
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.format.DateTimeFormatter

private val setupLog = KotlinLogging.logger {}

@Service
class StandupRoutineSetupService(
    private val standupRepository: StandupRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    @EventListener
    fun createRoutine(event: CreateStandupRoutineEvent) {
        val payload = event.payload
        val activeNames =
            standupRepository
                .findActiveRoutinesByChannel(commandChannel = payload.commandChannel)
                .map { routine -> Routine.normalizeName(raw = routine.name) }
        val message =
            runCatching { buildRoutine(payload = payload, activeNames = activeNames) }
                .fold(
                    onSuccess = { routine ->
                        confirmationMessage(routine = standupRepository.createRoutine(routine = routine))
                    },
                    onFailure = { exception ->
                        setupLog.warn(exception) {
                            "Standup routine setup rejected: name=${payload.name} creatorId=${payload.creatorId} " +
                                "idempotencyKey=${event.idempotencyKey}"
                        }
                        val reason = exception.message?.escapeMrkdwn() ?: "invalid input"
                        "Couldn't create the standup routine: $reason. " +
                            "_Please run /standup setup again and review your inputs._"
                    },
                )
        val replyInfo = payload.responseBasicInfo.copy(channel = payload.creatorId)
        outboundStager
            .stage(
                message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = replyInfo.channel),
                        content = MessageContent.Text(headline = null, markdown = message),
                        detailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    ),
                basicInfo = replyInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    private fun buildRoutine(payload: CreateStandupRoutinePayload, activeNames: List<String>): Routine {
        val cutoffMinutes =
            requireNotNull(payload.cutoffMinutes) {
                "cutoff must be a whole number of minutes between ${Routine.MIN_CUTOFF_MINUTES} and " +
                    "${Routine.MAX_CUTOFF_MINUTES}"
            }
        val routine =
            Routine(
                name = Routine.normalizeName(raw = payload.name),
                creatorId = payload.creatorId,
                commandChannel = payload.commandChannel,
                summaryChannel = payload.summaryChannel,
                questions = payload.questions,
                triggerLocalTime = payload.triggerLocalTime,
                cutoffOffset = Duration.ofMinutes(cutoffMinutes),
                weekdays = payload.weekdays,
                routineTimezone = payload.timezone,
            )
        require(activeNames.none { activeName -> activeName.equals(routine.name, ignoreCase = true) }) {
            "a standup routine named '${routine.name}' already exists in this channel"
        }
        payload.memberIds.forEach { memberId ->
            routine.addMember(
                member = RoutineMember(userId = memberId, userTimezone = payload.timezone),
            )
        }
        return routine
    }

    private fun confirmationMessage(routine: Routine): String {
        val members = routine.memberIdSnapshot().joinToString(" ") { "<@$it>" }
        val weekdays =
            routine.weekdays
                .sortedBy { it.value }
                .joinToString(", ") { day -> day.name.lowercase().replaceFirstChar { it.uppercase() } }
        val triggerTime = routine.triggerLocalTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "Standup routine *${routine.name.escapeMrkdwn()}* created — ${routine.questions.size} questions, " +
            "members $members, weekdays $weekdays, daily at $triggerTime."
    }
}
