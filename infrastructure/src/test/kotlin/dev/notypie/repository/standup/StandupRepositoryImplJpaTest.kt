package dev.notypie.repository.standup

import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupSession
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

// Real Hibernate + H2 so SQL ordering (IDENTITY inserts vs. orphan deletes) is what production sees.
@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class StandupRepositoryImplJpaTest
    @Autowired
    constructor(
        private val jpaRoutineRepository: JpaRoutineRepository,
        private val jpaStandupSessionRepository: JpaStandupSessionRepository,
        private val jpaSessionDispatchRepository: JpaSessionDispatchRepository,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                StandupRepositoryImpl(
                    jpaRoutineRepository = jpaRoutineRepository,
                    jpaStandupSessionRepository = jpaStandupSessionRepository,
                    jpaSessionDispatchRepository = jpaSessionDispatchRepository,
                )
            val transactionTemplate = TransactionTemplate(transactionManager)
            val cutoffAt = Instant.parse("2026-05-04T03:00:00Z")

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }!!

            fun openSession(): UUID =
                inTx {
                    repository
                        .createSession(
                            session =
                                createStandupSession(
                                    routineUid = UUID.randomUUID(),
                                    cutoffAt = cutoffAt,
                                    dispatches = listOf(createSessionDispatch(userId = "U_A")),
                                ),
                        ).sessionUid
                }

            fun record(sessionUid: UUID, responses: List<String>, submittedAt: Instant) =
                inTx {
                    repository.recordAnswer(
                        sessionUid = sessionUid,
                        userId = "U_A",
                        responses = responses,
                        submittedAt = submittedAt,
                    )
                }

            afterSpec {
                jpaStandupSessionRepository.deleteAll()
            }

            given("recordAnswer for a member who already answered (T9)") {
                `when`("the same member resubmits in a later transaction") {
                    val sessionUid = openSession()
                    record(
                        sessionUid = sessionUid,
                        responses = listOf("first", "draft"),
                        submittedAt = cutoffAt.minusSeconds(600L),
                    )

                    then("the second submission replaces the row instead of violating uk_standup_answer") {
                        shouldNotThrowAny {
                            record(
                                sessionUid = sessionUid,
                                responses = listOf("second", "final"),
                                submittedAt = cutoffAt.minusSeconds(300L),
                            )
                        }
                        val answers = repository.findSession(sessionUid = sessionUid)!!.answers
                        answers.size shouldBe 1
                        answers.single().responses shouldContainExactly listOf("second", "final")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(300L)
                    }
                }
            }
        })
