package dev.notypie.application.service.cve

import dev.notypie.application.configurations.createCveTopicConfigDefinition
import dev.notypie.repository.cve.CveTopicDefinition
import dev.notypie.repository.cve.CveTopicRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class CveTopicBootstrapTest :
    BehaviorSpec({
        given("two declared topics") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val declared =
                listOf(
                    createCveTopicConfigDefinition(),
                    createCveTopicConfigDefinition(key = "kotlin", displayName = "Kotlin releases"),
                )
            val bootstrap = CveTopicBootstrap(topics = declared, cveTopicRepository = cveTopicRepository)
            every { cveTopicRepository.upsert(definition = any()) } returns true
            every { cveTopicRepository.countActive() } returns 2L

            `when`("the boot sync runs") {
                bootstrap.bootstrapTopics()

                then("each declaration is upserted with its fields mapped") {
                    val definitions = mutableListOf<CveTopicDefinition>()
                    verify(exactly = 2) { cveTopicRepository.upsert(definition = capture(definitions)) }
                    definitions.map { it.topicKey } shouldBe listOf("cve-java", "kotlin")
                    val first = definitions.first()
                    val source = declared.first()
                    first.displayName shouldBe source.displayName
                    first.category shouldBe source.category
                    first.sourceType shouldBe source.sourceType
                    first.sourceConfig shouldBe source.sourceConfig
                    first.deliveryMode shouldBe source.deliveryMode
                    first.active shouldBe source.active
                }
            }
        }

        given("a declaration with a blank key") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap =
                CveTopicBootstrap(
                    topics = listOf(createCveTopicConfigDefinition(key = " ")),
                    cveTopicRepository = cveTopicRepository,
                )

            `when`("the boot sync runs") {
                then("boot fails before touching the repository") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("a declaration with a blank display name") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap =
                CveTopicBootstrap(
                    topics = listOf(createCveTopicConfigDefinition(displayName = "")),
                    cveTopicRepository = cveTopicRepository,
                )

            `when`("the boot sync runs") {
                then("boot fails before touching the repository") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("two declarations whose keys differ only in case") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap =
                CveTopicBootstrap(
                    topics =
                        listOf(
                            createCveTopicConfigDefinition(key = "springBoot"),
                            createCveTopicConfigDefinition(key = "springboot"),
                        ),
                    cveTopicRepository = cveTopicRepository,
                )

            `when`("the boot sync runs") {
                then("boot fails before touching the repository, since /latest matches keys ignoring case") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("a display name at 76 characters, one past the Slack option text limit") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap =
                CveTopicBootstrap(
                    topics = listOf(createCveTopicConfigDefinition(displayName = "d".repeat(76))),
                    cveTopicRepository = cveTopicRepository,
                )

            `when`("the boot sync runs") {
                then("boot fails before touching the repository") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("a key at 65 characters, one past the topic_key column") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap =
                CveTopicBootstrap(
                    topics = listOf(createCveTopicConfigDefinition(key = "k".repeat(65))),
                    cveTopicRepository = cveTopicRepository,
                )

            `when`("the boot sync runs") {
                then("boot fails before touching the repository") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("101 declared active topics, one past the modal's option limit") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap =
                CveTopicBootstrap(
                    topics = (1..101).map { createCveTopicConfigDefinition(key = "topic-$it") },
                    cveTopicRepository = cveTopicRepository,
                )

            `when`("the boot sync runs") {
                then("boot fails before touching the repository") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("topics exactly at every limit, plus inactive ones beyond 100") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val declared =
                (1..100).map {
                    createCveTopicConfigDefinition(key = "t$it".padEnd(64, 'k'), displayName = "d".repeat(75))
                } + (1..5).map { createCveTopicConfigDefinition(key = "off-$it", active = false) }
            val bootstrap = CveTopicBootstrap(topics = declared, cveTopicRepository = cveTopicRepository)
            every { cveTopicRepository.upsert(definition = any()) } returns true
            every { cveTopicRepository.countActive() } returns 100L

            `when`("the boot sync runs") {
                bootstrap.bootstrapTopics()

                then("every declaration is upserted") {
                    verify(exactly = 105) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("no declared topics") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap = CveTopicBootstrap(topics = emptyList(), cveTopicRepository = cveTopicRepository)
            every { cveTopicRepository.countActive() } returns 0L

            `when`("the boot sync runs") {
                bootstrap.bootstrapTopics()

                then("nothing is upserted") {
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }
    })
