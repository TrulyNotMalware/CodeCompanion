package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.toChainHead
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackBlockLimits
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private val summaryLog = KotlinLogging.logger {}

internal const val SUMMARY_MEMBER_RESPONSE_CHARS = SlackBlockLimits.SECTION_TEXT_BUDGET

// Each costs 14 bytes in the summary chain's CDC update record; stripped, a full routine fits within 1 MiB.
private val CONTROL_CHARACTERS = Regex("[\\p{Cc}&&[^\\n\\t]]")

internal fun List<StandupAnswerDto>.boundedForSummary(): List<StandupAnswerDto> =
    map { answer ->
        if (answer.responses.isEmpty()) {
            answer
        } else {
            val perResponse = SUMMARY_MEMBER_RESPONSE_CHARS / answer.responses.size
            answer.copy(
                responses =
                    answer.responses.map { raw ->
                        val response = raw.replace(regex = CONTROL_CHARACTERS, replacement = "")
                        if (response.length <= perResponse) response else "${response.take(perResponse - 1)}…"
                    },
            )
        }
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
                val parts =
                    ModalTemplateBuilder.standupSummaryParts(
                        routineName = routine.name,
                        sessionDate = session.sessionDate,
                        members = routine.members,
                        answers = session.answers.boundedForSummary(),
                        questions = routine.questions,
                    )
                val commandBasicInfo =
                    CommandBasicInfo.forOutbound(publisherId = routine.creatorId, channel = routine.summaryChannel)
                val summaryRow =
                    outboundMessagePort.toChainHead(
                        messages =
                            parts.map { part ->
                                OutboundMessage.ChannelMessage(
                                    target = ConversationTarget(id = commandBasicInfo.channel),
                                    content = part,
                                )
                            },
                        basicInfo = commandBasicInfo,
                    )
                outboxRepository.save(summaryRow)
                if (!standupRepository.markSessionSummarized(
                        sessionId = session.sessionId,
                        messageTs = "outbox:${summaryRow.eventId}",
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
