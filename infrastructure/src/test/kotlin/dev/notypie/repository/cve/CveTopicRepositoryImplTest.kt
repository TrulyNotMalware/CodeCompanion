package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveTopicSchema
import dev.notypie.schema.createCveTopicDefinition
import dev.notypie.schema.createCveTopicSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.util.Optional

private fun stubTransactionManager(): PlatformTransactionManager =
    mockk<PlatformTransactionManager>().also { manager ->
        every { manager.getTransaction(any()) } returns mockk<TransactionStatus>(relaxed = true)
        every { manager.commit(any()) } returns Unit
        every { manager.rollback(any()) } returns Unit
    }

class CveTopicRepositoryImplTest :
    BehaviorSpec({
        given("upsert with a topic key that has no row yet") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val definition = createCveTopicDefinition()
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns null
            every { jpaCveTopicRepository.saveAndFlush(any<CveTopicSchema>()) } answers { firstArg() }

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("a new schema row is saved with every definition field") {
                    written shouldBe true
                    val saved = slot<CveTopicSchema>()
                    verify(exactly = 1) { jpaCveTopicRepository.saveAndFlush(capture(saved)) }
                    saved.captured.topicKey shouldBe definition.topicKey
                    saved.captured.displayName shouldBe definition.displayName
                    saved.captured.category shouldBe definition.category
                    saved.captured.sourceType shouldBe definition.sourceType
                    saved.captured.sourceConfig shouldBe definition.sourceConfig
                    saved.captured.deliveryMode shouldBe definition.deliveryMode
                    saved.captured.active shouldBe definition.active
                }
            }
        }

        given("upsert with an identical existing row") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val definition = createCveTopicDefinition()
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns
                createCveTopicSchema(id = 7L)

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("nothing is saved") {
                    written shouldBe false
                    verify(exactly = 0) { jpaCveTopicRepository.save(any()) }
                }
            }
        }

        given("upsert with an existing row whose non-active fields differ") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val existing = createCveTopicSchema(id = 7L, deliveryMode = CveDeliveryMode.DIGEST, active = false)
            val definition = createCveTopicDefinition(deliveryMode = CveDeliveryMode.IMMEDIATE, active = true)
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns existing
            every { jpaCveTopicRepository.save(any()) } answers { firstArg() }

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("non-active fields sync but the DB active flag is preserved (yaml never reactivates)") {
                    written shouldBe true
                    verify(exactly = 1) { jpaCveTopicRepository.save(existing) }
                    existing.deliveryMode shouldBe CveDeliveryMode.IMMEDIATE
                    existing.active shouldBe false
                }
            }
        }

        given("upsert with an existing row that differs only in active") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val existing = createCveTopicSchema(id = 7L, active = false)
            val definition = createCveTopicDefinition(active = true)
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns existing

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("nothing is written, so the chat deactivation survives the reboot") {
                    written shouldBe false
                    verify(exactly = 0) { jpaCveTopicRepository.save(any()) }
                    existing.active shouldBe false
                }
            }
        }

        given("upsert whose insert loses the unique key to a replica that inserted the row meanwhile") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val definition = createCveTopicDefinition(displayName = "Renamed")
            val raced = createCveTopicSchema(id = 9L, topicKey = definition.topicKey, active = false)
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns null
            every { jpaCveTopicRepository.saveAndFlush(any<CveTopicSchema>()) } throws
                DataIntegrityViolationException("Duplicate entry for key 'uk_cve_topic_topic_key'")
            every { jpaCveTopicRepository.findLockedByTopicKey(topicKey = definition.topicKey) } returns raced
            every { jpaCveTopicRepository.save(any<CveTopicSchema>()) } answers { firstArg() }

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("the replica's row is read back with a locking read and synced, keeping its active flag") {
                    written shouldBe true
                    raced.displayName shouldBe "Renamed"
                    raced.active shouldBe false
                    verify(exactly = 1) { jpaCveTopicRepository.save(raced) }
                }
            }
        }

        given("upsert whose insert fails on a constraint with no row behind it") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val definition = createCveTopicDefinition()
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns null
            every { jpaCveTopicRepository.saveAndFlush(any<CveTopicSchema>()) } throws
                DataIntegrityViolationException("Data too long for column 'display_name'")
            every { jpaCveTopicRepository.findLockedByTopicKey(topicKey = definition.topicKey) } returns null

            `when`("upserted") {
                then("the violation is not a race and is rethrown") {
                    shouldThrow<DataIntegrityViolationException> { repository.upsert(definition = definition) }
                }
            }
        }

        given("findById") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            every { jpaCveTopicRepository.findById(3L) } returns
                Optional.of(createCveTopicSchema(id = 3L, topicKey = "found-topic"))
            every { jpaCveTopicRepository.findById(4L) } returns Optional.empty()

            `when`("the id exists and when it does not") {
                val found = repository.findById(id = 3L)
                val missing = repository.findById(id = 4L)

                then("an existing row maps to a topic and a missing one is null") {
                    found?.topicKey shouldBe "found-topic"
                    missing shouldBe null
                }
            }
        }

        given("findAllTopics") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val active = createCveTopicSchema(id = 1L, topicKey = "a-topic", active = true)
            val inactive = createCveTopicSchema(id = 2L, topicKey = "b-topic", active = false)
            every { jpaCveTopicRepository.findAllOrderByTopicKey() } returns listOf(active, inactive)

            `when`("queried") {
                val topics = repository.findAllTopics()

                then("both active and inactive topics are mapped, order preserved") {
                    topics.map { it.topicKey } shouldBe listOf("a-topic", "b-topic")
                    topics.map { it.active } shouldBe listOf(true, false)
                }
            }
        }

        given("countActive and setActive delegate to the jpa repository") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            every { jpaCveTopicRepository.countActive() } returns 4L
            every { jpaCveTopicRepository.setActive(topicKey = "kotlin", active = false) } returns 1

            `when`("called") {
                then("the delegated results pass through") {
                    repository.countActive() shouldBe 4L
                    repository.setActive(topicKey = "kotlin", active = false) shouldBe 1
                }
            }
        }

        given("findActiveTopics") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository =
                CveTopicRepositoryImpl(
                    jpaCveTopicRepository = jpaCveTopicRepository,
                    transactionManager = stubTransactionManager(),
                )
            val schema = createCveTopicSchema(id = 3L)
            every { jpaCveTopicRepository.findByActiveTrueOrderByTopicKey() } returns listOf(schema)

            `when`("queried") {
                val topics = repository.findActiveTopics()

                then("schemas are mapped to records field by field") {
                    topics.size shouldBe 1
                    topics.first() shouldBe
                        CveTopic(
                            id = 3L,
                            topicKey = schema.topicKey,
                            displayName = schema.displayName,
                            category = schema.category,
                            sourceType = schema.sourceType,
                            sourceConfig = schema.sourceConfig,
                            deliveryMode = schema.deliveryMode,
                            active = schema.active,
                        )
                }
            }
        }
    })
