package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Instant

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class GoogleCalendarConnectionRepositoryImplTest
    @Autowired
    constructor(
        private val jpaGoogleCalendarConnectionRepository: JpaGoogleCalendarConnectionRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository =
                GoogleCalendarConnectionRepositoryImpl(
                    jpaGoogleCalendarConnectionRepository = jpaGoogleCalendarConnectionRepository,
                )
            val first = Instant.parse("2026-10-07T10:00:00Z")
            val second = Instant.parse("2026-10-08T10:00:00Z")

            given("a user who connects, reconnects with another account and then disconnects") {
                `when`("saveActive runs twice for the same user") {
                    then(
                        "one row is kept and rewritten with the newer token, email and time, and the replaced token is reported",
                    ) {
                        val firstSave =
                            repository.saveActive(
                                userId = "U_CAL",
                                googleSubject = "sub-a",
                                googleEmail = "a@example.com",
                                encryptedRefreshToken = "v1.a",
                                now = first,
                            )
                        val secondSave =
                            repository.saveActive(
                                userId = "U_CAL",
                                googleSubject = "sub-b",
                                googleEmail = "b@example.com",
                                encryptedRefreshToken = "v1.b",
                                now = second,
                            )
                        entityManager.flush()
                        entityManager.clear()

                        firstSave.replaced.shouldBeNull()
                        val replaced = secondSave.replaced.shouldNotBeNull()
                        replaced.encryptedRefreshToken shouldBe "v1.a"
                        replaced.googleSubject shouldBe "sub-a"
                        replaced.googleEmail shouldBe "a@example.com"
                        secondSave.toString() shouldNotContain "v1."

                        val connection = repository.find(userId = "U_CAL").shouldNotBeNull()
                        connection.googleSubject shouldBe "sub-b"
                        connection.googleEmail shouldBe "b@example.com"
                        connection.encryptedRefreshToken shouldBe "v1.b"
                        connection.connectedAt shouldBe second
                        connection.status shouldBe CalendarConnectionStatus.ACTIVE
                        connection.isActive shouldBe true
                        jpaGoogleCalendarConnectionRepository.findAll().count { it.slackUserId == "U_CAL" } shouldBe 1
                    }
                }

                `when`("the connection is deleted") {
                    then("find returns nothing and a second delete reports no row") {
                        repository.saveActive(
                            userId = "U_GONE",
                            googleSubject = null,
                            googleEmail = null,
                            encryptedRefreshToken = "v1.x",
                            now = first,
                        )
                        entityManager.flush()

                        repository.delete(userId = "U_GONE") shouldBe true
                        entityManager.flush()
                        entityManager.clear()
                        repository.find(userId = "U_GONE").shouldBeNull()
                        repository.delete(userId = "U_GONE") shouldBe false
                    }
                }
            }

            given("a user who never connected") {
                then("find returns nothing") {
                    repository.find(userId = "U_NONE").shouldBeNull()
                }
            }
        })
