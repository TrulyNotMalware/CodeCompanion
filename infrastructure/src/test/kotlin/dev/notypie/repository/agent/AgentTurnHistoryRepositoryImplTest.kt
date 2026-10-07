package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnHistorySchema
import dev.notypie.repository.agent.schema.AgentTurnOutcome
import dev.notypie.schema.createAgentTurnHistorySchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:agent_turn_history_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class AgentTurnHistoryRepositoryImplTest
    @Autowired
    constructor(
        private val jpaAgentTurnHistoryRepository: JpaAgentTurnHistoryRepository,
        private val entityManager: TestEntityManager,
        private val jdbcTemplate: JdbcTemplate,
    ) : BehaviorSpec({
            val repository =
                AgentTurnHistoryRepositoryImpl(jpaAgentTurnHistoryRepository = jpaAgentTurnHistoryRepository)
            val since = LocalDateTime.of(2026, 10, 1, 0, 0, 0)
            val inside = since.plusDays(2L)
            val outside = since.minusMinutes(1L)

            fun persistAt(row: AgentTurnHistorySchema, createdAt: LocalDateTime) {
                val id = entityManager.persistAndFlush(row).id
                jdbcTemplate.update("UPDATE agent_turn_history SET created_at = ? WHERE id = ?", createdAt, id)
            }

            fun persistTurns() {
                persistAt(
                    row =
                        createAgentTurnHistorySchema(
                            requesterId = "U_ALPHA",
                            inputTokens = 1_000L,
                            outputTokens = 100L,
                            durationMs = 2_000L,
                        ),
                    createdAt = inside,
                )
                persistAt(
                    row =
                        createAgentTurnHistorySchema(
                            requesterId = "U_ALPHA",
                            inputTokens = 500L,
                            outputTokens = 50L,
                            durationMs = 1_000L,
                        ),
                    createdAt = since,
                )
                persistAt(
                    row =
                        createAgentTurnHistorySchema(
                            requesterId = "U_ALPHA",
                            outcome = AgentTurnOutcome.FAILED,
                            errorCode = "Timeout",
                            inputTokens = null,
                            outputTokens = null,
                            durationMs = 300L,
                        ),
                    createdAt = inside,
                )
                persistAt(
                    row =
                        createAgentTurnHistorySchema(
                            requesterId = "U_BETA",
                            inputTokens = 200L,
                            outputTokens = 20L,
                            durationMs = 500L,
                        ),
                    createdAt = inside,
                )
                persistAt(
                    row =
                        createAgentTurnHistorySchema(
                            requesterId = "U_GAMMA",
                            outcome = AgentTurnOutcome.BUSY,
                            inputTokens = 9_999L,
                            outputTokens = 999L,
                            durationMs = 9_999L,
                        ),
                    createdAt = outside,
                )
                entityManager.clear()
            }

            given("turns inside and outside the window with two outcomes and two requesters") {
                `when`("the usage is grouped by outcome") {
                    then("only turns since the window start are summed, and a group with no tokens sums to zero") {
                        persistTurns()

                        repository.countByOutcomeSince(since = since) shouldContainExactlyInAnyOrder
                            listOf(
                                AgentTurnOutcomeUsage(
                                    outcome = AgentTurnOutcome.COMPLETED,
                                    turns = 3L,
                                    inputTokens = 1_700L,
                                    outputTokens = 170L,
                                    totalDurationMs = 3_500L,
                                ),
                                AgentTurnOutcomeUsage(
                                    outcome = AgentTurnOutcome.FAILED,
                                    turns = 1L,
                                    inputTokens = 0L,
                                    outputTokens = 0L,
                                    totalDurationMs = 300L,
                                ),
                            )
                    }
                }

                `when`("the top requesters are read") {
                    then("they are ordered by turn count and the out-of-window requester is absent") {
                        persistTurns()

                        repository.topRequestersSince(since = since, limit = 5) shouldContainExactly
                            listOf(
                                RequesterTurnUsage(
                                    requesterId = "U_ALPHA",
                                    turns = 3L,
                                    inputTokens = 1_500L,
                                    outputTokens = 150L,
                                ),
                                RequesterTurnUsage(
                                    requesterId = "U_BETA",
                                    turns = 1L,
                                    inputTokens = 200L,
                                    outputTokens = 20L,
                                ),
                            )
                    }

                    then("the limit keeps only the busiest requester") {
                        persistTurns()

                        repository.topRequestersSince(since = since, limit = 1) shouldContainExactly
                            listOf(
                                RequesterTurnUsage(
                                    requesterId = "U_ALPHA",
                                    turns = 3L,
                                    inputTokens = 1_500L,
                                    outputTokens = 150L,
                                ),
                            )
                    }
                }

                `when`("the window starts after every turn") {
                    then("both aggregates are empty") {
                        persistTurns()

                        repository.countByOutcomeSince(since = inside.plusDays(1L)).shouldBeEmpty()
                        repository.topRequestersSince(since = inside.plusDays(1L), limit = 5).shouldBeEmpty()
                    }
                }
            }
        })
