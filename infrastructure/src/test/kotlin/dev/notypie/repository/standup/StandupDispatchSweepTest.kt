package dev.notypie.repository.standup

import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.repository.standup.schema.toSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Instant

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class StandupDispatchSweepTest
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

            given("a dispatch claimed at an application time far from the database's own clock") {
                `when`("the stuck sweep runs with cutoffs on either side of that claim") {
                    then("only the cutoff after the claim resets it, so the sweep compares against the bound time") {
                        val claimedAt = Instant.parse("2020-01-01T00:00:00Z")
                        val saved =
                            jpaStandupSessionRepository.save(
                                createStandupSession(dispatches = listOf(createSessionDispatch(userId = "U_SWEEP")))
                                    .toSchema(),
                            )
                        entityManager.flush()
                        val dispatchId = saved.dispatches.single().id

                        repository.claimDispatch(
                            dispatchId = dispatchId,
                            claimToken = "token",
                            now = claimedAt,
                        ) shouldBe
                            true
                        repository.resetStuckDispatches(
                            olderThan = claimedAt.minusSeconds(60),
                            now = claimedAt.plusSeconds(60),
                        ) shouldBe 0
                        repository.resetStuckDispatches(
                            olderThan = claimedAt.plusSeconds(60),
                            now = claimedAt.plusSeconds(120),
                        ) shouldBe 1
                    }
                }
            }
        })
