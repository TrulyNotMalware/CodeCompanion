package dev.notypie.application.service.agent

import dev.notypie.application.common.runInTx
import dev.notypie.application.security.mcp.ScopedTurnTokenCodec
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConversePayload
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.repository.agent.AgentSessionRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import dev.notypie.repository.agent.AgentTurnRecord
import dev.notypie.repository.agent.schema.AgentTurnOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

private val log = KotlinLogging.logger {}

@Async
class AgentConverseService(
    private val agentGateway: AgentGateway,
    private val agentSessionRepository: AgentSessionRepository,
    private val agentTurnHistoryRepository: AgentTurnHistoryRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val scopedTurnTokenCodec: ScopedTurnTokenCodec? = null,
) {
    companion object {
        internal const val RESPONSE_HEADLINE = "CodeCompanion — AI assistant"
        internal const val BUSY_MESSAGE =
            "I'm still working on the previous request in this conversation — please wait for it to finish."
        internal const val FAILURE_MESSAGE = "Sorry — I couldn't process that request. Please try again later."
        internal const val EMPTY_RESPONSE_MESSAGE = "_(the assistant returned an empty response)_"

        internal const val METRIC_TURNS = "agent.turns"
        internal const val METRIC_TOKENS = "agent.tokens"
        internal const val METRIC_TURN_DURATION = "agent.turn.duration"

        private val CONTEXT_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE) HH:mm zzz", Locale.ENGLISH)
    }

    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    @EventListener
    fun handleAgentConverse(event: AgentConverseRequestEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        val sessionKey = "${basicInfo.channel}:${payload.threadId ?: basicInfo.publisherId}"

        val startedAtNanos = System.nanoTime()
        val result =
            agentGateway.converse(
                request =
                    AgentTurnRequest(
                        sessionKey = sessionKey,
                        prompt = payload.prompt,
                        sessionId = agentSessionRepository.findProviderSessionId(sessionKey = sessionKey),
                        userId = basicInfo.publisherId,
                        appendSystemPrompt = contextPrompt(payload = payload),
                        scopedToken =
                            scopedTurnTokenCodec?.mint(
                                userId = basicInfo.publisherId,
                                sessionKey = sessionKey,
                                turnId = event.idempotencyKey.toString(),
                            ),
                    ),
            )
        val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

        when (result) {
            is AgentTurnResult.Completed ->
                publishAnswer(event = event, sessionKey = sessionKey, result = result, durationMs = durationMs)

            is AgentTurnResult.Busy ->
                publishBusy(event = event, sessionKey = sessionKey, durationMs = durationMs)

            is AgentTurnResult.Failed -> {
                log.error {
                    "Agent turn failed sessionKey=$sessionKey code=${result.code} " +
                        "message=${result.message} idempotencyKey=${event.idempotencyKey}"
                }
                publishFailure(event = event, sessionKey = sessionKey, result = result, durationMs = durationMs)
            }
        }
        recordMetrics(result = result, durationMs = durationMs)
    }

    private fun contextPrompt(payload: AgentConversePayload): String {
        val now = clock.instant().atZone(clock.zone)
        return buildString {
            appendLine("## Conversation context")
            appendLine("- Requester: ${requesterLine(payload = payload)}")
            appendLine("- Channel: ${channelLine(payload = payload)}")
            appendLine("- Current time: ${CONTEXT_TIME_FORMAT.format(now)}")
            appendLine()
            appendLine("## Response format")
            append("Write replies as Slack mrkdwn: *bold*, _italic_, `code`, and hyphen bullet lists. ")
            append("Never use Markdown headings (#) or double-asterisk bold. ")
            append("Keep replies concise — this is a chat thread.")
        }
    }

    // app_mention events carry no display names, so a blank name degrades to the bare Slack mention.
    private fun requesterLine(payload: AgentConversePayload): String {
        val mention = "<@${payload.responseBasicInfo.publisherId}>"
        return if (payload.requesterName.isBlank()) mention else "$mention (${payload.requesterName})"
    }

    private fun channelLine(payload: AgentConversePayload): String =
        if (payload.channelName.isBlank()) "<#${payload.responseBasicInfo.channel}>" else "#${payload.channelName}"

    private fun publishAnswer(
        event: AgentConverseRequestEvent,
        sessionKey: String,
        result: AgentTurnResult.Completed,
        durationMs: Long,
    ) {
        log.debug {
            "Agent turn completed sessionKey=$sessionKey inputTokens=${result.inputTokens} " +
                "outputTokens=${result.outputTokens} durationMs=$durationMs idempotencyKey=${event.idempotencyKey}"
        }
        transactionTemplate
            .runInTx {
                result.sessionId?.let {
                    agentSessionRepository.saveProviderSessionId(
                        sessionKey = sessionKey,
                        providerSessionId = it,
                    )
                }
                agentTurnHistoryRepository.record(
                    turn =
                        turnRecord(
                            event = event,
                            sessionKey = sessionKey,
                            outcome = AgentTurnOutcome.COMPLETED,
                            durationMs = durationMs,
                            inputTokens = result.inputTokens,
                            outputTokens = result.outputTokens,
                        ),
                )
                eventPublisher.publishOne(
                    event = answerEvent(event = event, text = result.finalText.ifBlank { EMPTY_RESPONSE_MESSAGE }),
                )
            }.onFailure { exception ->
                log.error(exception) {
                    "Failed to publish agent answer sessionKey=$sessionKey idempotencyKey=${event.idempotencyKey}"
                }
            }
    }

    private fun publishBusy(event: AgentConverseRequestEvent, sessionKey: String, durationMs: Long) {
        val basicInfo = event.payload.responseBasicInfo
        transactionTemplate
            .runInTx {
                agentTurnHistoryRepository.record(
                    turn =
                        turnRecord(
                            event = event,
                            sessionKey = sessionKey,
                            outcome = AgentTurnOutcome.BUSY,
                            durationMs = durationMs,
                        ),
                )
                eventPublisher.publishOne(
                    event =
                        stageReply(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = ConversationTarget(id = basicInfo.channel),
                                    recipient = UserRef(id = basicInfo.publisherId),
                                    content = MessageContent.Text(headline = null, markdown = BUSY_MESSAGE),
                                    detailType = CommandDetailType.AGENT_CONVERSE,
                                ),
                            basicInfo = basicInfo,
                        ),
                )
            }.onFailure { exception ->
                log.error(exception) {
                    "Failed to publish agent busy notice idempotencyKey=${event.idempotencyKey}"
                }
            }
    }

    private fun publishFailure(
        event: AgentConverseRequestEvent,
        sessionKey: String,
        result: AgentTurnResult.Failed,
        durationMs: Long,
    ) {
        transactionTemplate
            .runInTx {
                agentTurnHistoryRepository.record(
                    turn =
                        turnRecord(
                            event = event,
                            sessionKey = sessionKey,
                            outcome = AgentTurnOutcome.FAILED,
                            durationMs = durationMs,
                            errorCode = result.code,
                        ),
                )
                eventPublisher.publishOne(event = answerEvent(event = event, text = FAILURE_MESSAGE))
            }.onFailure { exception ->
                log.error(exception) {
                    "Failed to publish agent failure notice idempotencyKey=${event.idempotencyKey}"
                }
            }
    }

    private fun turnRecord(
        event: AgentConverseRequestEvent,
        sessionKey: String,
        outcome: AgentTurnOutcome,
        durationMs: Long,
        errorCode: String? = null,
        inputTokens: Long? = null,
        outputTokens: Long? = null,
    ): AgentTurnRecord {
        val basicInfo = event.payload.responseBasicInfo
        return AgentTurnRecord(
            sessionKey = sessionKey,
            requesterId = basicInfo.publisherId,
            channel = basicInfo.channel,
            idempotencyKey = event.idempotencyKey,
            outcome = outcome,
            errorCode = errorCode,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            durationMs = durationMs,
        )
    }

    private fun recordMetrics(result: AgentTurnResult, durationMs: Long) {
        val outcome =
            when (result) {
                is AgentTurnResult.Completed -> "completed"
                is AgentTurnResult.Busy -> "busy"
                is AgentTurnResult.Failed -> "failed"
            }
        meterRegistry.counter(METRIC_TURNS, "outcome", outcome).increment()
        meterRegistry.timer(METRIC_TURN_DURATION, "outcome", outcome).record(Duration.ofMillis(durationMs))
        if (result is AgentTurnResult.Completed) {
            result.inputTokens?.let {
                meterRegistry.counter(METRIC_TOKENS, "direction", "input").increment(it.toDouble())
            }
            result.outputTokens?.let {
                meterRegistry.counter(METRIC_TOKENS, "direction", "output").increment(it.toDouble())
            }
        }
    }

    private fun answerEvent(event: AgentConverseRequestEvent, text: String): CommandEvent<EventPayload> =
        stageReply(
            message =
                OutboundMessage.ChannelMessage(
                    target = ConversationTarget(id = event.payload.responseBasicInfo.channel),
                    content = MessageContent.Text(headline = RESPONSE_HEADLINE, markdown = text),
                    detailType = CommandDetailType.AGENT_CONVERSE,
                    threadId = event.payload.threadId,
                ),
            basicInfo = event.payload.responseBasicInfo,
        )

    // A null here means the reply would be silently lost, so we fail fast instead of swallowing it.
    private fun stageReply(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload> =
        checkNotNull(outboundStager.stage(message = message, basicInfo = basicInfo)) {
            "Agent reply failed to stage an outbox event: $message"
        }
}
