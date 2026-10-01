package dev.notypie.repository.standup

import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupAnswer
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.repository.standup.schema.toSchema
import dev.notypie.repository.standup.schema.toStandupSessionDto
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaStandupSessionRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaStandupSessionRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            given("a session with three dispatches and two answers") {
                `when`("it is loaded by sessionUid with both collections fetched") {
                    then("each dispatch and each answer appears exactly once") {
                        val session =
                            createStandupSession(
                                dispatches = listOf("U1", "U2", "U3").map { createSessionDispatch(userId = it) },
                                answers = listOf("U1", "U2").map { createStandupAnswer(userId = it) },
                            )
                        repository.save(session.toSchema())
                        entityManager.flush()
                        entityManager.clear()

                        val loaded = repository.findBySessionUid(sessionUid = session.sessionUid).shouldNotBeNull()

                        loaded.dispatches shouldHaveSize 3
                        loaded.answers.map { it.userId } shouldContainExactlyInAnyOrder listOf("U1", "U2")
                        loaded.toStandupSessionDto().answers.map { it.userId } shouldContainExactlyInAnyOrder
                            listOf("U1", "U2")
                    }
                }
            }
        })
