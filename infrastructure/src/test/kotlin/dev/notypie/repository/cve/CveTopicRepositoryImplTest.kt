package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveTopicSchema
import dev.notypie.schema.createCveTopicDefinition
import dev.notypie.schema.createCveTopicSchema
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class CveTopicRepositoryImplTest :
    BehaviorSpec({
        given("upsert with a topic key that has no row yet") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository = CveTopicRepositoryImpl(jpaCveTopicRepository = jpaCveTopicRepository)
            val definition = createCveTopicDefinition()
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns null
            every { jpaCveTopicRepository.save(any()) } answers { firstArg() }

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("a new schema row is saved with every definition field") {
                    written shouldBe true
                    val saved = slot<CveTopicSchema>()
                    verify(exactly = 1) { jpaCveTopicRepository.save(capture(saved)) }
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
            val repository = CveTopicRepositoryImpl(jpaCveTopicRepository = jpaCveTopicRepository)
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

        given("upsert with an existing row whose fields differ") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository = CveTopicRepositoryImpl(jpaCveTopicRepository = jpaCveTopicRepository)
            val existing = createCveTopicSchema(id = 7L, deliveryMode = CveDeliveryMode.DIGEST, active = false)
            val definition = createCveTopicDefinition(deliveryMode = CveDeliveryMode.IMMEDIATE, active = true)
            every { jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) } returns existing
            every { jpaCveTopicRepository.save(any()) } answers { firstArg() }

            `when`("upserted") {
                val written = repository.upsert(definition = definition)

                then("the existing row is updated in place and saved") {
                    written shouldBe true
                    verify(exactly = 1) { jpaCveTopicRepository.save(existing) }
                    existing.deliveryMode shouldBe CveDeliveryMode.IMMEDIATE
                    existing.active shouldBe true
                }
            }
        }

        given("findActiveTopics") {
            val jpaCveTopicRepository = mockk<JpaCveTopicRepository>()
            val repository = CveTopicRepositoryImpl(jpaCveTopicRepository = jpaCveTopicRepository)
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
