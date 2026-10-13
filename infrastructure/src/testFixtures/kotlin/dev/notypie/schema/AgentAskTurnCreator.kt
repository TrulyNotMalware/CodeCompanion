package dev.notypie.schema

import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.repository.agent.schema.AgentAskTurnSchema
import dev.notypie.repository.agent.schema.AgentAskTurnStatus
import java.time.LocalDateTime
import java.util.UUID

fun createAgentAskTurnSchema(
    eventId: String = UUID.randomUUID().toString(),
    messageTs: String = TEST_MESSAGE_TS,
    status: AgentAskTurnStatus = AgentAskTurnStatus.PENDING,
    attemptCount: Int = 0,
    runCount: Int = 0,
    createdAt: LocalDateTime = LocalDateTime.of(2026, 10, 12, 9, 0, 0),
    updatedAt: LocalDateTime = createdAt,
): AgentAskTurnSchema =
    AgentAskTurnSchema(
        eventId = eventId,
        messageTs = messageTs,
        status = status,
        attemptCount = attemptCount,
        runCount = runCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
