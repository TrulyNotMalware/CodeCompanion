package dev.notypie.repository.mcp

import dev.notypie.repository.mcp.schema.McpToolCallHistorySchema
import dev.notypie.repository.mcp.schema.McpToolCallOutcome
import dev.notypie.schema.createMcpToolCallHistorySchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:mcp_tool_call_history_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class McpToolCallHistoryRepositoryImplTest
    @Autowired
    constructor(
        private val jpaMcpToolCallHistoryRepository: JpaMcpToolCallHistoryRepository,
        private val entityManager: TestEntityManager,
        private val jdbcTemplate: JdbcTemplate,
    ) : BehaviorSpec({
            val repository =
                McpToolCallHistoryRepositoryImpl(jpaMcpToolCallHistoryRepository = jpaMcpToolCallHistoryRepository)
            val since = LocalDateTime.of(2026, 10, 1, 0, 0, 0)
            val inside = since.plusDays(2L)
            val outside = since.minusMinutes(1L)

            fun persistAt(row: McpToolCallHistorySchema, createdAt: LocalDateTime) {
                val id = entityManager.persistAndFlush(row).id
                jdbcTemplate.update("UPDATE mcp_tool_call_history SET created_at = ? WHERE id = ?", createdAt, id)
            }

            fun persistCalls() {
                persistAt(row = createMcpToolCallHistorySchema(toolName = "list_meetings"), createdAt = inside)
                persistAt(row = createMcpToolCallHistorySchema(toolName = "list_meetings"), createdAt = since)
                persistAt(row = createMcpToolCallHistorySchema(toolName = "get_status"), createdAt = inside)
                persistAt(
                    row =
                        createMcpToolCallHistorySchema(
                            toolName = "get_status",
                            outcome = McpToolCallOutcome.DENIED,
                        ),
                    createdAt = inside,
                )
                persistAt(
                    row =
                        createMcpToolCallHistorySchema(
                            toolName = "get_status",
                            outcome = McpToolCallOutcome.FAILED,
                        ),
                    createdAt = outside,
                )
                entityManager.clear()
            }

            given("tool calls inside and outside the window") {
                `when`("the calls are grouped by tool and outcome") {
                    then("each pair is counted once, ordered by tool then outcome, without the old call") {
                        persistCalls()

                        repository.countByToolSince(since = since) shouldContainExactly
                            listOf(
                                ToolCallUsage(
                                    toolName = "get_status",
                                    outcome = McpToolCallOutcome.COMPLETED,
                                    calls = 1L,
                                ),
                                ToolCallUsage(toolName = "get_status", outcome = McpToolCallOutcome.DENIED, calls = 1L),
                                ToolCallUsage(
                                    toolName = "list_meetings",
                                    outcome = McpToolCallOutcome.COMPLETED,
                                    calls = 2L,
                                ),
                            )
                    }
                }

                `when`("the window starts after every call") {
                    then("the aggregate is empty") {
                        persistCalls()

                        repository.countByToolSince(since = inside.plusDays(1L)).shouldBeEmpty()
                    }
                }
            }
        })
