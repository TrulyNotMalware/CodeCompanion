package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackBlockLimits
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

private val summaryLog = KotlinLogging.logger {}

internal const val SUMMARY_MEMBER_RESPONSE_CHARS = SlackBlockLimits.SECTION_TEXT_BUDGET

internal fun List<StandupAnswerDto>.boundedForSummary(): List<StandupAnswerDto> =
    map { answer ->
        if (answer.responses.isEmpty()) {
            answer
        } else {
            val perResponse = SUMMARY_MEMBER_RESPONSE_CHARS / answer.responses.size
            answer.copy(
                responses =
                    answer.responses.map { response ->
                        if (response.length <= perResponse) response else "${response.take(perResponse - 1)}…"
                    },
            )
        }
    }

internal fun summaryPages(
    routineName: String,
    sessionDate: LocalDate,
    members: List<RoutineMemberDto>,
    answersByUser: Map<String, StandupAnswerDto>,
    questions: List<String>,
): List<List<RoutineMemberDto>> {
    val header =
        ModalTemplateBuilder.standupSummaryHeader(routineName = "$routineName (99/99)", sessionDate = sessionDate)
    val budget = SlackBlockLimits.MESSAGE_TEXT_BUDGET - header.length
    val pages = mutableListOf<MutableList<RoutineMemberDto>>()
    var used = 0
    members.forEach { member ->
        val length =
            ModalTemplateBuilder
                .standupSummaryMemberSection(
                    userId = member.userId,
                    answer = answersByUser[member.userId],
                    questions = questions,
                ).length
        val current = pages.lastOrNull()
        if (current == null ||
            used + length > budget ||
            current.size >= ModalTemplateBuilder.STANDUP_SUMMARY_MAX_MEMBER_SECTIONS
        ) {
            pages += mutableListOf(member)
            used = length
        } else {
            current += member
            used += length
        }
    }
    return pages.ifEmpty { listOf(emptyList()) }
}

@Service
class StandupSummaryService(
    private val standupRepository: StandupRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @EventListener
    fun postSummary(event: StandupCutoffEvent) {
        transactionTemplate
            .runInTx {
                val session =
                    standupRepository.findSessionForSummary(sessionUid = event.sessionUid)
                        ?: run {
                            summaryLog.warn {
                                "Standup summary skipped; session not found: sessionUid=${event.sessionUid}"
                            }
                            return@runInTx false
                        }
                if (session.status != SessionStatus.COLLECTING) {
                    summaryLog.info {
                        "Standup summary skipped; session is ${session.status}: sessionUid=${event.sessionUid}"
                    }
                    return@runInTx false
                }
                val routine = standupRepository.getRoutine(routineUid = event.routineUid)
                val answersByUser = session.answers.boundedForSummary().associateBy { it.userId }
                val pages =
                    summaryPages(
                        routineName = routine.name,
                        sessionDate = session.sessionDate,
                        members = routine.members,
                        answersByUser = answersByUser,
                        questions = routine.questions,
                    )
                val summaryRows =
                    pages.mapIndexed { index, pageMembers ->
                        val commandBasicInfo =
                            CommandBasicInfo.forOutbound(
                                publisherId = routine.creatorId,
                                channel = routine.summaryChannel,
                            )
                        outboundMessagePort.toRow(
                            message =
                                OutboundMessage.ChannelMessage(
                                    target = ConversationTarget(id = commandBasicInfo.channel),
                                    content =
                                        MessageContent.StandupSummary(
                                            routineName =
                                                if (pages.size == 1) {
                                                    routine.name
                                                } else {
                                                    "${routine.name} (${index + 1}/${pages.size})"
                                                },
                                            sessionDate = session.sessionDate,
                                            members = pageMembers,
                                            answers = pageMembers.mapNotNull { answersByUser[it.userId] },
                                            questions = routine.questions,
                                        ),
                                ),
                            basicInfo = commandBasicInfo,
                        )
                    }
                summaryRows.forEach { row -> outboxRepository.save(row) }
                if (!standupRepository.markSessionSummarized(
                        sessionId = session.sessionId,
                        messageTs = "outbox:${summaryRows.first().eventId}",
                    )
                ) {
                    error("Session was already summarized: sessionUid=${session.sessionUid}")
                }
                true
            }.onSuccess { enqueued ->
                if (enqueued) summaryLog.info { "Standup summary enqueued: sessionUid=${event.sessionUid}" }
            }.onFailure { ex ->
                summaryLog.warn(ex) { "Standup summary enqueue rolled back: sessionUid=${event.sessionUid}" }
            }
    }

    @EventListener
    fun replaceSummaryMarkerWithSlackTs(event: MessagePublishSuccessEvent) {
        if (event.messageTs.isBlank()) return
        val marker = "outbox:${event.eventId}"
        if (standupRepository.replaceSummaryMessageTs(currentMessageTs = marker, messageTs = event.messageTs)) {
            summaryLog.info { "Standup summary Slack ts recorded: eventId=${event.eventId}" }
        }
    }
}
