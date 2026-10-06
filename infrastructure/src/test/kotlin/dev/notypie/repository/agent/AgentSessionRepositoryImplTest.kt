package dev.notypie.repository.agent

import dev.notypie.repository.SnapshotIsolationTransactionManager
import dev.notypie.repository.agent.schema.AgentSessionSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:agent_session_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class AgentSessionRepositoryImplTest
    @Autowired
    constructor(
        private val jpaAgentSessionRepository: JpaAgentSessionRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository = AgentSessionRepositoryImpl(jpaAgentSessionRepository = jpaAgentSessionRepository)
            val firstAt = LocalDateTime.of(2026, 7, 3, 12, 0, 0)
            val secondAt = LocalDateTime.of(2026, 7, 3, 12, 5, 0)

            given("a thread whose provider session is saved and then replaced") {
                `when`("the provider hands back a new session id for the same thread") {
                    then("the stored id is replaced in place, one row per thread, keeping its creation time") {
                        repository.saveProviderSessionId(
                            sessionKey = "thread-1",
                            providerSessionId = "provider-a",
                            now = firstAt,
                        )
                        repository.saveProviderSessionId(
                            sessionKey = "thread-1",
                            providerSessionId = "provider-b",
                            now = secondAt,
                        )
                        entityManager.flush()
                        entityManager.clear()

                        repository.findProviderSessionId(sessionKey = "thread-1") shouldBe "provider-b"
                        val rows = jpaAgentSessionRepository.findAll().filter { it.sessionKey == "thread-1" }
                        rows.size shouldBe 1
                        rows.single().createdAt shouldBe firstAt
                        rows.single().updatedAt shouldBe secondAt
                    }
                }
            }

            given("a turn that stores its provider session while another turn of the thread just did") {
                val isolation = SnapshotIsolationTransactionManager()
                val sessions = mockk<JpaAgentSessionRepository>()
                val isolated = AgentSessionRepositoryImpl(jpaAgentSessionRepository = sessions)
                val existing = AgentSessionSchema(id = 4L, sessionKey = "thread-2", providerSessionId = "provider-a")
                every { sessions.findBySessionKey(sessionKey = "thread-2") } answers {
                    isolation.consistentRead()
                    existing
                }
                every { sessions.save(any<AgentSessionSchema>()) } answers {
                    isolation.lockingAccessToRowChangedConcurrently(table = "agent_session")
                    firstArg()
                }
                every {
                    sessions.upsertProviderSessionId(
                        sessionKey = "thread-2",
                        providerSessionId = "provider-c",
                        now = secondAt,
                    )
                } answers {
                    isolation.lockingAccessToRowChangedConcurrently(table = "agent_session")
                    2
                }

                `when`("the save runs first in the answer transaction under MariaDB snapshot isolation") {
                    TransactionTemplate(isolation).executeWithoutResult {
                        isolated.saveProviderSessionId(
                            sessionKey = "thread-2",
                            providerSessionId = "provider-c",
                            now = secondAt,
                        )
                    }

                    then("one upsert writes it without reading first, so the answer's outbox rows still commit") {
                        verify(exactly = 1) {
                            sessions.upsertProviderSessionId(
                                sessionKey = "thread-2",
                                providerSessionId = "provider-c",
                                now = secondAt,
                            )
                        }
                        verify(exactly = 0) { sessions.findBySessionKey(sessionKey = any()) }
                    }
                }
            }
        })
