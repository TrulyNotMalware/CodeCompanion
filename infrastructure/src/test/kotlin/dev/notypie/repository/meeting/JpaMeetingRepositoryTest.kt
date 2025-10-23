package dev.notypie.repository.meeting

import dev.notypie.schema.createMeetingSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaMeetingRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaMeetingRepository,
    ) : BehaviorSpec({

            given("Meeting Repository WITHOUT SpringExtension") {
                `when`("saving a meeting") {
                    then("should work correctly") {
                        val meetingSchema = createMeetingSchema(member = 3)
                        val saved = repository.save(meetingSchema)

                        saved.id shouldNotBe null
                        saved.publisherId shouldBe meetingSchema.publisherId
                    }
                }

                `when`("finding by id") {
                    then("should retrieve correctly") {
                        val meetingSchema = createMeetingSchema(member = 3)
                        val saved = repository.save(meetingSchema)

                        val found = repository.findById(saved.id)
                        found.isPresent shouldBe true
                        found.get().publisherId shouldBe meetingSchema.publisherId
                    }
                }
            }
        })
