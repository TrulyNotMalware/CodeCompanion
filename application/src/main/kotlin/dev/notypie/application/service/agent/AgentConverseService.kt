package dev.notypie.application.service.agent

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConversePayload
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.impl.command.SlackApiEventConstructor
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

/**
 * Runs one AI-agent conversation turn for an [AgentConverseRequestEvent] and posts the outcome
 * back into the originating Slack thread.
 *
 * Class-level `@Async` for the same reasons as
 * [dev.notypie.impl.command.SlackEventAsyncDispatcher]: a turn is a slow external network call
 * (up to the sidecar's turn ceiling), so it must leave the mention's HTTP request thread — and its
 * `@Transactional` scope — immediately. Because the listener therefore runs with no active
 * transaction, each outcome's writes are wrapped in a [TransactionTemplate] so the outbox's
 * BEFORE_COMMIT listener has a transaction to bind to (the scheduling services' pattern); the
 * session upsert, the audit row, and the staged reply commit atomically.
 *
 * Session continuity: the conversation anchor (`threadId`, falling back to the requester for
 * transports without message identity) keys both the sidecar workspace (`sessionKey`) and the
 * stored provider session id that must be echoed back on the next turn to resume context.
 *
 * Every turn also feeds [AgentTurnHistoryRepository] (per-user/channel token audit — the Pod
 * shares one Anthropic identity) and Micrometer counters/timers under `agent.*`.
 */
@Async
class AgentConverseService(
    private val agentGateway: AgentGateway,
    private val agentSessionRepository: AgentSessionRepository,
    private val agentTurnHistoryRepository: AgentTurnHistoryRepository,
    private val slackEventBuilder: SlackApiEventConstructor,
    private val eventPublisher: EventPublisher,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
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

    /**
     * Per-request context appended to the sidecar's static base prompt: who is asking where and
     * when (so relative dates resolve), plus the Slack mrkdwn output contract — the model defaults
     * to GitHub Markdown, which renders broken inside Slack section blocks. Facts only: identity
     * for authorization purposes travels as `X-User-Id`, never as prompt text.
     */
    private fun contextPrompt(payload: AgentConversePayload): String {
        val now = clock.instant().atZone(clock.zone)
        return buildString {
            appendLine("## Conversation context")
            appendLine("- Requester: <@${payload.responseBasicInfo.publisherId}> (${payload.requesterName})")
            appendLine("- Channel: #${payload.channelName}")
            appendLine("- Current time: ${CONTEXT_TIME_FORMAT.format(now)}")
            appendLine()
            appendLine("## Response format")
            append("Write replies as Slack mrkdwn: *bold*, _italic_, `code`, and hyphen bullet lists. ")
            append("Never use Markdown headings (#) or double-asterisk bold. ")
            append("Keep replies concise — this is a chat thread.")
        }
    }

    /** Commits the resumable session id, the audit row, and the staged reply atomically. */
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
        val ephemeral =
            slackEventBuilder.simpleEphemeralTextRequest(
                textMessage = BUSY_MESSAGE,
                commandBasicInfo = basicInfo,
                commandDetailType = CommandDetailType.AGENT_CONVERSE,
                targetUserId = basicInfo.publisherId,
            )
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
                eventPublisher.publishOne(event = ephemeral)
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

    private fun answerEvent(event: AgentConverseRequestEvent, text: String) =
        slackEventBuilder.simpleTextRequest(
            commandDetailType = CommandDetailType.AGENT_CONVERSE,
            headLineText = RESPONSE_HEADLINE,
            commandBasicInfo = event.payload.responseBasicInfo,
            simpleString = text,
            threadTs = event.payload.threadId,
        )
}
