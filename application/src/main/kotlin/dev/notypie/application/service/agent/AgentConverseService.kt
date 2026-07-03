package dev.notypie.application.service.agent

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.repository.agent.AgentSessionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

/**
 * Runs one AI-agent conversation turn for an [AgentConverseRequestEvent] and posts the outcome
 * back into the originating Slack thread.
 *
 * Class-level `@Async` for the same reasons as
 * [dev.notypie.impl.command.SlackEventAsyncDispatcher]: a turn is a slow external network call
 * (up to the sidecar's turn ceiling), so it must leave the mention's HTTP request thread — and its
 * `@Transactional` scope — immediately. Because the listener therefore runs with no active
 * transaction, the reply publish is wrapped in a [TransactionTemplate] so the outbox's
 * BEFORE_COMMIT listener has a transaction to bind to (the scheduling services' pattern), and the
 * session upsert commits atomically with the staged reply.
 *
 * Session continuity: the conversation anchor (`threadId`, falling back to the requester for
 * transports without message identity) keys both the sidecar workspace (`sessionKey`) and the
 * stored provider session id that must be echoed back on the next turn to resume context.
 */
@Async
class AgentConverseService(
    private val agentGateway: AgentGateway,
    private val agentSessionRepository: AgentSessionRepository,
    private val slackEventBuilder: SlackApiEventConstructor,
    private val eventPublisher: EventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    companion object {
        internal const val RESPONSE_HEADLINE = "CodeCompanion — AI assistant"
        internal const val BUSY_MESSAGE =
            "I'm still working on the previous request in this conversation — please wait for it to finish."
        internal const val FAILURE_MESSAGE = "Sorry — I couldn't process that request. Please try again later."
        internal const val EMPTY_RESPONSE_MESSAGE = "_(the assistant returned an empty response)_"
    }

    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    @EventListener
    fun handleAgentConverse(event: AgentConverseRequestEvent) {
        val payload = event.payload
        val basicInfo = payload.responseBasicInfo
        val sessionKey = "${basicInfo.channel}:${payload.threadId ?: basicInfo.publisherId}"

        val result =
            agentGateway.converse(
                request =
                    AgentTurnRequest(
                        sessionKey = sessionKey,
                        prompt = payload.prompt,
                        sessionId = agentSessionRepository.findProviderSessionId(sessionKey = sessionKey),
                        userId = basicInfo.publisherId,
                    ),
            )

        when (result) {
            is AgentTurnResult.Completed -> publishAnswer(event = event, sessionKey = sessionKey, result = result)

            is AgentTurnResult.Busy ->
                publishEphemeral(event = event, message = BUSY_MESSAGE)

            is AgentTurnResult.Failed -> {
                log.error {
                    "Agent turn failed sessionKey=$sessionKey code=${result.code} " +
                        "message=${result.message} idempotencyKey=${event.idempotencyKey}"
                }
                publishAnswerText(event = event, text = FAILURE_MESSAGE)
            }
        }
    }

    /** Commits the resumable session id and the staged reply atomically (outbox binds BEFORE_COMMIT). */
    private fun publishAnswer(event: AgentConverseRequestEvent, sessionKey: String, result: AgentTurnResult.Completed) {
        log.debug {
            "Agent turn completed sessionKey=$sessionKey inputTokens=${result.inputTokens} " +
                "outputTokens=${result.outputTokens} idempotencyKey=${event.idempotencyKey}"
        }
        transactionTemplate
            .runInTx {
                result.sessionId?.let {
                    agentSessionRepository.saveProviderSessionId(
                        sessionKey = sessionKey,
                        providerSessionId = it,
                    )
                }
                eventPublisher.publishOne(
                    event = answerEvent(event = event, text = result.finalText.ifBlank { EMPTY_RESPONSE_MESSAGE }),
                )
            }.onFailure { exception ->
                log.error(exception) {
                    "Failed to publish agent answer sessionKey=$sessionKey idempotencyKey=${event.idempotencyKey}"
                }
            }
    }

    private fun publishAnswerText(event: AgentConverseRequestEvent, text: String) {
        transactionTemplate
            .runInTx { eventPublisher.publishOne(event = answerEvent(event = event, text = text)) }
            .onFailure { exception ->
                log.error(exception) {
                    "Failed to publish agent reply idempotencyKey=${event.idempotencyKey}"
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

    private fun publishEphemeral(event: AgentConverseRequestEvent, message: String) {
        val basicInfo = event.payload.responseBasicInfo
        val ephemeral =
            slackEventBuilder.simpleEphemeralTextRequest(
                textMessage = message,
                commandBasicInfo = basicInfo,
                commandDetailType = CommandDetailType.AGENT_CONVERSE,
                targetUserId = basicInfo.publisherId,
            )
        transactionTemplate
            .runInTx { eventPublisher.publishOne(event = ephemeral) }
            .onFailure { exception ->
                log.error(exception) {
                    "Failed to publish agent busy notice idempotencyKey=${event.idempotencyKey}"
                }
            }
    }
}
