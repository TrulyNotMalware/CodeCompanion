package dev.notypie.application.service.standup

import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StandupOpsAction
import dev.notypie.domain.command.entity.event.StandupOpsPayload
import dev.notypie.domain.command.entity.event.StandupOpsRequestEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.escapeMrkdwn
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val opsLog = KotlinLogging.logger {}

private val ROUTINE_TRIGGER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Service
class StandupRoutineOpsService(
    private val standupRepository: StandupRepository,
    private val commandRoleResolver: CommandRoleResolver,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    companion object {
        private const val RESPONSE_HEADLINE = "CodeCompanion — standup"
        private const val NO_ROUTINES_MESSAGE =
            "No active standup routines in this channel. Run `/standup setup` to create one."
    }

    @Transactional
    @EventListener
    fun handleStandupOps(event: StandupOpsRequestEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        val (text, detailType) =
            when (payload.action) {
                StandupOpsAction.LIST ->
                    renderRoutines(channel = basicInfo.channel) to CommandDetailType.STANDUP_ROUTINE_LIST

                StandupOpsAction.STOP ->
                    stopRoutine(payload = payload) to CommandDetailType.STANDUP_ROUTINE_STOP
            }

        val staged =
            checkNotNull(
                outboundStager.stage(
                    message =
                        OutboundMessage.Ephemeral(
                            target = ConversationTarget(id = basicInfo.channel),
                            recipient = UserRef(id = basicInfo.publisherId),
                            content = MessageContent.Text(headline = RESPONSE_HEADLINE, markdown = text),
                            detailType = detailType,
                        ),
                    basicInfo = basicInfo,
                ),
            ) { "Standup ops reply failed to stage an outbox event: action=${payload.action}" }
        eventPublisher.publishOne(event = staged)
    }

    private fun renderRoutines(channel: String): String {
        val routines = standupRepository.findActiveRoutinesByChannel(commandChannel = channel)
        if (routines.isEmpty()) return NO_ROUTINES_MESSAGE
        val lines = routines.joinToString(separator = "\n") { routine -> routineLine(routine = routine) }
        return "Active standup routines in <#$channel> (${routines.size}):\n$lines"
    }

    private fun routineLine(routine: RoutineDto): String {
        val weekdays =
            routine.weekdays
                .sortedBy { day -> day.value }
                .joinToString(separator = "/") { day -> day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
        return "• *${routine.name.escapeMrkdwn()}* — " +
            "${routine.triggerLocalTime.format(ROUTINE_TRIGGER_TIME_FORMAT)} ${routine.routineTimezone.id}, " +
            "$weekdays, cutoff +${routine.cutoffOffset.toMinutes()}m, ${routine.members.size} member(s), " +
            "summary → <#${routine.summaryChannel}>, created by <@${routine.creatorId}>"
    }

    private fun stopRoutine(payload: StandupOpsPayload): String {
        val basicInfo = payload.responseBasicInfo
        val routineName = checkNotNull(payload.routineName) { "STOP requires a routine name" }
        val routine =
            standupRepository
                .findActiveRoutinesByChannel(commandChannel = basicInfo.channel)
                .firstOrNull { candidate -> candidate.name.equals(routineName, ignoreCase = true) }
                ?: return "No active standup routine named `${routineName.escapeMrkdwn()}` in this channel. " +
                    "Run `/standup list` to see them."
        val escapedName = routine.name.escapeMrkdwn()
        val requesterId = basicInfo.publisherId
        if (requesterId != routine.creatorId && !isAdmin(userId = requesterId)) {
            return "Only the routine creator (<@${routine.creatorId}>) or an admin can stop `$escapedName`."
        }
        if (!standupRepository.deactivateRoutine(routineUid = routine.routineUid)) {
            return "`$escapedName` was already stopped."
        }
        opsLog.info { "Standup routine stopped: routineUid=${routine.routineUid} requesterId=$requesterId" }
        return "Stopped standup routine `$escapedName`. No new sessions will open; prompts and nudges still " +
            "pending for it are skipped, and a session that is already collecting will still be summarized " +
            "at its cutoff."
    }

    private fun isAdmin(userId: String): Boolean =
        commandRoleResolver.resolve(userId = userId).grants(permission = CommandPermission.ADMINISTRATION)
}
