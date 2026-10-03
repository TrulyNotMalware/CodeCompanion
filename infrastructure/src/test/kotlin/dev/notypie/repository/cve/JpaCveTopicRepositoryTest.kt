package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import dev.notypie.schema.createCveTopicDefinition
import dev.notypie.schema.createCveTopicSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaCveTopicRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaCveTopicRepository,
        private val jdbcTemplate: JdbcTemplate,
        private val transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val committed =
                TransactionTemplate(transactionManager).apply {
                    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                }

            afterSpec {
                committed.executeWithoutResult {
                    jdbcTemplate.update("DELETE FROM cve_topic WHERE topic_key = ?", "replica-race")
                }
            }

            given("a bootstrap upsert holding a topic read before an admin deactivated it") {
                `when`("the upsert renames the topic after the deactivation committed") {
                    then("its UPDATE writes only the changed columns, so the deactivation survives") {
                        repository.saveAndFlush(createCveTopicSchema(topicKey = "race-topic", active = true))
                        repository.findByTopicKey(topicKey = "race-topic")
                        jdbcTemplate.update("UPDATE cve_topic SET active = FALSE WHERE topic_key = ?", "race-topic")

                        CveTopicRepositoryImpl(
                            jpaCveTopicRepository = repository,
                            transactionManager = transactionManager,
                        ).upsert(
                            definition = createCveTopicDefinition(topicKey = "race-topic", displayName = "Renamed"),
                        )
                        repository.flush()

                        jdbcTemplate.queryForObject(
                            "SELECT active FROM cve_topic WHERE topic_key = ?",
                            Boolean::class.java,
                            "race-topic",
                        ) shouldBe false
                        jdbcTemplate.queryForObject(
                            "SELECT display_name FROM cve_topic WHERE topic_key = ?",
                            String::class.java,
                            "race-topic",
                        ) shouldBe "Renamed"
                    }
                }
            }

            given("two replicas booting together, the other one committing the topic between the read and the insert") {
                `when`("this replica's insert loses the unique key") {
                    then("the bootstrap transaction survives and syncs the committed row, keeping its active flag") {
                        committed.executeWithoutResult {
                            repository.saveAndFlush(createCveTopicSchema(topicKey = "replica-race", active = false))
                        }
                        var lockedReads = 0
                        val lockedBeforeTheOtherCommit =
                            object : JpaCveTopicRepository by repository {
                                override fun findLockedByTopicKey(topicKey: String): CveTopicSchema? =
                                    if (lockedReads++ ==
                                        0
                                    ) {
                                        null
                                    } else {
                                        repository.findLockedByTopicKey(topicKey = topicKey)
                                    }
                            }

                        val written =
                            CveTopicRepositoryImpl(
                                jpaCveTopicRepository = lockedBeforeTheOtherCommit,
                                transactionManager = transactionManager,
                            ).upsert(
                                definition =
                                    createCveTopicDefinition(topicKey = "replica-race", displayName = "Renamed"),
                            )
                        repository.flush()

                        written shouldBe true
                        lockedReads shouldBe 2
                        jdbcTemplate.queryForObject(
                            "SELECT display_name FROM cve_topic WHERE topic_key = ?",
                            String::class.java,
                            "replica-race",
                        ) shouldBe "Renamed"
                        jdbcTemplate.queryForObject(
                            "SELECT active FROM cve_topic WHERE topic_key = ?",
                            Boolean::class.java,
                            "replica-race",
                        ) shouldBe false
                    }
                }
            }

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
