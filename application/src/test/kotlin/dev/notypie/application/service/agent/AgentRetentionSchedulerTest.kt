package dev.notypie.application.service.agent

import ch.qos.logback.classic.Level
import dev.notypie.application.configurations.createAgentRetentionAppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.service.relay.RETENTION_MAX_BATCHES_PER_TICK
import dev.notypie.logging.captureLogs
import dev.notypie.logging.messagesAt
import dev.notypie.repository.agent.AgentAskTurnRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException

private class RetentionLane(
    askTurnDeletes: List<Int>,
    turnHistoryDeletes: List<Int>,
    toolCallDeletes: List<Int>,
) {
    val askTurnRepository =
        mockk<AgentAskTurnRepository> {
            every { deleteFinishedOlderThan(olderThan = any(), limit = any()) } returnsMany askTurnDeletes
        }
    val turnHistoryRepository =
        mockk<AgentTurnHistoryRepository> {
            every { deleteOlderThan(olderThan = any(), limit = any()) } returnsMany turnHistoryDeletes
        }
    val toolCallRepository =
        mockk<McpToolCallHistoryRepository> {
            every { deleteOlderThan(olderThan = any(), limit = any()) } returnsMany toolCallDeletes
        }
    val scheduler =
        AgentRetentionScheduler(
            askTurnRepository = askTurnRepository,
            agentTurnHistoryRepository = turnHistoryRepository,
            mcpToolCallHistoryRepository = toolCallRepository,
            appConfig = createAgentRetentionAppConfig(askTurnDays = 14L, historyDays = 180L, batchSize = BATCH),
            clock = createFixedUtcClock(),
        )

    companion object {
        const val BATCH = 50
    }
}

class AgentRetentionSchedulerTest :
    BehaviorSpec({
        val loggerName = AgentRetentionScheduler::class.java.packageName
        val askTurnCutoff = DEFAULT_TEST_NOW.minusDays(14L)
        val historyCutoff = DEFAULT_TEST_NOW.minusDays(180L)

        given("a retention of 14 days for ask turns and 180 days for the history, in batches of 50") {
            `when`("each table has a different number of rows past its cutoff") {
                val lane =
                    RetentionLane(
                        askTurnDeletes = listOf(50, 50, 3),
                        turnHistoryDeletes = listOf(50, 7),
                        toolCallDeletes = listOf(0),
                    )
                val (purged, logs) = captureLogs(loggerName = loggerName) { lane.scheduler.purgeOnce() }

                then("each table is deleted in batches until one comes back short, against its own cutoff") {
                    purged shouldBe 160
                    verify(exactly = 3) {
                        lane.askTurnRepository.deleteFinishedOlderThan(olderThan = askTurnCutoff, limit = 50)
                    }
                    verify(exactly = 2) {
                        lane.turnHistoryRepository.deleteOlderThan(olderThan = historyCutoff, limit = 50)
                    }
                    verify(exactly = 1) {
                        lane.toolCallRepository.deleteOlderThan(olderThan = historyCutoff, limit = 50)
                    }
                }

                then("one INFO line per table that lost rows, none for the table that lost none") {
                    logs.messagesAt(level = Level.INFO) shouldBe
                        listOf(
                            "Agent retention purged 103 rows of agent_ask_turn older than $askTurnCutoff",
                            "Agent retention purged 57 rows of agent_turn_history older than $historyCutoff",
                        )
                    logs.messagesAt(level = Level.ERROR).shouldBeEmpty()
                }
            }

            `when`("every batch comes back full") {
                val full = List(size = 30) { RetentionLane.BATCH }
                val lane = RetentionLane(askTurnDeletes = full, turnHistoryDeletes = full, toolCallDeletes = full)
                val purged = lane.scheduler.purgeOnce()

                then("each table stops after the per-tick batch cap and the rest waits for the next tick") {
                    purged shouldBe 3 * RETENTION_MAX_BATCHES_PER_TICK * RetentionLane.BATCH
                    verify(exactly = RETENTION_MAX_BATCHES_PER_TICK) {
                        lane.askTurnRepository.deleteFinishedOlderThan(olderThan = any(), limit = any())
                    }
                    verify(exactly = RETENTION_MAX_BATCHES_PER_TICK) {
                        lane.turnHistoryRepository.deleteOlderThan(olderThan = any(), limit = any())
                    }
                    verify(exactly = RETENTION_MAX_BATCHES_PER_TICK) {
                        lane.toolCallRepository.deleteOlderThan(olderThan = any(), limit = any())
                    }
                }
            }

            `when`("the ask-turn table's second batch throws inside the scheduled tick") {
                val lane =
                    RetentionLane(
                        askTurnDeletes = listOf(50),
                        turnHistoryDeletes = listOf(4),
                        toolCallDeletes = listOf(2),
                    )
                every { lane.askTurnRepository.deleteFinishedOlderThan(olderThan = any(), limit = any()) } returns
                    50 andThenThrows DataAccessResourceFailureException("ledger down")
                val errors =
                    captureLogs(loggerName = loggerName) { lane.scheduler.purge() }.messagesAt(level = Level.ERROR)

                then("one ERROR names the table and the rows already deleted, and both history tables are purged") {
                    errors shouldBe listOf("Agent retention purge of agent_ask_turn failed after 50 rows")
                    verify(exactly = 1) {
                        lane.turnHistoryRepository.deleteOlderThan(olderThan = historyCutoff, limit = 50)
                    }
                    verify(exactly = 1) {
                        lane.toolCallRepository.deleteOlderThan(olderThan = historyCutoff, limit = 50)
                    }
                }
            }

            `when`("a table's delete throws an error") {
                val lane =
                    RetentionLane(
                        askTurnDeletes = listOf(0),
                        turnHistoryDeletes = listOf(0),
                        toolCallDeletes = listOf(0),
                    )
                every { lane.turnHistoryRepository.deleteOlderThan(olderThan = any(), limit = any()) } throws
                    LinkageError("broken class")

                then("the error is not swallowed") {
                    shouldThrow<LinkageError> { lane.scheduler.purge() }
                }
            }

            `when`("no row is past its cutoff") {
                val lane =
                    RetentionLane(
                        askTurnDeletes = listOf(0),
                        turnHistoryDeletes = listOf(0),
                        toolCallDeletes = listOf(0),
                    )
                val (purged, logs) = captureLogs(loggerName = loggerName) { lane.scheduler.purgeOnce() }

                then("one call per table and nothing is logged") {
                    purged shouldBe 0
                    logs.shouldBeEmpty()
                    verify(exactly = 1) {
                        lane.askTurnRepository.deleteFinishedOlderThan(olderThan = askTurnCutoff, limit = 50)
                    }
                    verify(exactly = 1) {
                        lane.turnHistoryRepository.deleteOlderThan(olderThan = historyCutoff, limit = 50)
                    }
                    verify(exactly = 1) {
                        lane.toolCallRepository.deleteOlderThan(olderThan = historyCutoff, limit = 50)
                    }
                }
            }
        }
    })
