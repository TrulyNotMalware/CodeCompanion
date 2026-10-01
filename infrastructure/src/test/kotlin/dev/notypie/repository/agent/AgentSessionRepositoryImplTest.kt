package dev.notypie.repository.agent

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class AgentSessionRepositoryImplTest
    @Autowired
    constructor(
        private val jpaAgentSessionRepository: JpaAgentSessionRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository = AgentSessionRepositoryImpl(jpaAgentSessionRepository = jpaAgentSessionRepository)

            given("a thread whose provider session is saved and then replaced") {
                `when`("the provider hands back a new session id for the same thread") {
                    then("the stored id is replaced in place, one row per thread") {
                        repository.saveProviderSessionId(sessionKey = "thread-1", providerSessionId = "provider-a")
                        repository.saveProviderSessionId(sessionKey = "thread-1", providerSessionId = "provider-b")
                        entityManager.flush()
                        entityManager.clear()

                        repository.findProviderSessionId(sessionKey = "thread-1") shouldBe "provider-b"
                        jpaAgentSessionRepository.findAll().count { it.sessionKey == "thread-1" } shouldBe 1
                    }
                }
            }
        })
