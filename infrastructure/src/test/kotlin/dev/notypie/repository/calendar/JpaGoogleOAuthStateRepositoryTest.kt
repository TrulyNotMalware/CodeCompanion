package dev.notypie.repository.calendar

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Duration
import java.time.Instant

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaGoogleOAuthStateRepositoryTest
    @Autowired
    constructor(
        private val jpaGoogleOAuthStateRepository: JpaGoogleOAuthStateRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository =
                GoogleOAuthStateRepositoryImpl(jpaGoogleOAuthStateRepository = jpaGoogleOAuthStateRepository)
            val now = Instant.parse("2026-10-07T10:00:00Z")

            given("an issued state") {
                `when`("it is consumed twice before it expires") {
                    then("the first consume returns the user and the replay returns nothing") {
                        repository.issue(
                            state = "state-once",
                            userId = "U_ONE",
                            expiresAt = now.plus(Duration.ofMinutes(10)),
                        )
                        entityManager.flush()

                        repository.consume(state = "state-once", now = now.plusSeconds(30)) shouldBe "U_ONE"
                        repository.consume(state = "state-once", now = now.plusSeconds(31)).shouldBeNull()
                    }
                }

                `when`("it is consumed after it expired") {
                    then("nothing is returned and the row stays unconsumed") {
                        repository.issue(
                            state = "state-late",
                            userId = "U_LATE",
                            expiresAt = now.plus(Duration.ofMinutes(10)),
                        )
                        entityManager.flush()

                        repository.consume(state = "state-late", now = now.plus(Duration.ofMinutes(11))).shouldBeNull()
                        entityManager.clear()
                        jpaGoogleOAuthStateRepository
                            .findById("state-late")
                            .get()
                            .consumedAt
                            .shouldBeNull()
                    }
                }

                `when`("an unknown state is consumed") {
                    then("nothing is returned") {
                        repository.consume(state = "never-issued", now = now).shouldBeNull()
                    }
                }
            }

            given("live states of two users") {
                `when`("one user disconnects") {
                    then("only that user's states are deleted") {
                        repository.issue(state = "mine-1", userId = "U_ME", expiresAt = now.plusSeconds(60))
                        repository.issue(state = "mine-2", userId = "U_ME", expiresAt = now.plusSeconds(60))
                        repository.issue(state = "theirs", userId = "U_OTHER", expiresAt = now.plusSeconds(60))
                        entityManager.flush()

                        repository.deleteForUser(userId = "U_ME") shouldBe 2
                        entityManager.clear()
                        jpaGoogleOAuthStateRepository.existsById("mine-1") shouldBe false
                        jpaGoogleOAuthStateRepository.existsById("theirs") shouldBe true
                    }
                }
            }

            given("expired and live states") {
                `when`("expired rows are purged") {
                    then("only rows past the cutoff are deleted") {
                        repository.issue(state = "old", userId = "U", expiresAt = now.minusSeconds(1))
                        repository.issue(state = "live", userId = "U", expiresAt = now.plusSeconds(60))
                        entityManager.flush()

                        repository.deleteExpired(before = now) shouldBe 1
                        entityManager.clear()
                        jpaGoogleOAuthStateRepository.existsById("old") shouldBe false
                        jpaGoogleOAuthStateRepository.existsById("live") shouldBe true
                    }
                }
            }
        })
