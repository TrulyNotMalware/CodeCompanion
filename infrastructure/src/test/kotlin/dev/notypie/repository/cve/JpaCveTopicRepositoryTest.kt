package dev.notypie.repository.cve

import dev.notypie.schema.createCveTopicSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest

/**
 * Runs the admin-facing topic queries against a real database. Setup writes go through the
 * repository (self-transactional) because kotest container scopes run outside the test
 * transaction; rows persist across blocks and specs share one H2, so every topicKey is unique
 * and list/count assertions are scoped or delta-based rather than global exact matches.
 */
@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaCveTopicRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaCveTopicRepository,
    ) : BehaviorSpec({
            given("active and inactive topics with out-of-order keys") {
                repository.saveAndFlush(createCveTopicSchema(topicKey = "order-zz", active = true))
                repository.saveAndFlush(createCveTopicSchema(topicKey = "order-aa", active = false))
                repository.saveAndFlush(createCveTopicSchema(topicKey = "order-mm", active = true))

                `when`("findAllOrderByTopicKey runs") {
                    val keys =
                        repository
                            .findAllOrderByTopicKey()
                            .map { it.topicKey }
                            .filter { it.startsWith("order-") }

                    then("inactive topics are included and the order is by key") {
                        keys shouldContainExactly listOf("order-aa", "order-mm", "order-zz")
                    }
                }
            }

            given("a mixed active population") {
                val baseline = repository.countActive()
                repository.saveAndFlush(createCveTopicSchema(topicKey = "count-active-1", active = true))
                repository.saveAndFlush(createCveTopicSchema(topicKey = "count-active-2", active = true))
                repository.saveAndFlush(createCveTopicSchema(topicKey = "count-inactive", active = false))

                `when`("countActive runs") {
                    then("only the active rows raise the count") {
                        repository.countActive() shouldBe baseline + 2
                    }
                }
            }

            given("an inactive topic and its key") {
                val id = repository.saveAndFlush(createCveTopicSchema(topicKey = "toggle-me", active = false)).id

                `when`("setActive flips it on, then an unknown key is toggled") {
                    val flipped = repository.setActive(topicKey = "toggle-me", active = true)
                    val unknown = repository.setActive(topicKey = "toggle-ghost", active = true)

                    then("the row updates and the unknown key reports zero rows") {
                        flipped shouldBe 1
                        unknown shouldBe 0
                        repository.findById(id).orElseThrow().active shouldBe true
                    }
                }
            }
        })
