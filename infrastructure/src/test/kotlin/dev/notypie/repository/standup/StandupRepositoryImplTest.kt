package dev.notypie.repository.standup

import dev.notypie.domain.standup.createStandupSession
import dev.notypie.repository.standup.schema.toSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Instant

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class StandupRepositoryImplTest
    @Autowired
    constructor(
        private val jpaRoutineRepository: JpaRoutineRepository,
        private val jpaStandupSessionRepository: JpaStandupSessionRepository,
        private val jpaSessionDispatchRepository: JpaSessionDispatchRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository =
                StandupRepositoryImpl(
                    jpaRoutineRepository = jpaRoutineRepository,
                    jpaStandupSessionRepository = jpaStandupSessionRepository,
                    jpaSessionDispatchRepository = jpaSessionDispatchRepository,
                )

            given("a member who answers a collecting session twice") {
                `when`("the second answer is recorded") {
                    then("it replaces the first, so the session keeps one answer per member") {
                        val session = createStandupSession()
                        jpaStandupSessionRepository.save(session.toSchema())
                        entityManager.flush()
                        entityManager.clear()

                        repository.recordAnswer(
                            sessionUid = session.sessionUid,
                            userId = "U_ANSWER",
                            responses = listOf("first"),
                            submittedAt = Instant.parse("2026-05-01T01:00:00Z"),
                        ) shouldBe true
                        entityManager.flush()
                        entityManager.clear()
                        repository.recordAnswer(
                            sessionUid = session.sessionUid,
                            userId = "U_ANSWER",
                            responses = listOf("second", "edited"),
                            submittedAt = Instant.parse("2026-05-01T01:05:00Z"),
                        ) shouldBe true
                        entityManager.flush()
                        entityManager.clear()

                        val answers = repository.findSession(sessionUid = session.sessionUid)?.answers.orEmpty()
                        answers.map { it.userId } shouldContainExactly listOf("U_ANSWER")
                        answers.single().responses shouldContainExactly listOf("second", "edited")
                    }
                }
            }
        })
