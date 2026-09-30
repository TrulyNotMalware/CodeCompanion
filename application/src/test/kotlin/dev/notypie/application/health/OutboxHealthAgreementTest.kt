package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.MessageStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.health.contributor.Status
import java.time.LocalDateTime

// health/AGENTS.md: "the chat reply and the health endpoint must never disagree". The repository here answers every
// query from one row list, so a reader that uses a different cutoff or ignores a counter reaches a different verdict.
class OutboxHealthAgreementTest :
    BehaviorSpec({
        data class Row(
            val status: MessageStatus,
            val createdAt: LocalDateTime = DEFAULT_TEST_NOW,
            val updatedAt: LocalDateTime = DEFAULT_TEST_NOW,
            val sendCount: Int = 0,
        )

        fun repositoryOver(rows: List<Row>): MessageOutboxRepository {
            val pending = rows.filter { it.status == MessageStatus.PENDING }
            val inFlight = rows.filter { it.status == MessageStatus.IN_PROGRESS }
            return mockk {
                every { countPending() } returns pending.size.toLong()
                every { countPendingOlderThan(threshold = any()) } answers
                    { pending.count { it.createdAt < firstArg<LocalDateTime>() }.toLong() }
                every { findOldestPendingCreatedAt() } returns pending.minOfOrNull { it.createdAt }
                every { countInProgress() } returns inFlight.size.toLong()
                every { countInProgressOlderThan(threshold = any()) } answers
                    { inFlight.count { it.updatedAt < firstArg<LocalDateTime>() }.toLong() }
                every { findOldestInProgressUpdatedAt() } returns inFlight.minOfOrNull { it.updatedAt }
                every { countInProgressWithSendsAtLeast(sends = any()) } answers
                    { inFlight.count { it.sendCount >= firstArg<Int>() }.toLong() }
            }
        }

        fun verdicts(rows: List<Row>): Pair<Boolean, Boolean> {
            val repository = repositoryOver(rows = rows)
            val clock = createFixedUtcClock()
            val appConfig = AppConfig()
            val actuatorUp =
                OutboxHealthIndicator(outboxRepository = repository, clock = clock, appConfig = appConfig)
                    .health()
                    .status == Status.UP
            val chatUp =
                OpsStatusService(
                    outboxRepository = repository,
                    outboundStager = mockk(),
                    eventPublisher = mockk(),
                    cveTopicRepository = mockk(),
                    cveEventRepository = mockk(),
                    cveCollectLedgerRepository = mockk(),
                    clock = clock,
                    appConfig = appConfig,
                ).renderReport().contains("UP")
            return actuatorUp to chatUp
        }

        given("outbox states on either side of every DOWN rule") {
            val states =
                mapOf(
                    "empty" to emptyList(),
                    "a deferred row 330 s old, inside the sweep's grace period" to
                        listOf(
                            Row(status = MessageStatus.IN_PROGRESS, updatedAt = DEFAULT_TEST_NOW.minusSeconds(330L)),
                        ),
                    "an in-flight row the sweep failed to take for a full period" to
                        listOf(
                            Row(status = MessageStatus.IN_PROGRESS, updatedAt = DEFAULT_TEST_NOW.minusSeconds(400L)),
                        ),
                    "a just-reclaimed row that has already been sent three times" to
                        listOf(Row(status = MessageStatus.IN_PROGRESS, sendCount = 3)),
                    "a PENDING row older than the stuck threshold" to
                        listOf(Row(status = MessageStatus.PENDING, createdAt = DEFAULT_TEST_NOW.minusSeconds(400L))),
                    "a fresh PENDING row" to listOf(Row(status = MessageStatus.PENDING)),
                )

            states.forEach { (name, rows) ->
                `when`("the outbox holds $name") {
                    val (actuatorUp, chatUp) = verdicts(rows = rows)

                    then("@bot status and the actuator indicator reach the same verdict") {
                        chatUp shouldBe actuatorUp
                    }
                }
            }
        }
    })
