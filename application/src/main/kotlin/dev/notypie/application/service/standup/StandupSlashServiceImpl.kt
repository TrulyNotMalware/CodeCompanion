package dev.notypie.application.service.standup

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.entity.slash.StandupCommand
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.dto.StandupAnswerCandidate
import dev.notypie.domain.standup.dto.StandupSessionDto
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import dev.notypie.repository.standup.StandupRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

@Service
class StandupSlashServiceImpl(
    private val commandExecutor: CommandExecutor,
    private val standupRepository: StandupRepository,
    private val clock: Clock,
) : StandupSlashService {
    @Transactional
    override fun handleStandup(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val command =
            StandupCommand(
                idempotencyKey = idempotencyKey,
                commandData = commandData,
                answerCandidates = findAnswerCandidates(commandData = commandData),
            )
        commandExecutor.execute(command = command)
    }

    private fun findAnswerCandidates(commandData: InboundCommand): List<StandupAnswerCandidate> {
        if (commandData.subCommands.firstOrNull() != StandupSubCommandDefinition.ANSWER.subCommandIdentifier) {
            return emptyList()
        }
        val now = clock.instant()
        return standupRepository
            .findActiveRoutinesByChannel(commandChannel = commandData.channel)
            .mapNotNull { routine -> answerCandidateOf(routine = routine, actorId = commandData.actorId, now = now) }
    }

    private fun answerCandidateOf(routine: RoutineDto, actorId: String, now: Instant): StandupAnswerCandidate? {
        val member = routine.members.firstOrNull { it.userId == actorId } ?: return null
        val memberToday = LocalDate.ofInstant(now, member.userTimezone)
        val eligible =
            listOf(memberToday.minusDays(1), memberToday)
                .mapNotNull { date ->
                    standupRepository.findSession(routineUid = routine.routineUid, sessionDate = date)
                }.filter { session -> session.status == SessionStatus.COLLECTING && now.isBefore(session.cutoffAt) }
        val session =
            eligible.firstOrNull { it.promptFiredFor(userId = actorId, now = now) && !it.answeredBy(userId = actorId) }
                ?: eligible.firstOrNull { !it.answeredBy(userId = actorId) }
                ?: eligible.minByOrNull { it.sessionDate }
                ?: return null
        return StandupAnswerCandidate(
            sessionUid = session.sessionUid,
            routineUid = routine.routineUid,
            routineName = routine.name,
        )
    }

    private fun StandupSessionDto.promptFiredFor(userId: String, now: Instant): Boolean =
        dispatches.any { dispatch -> dispatch.userId == userId && !dispatch.dmTriggerAt.isAfter(now) }

    private fun StandupSessionDto.answeredBy(userId: String): Boolean =
        answers.any { answer -> answer.userId == userId }
}
