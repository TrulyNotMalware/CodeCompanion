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
                then("boot fails before touching the repository, since keys are matched ignoring case") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }

        given("display names measured against the 128-character column") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            every { cveTopicRepository.upsert(definition = any()) } returns true

            `when`("a name has 128 emoji, 256 UTF-16 units but 128 characters") {
                val bootstrap =
                    CveTopicBootstrap(
                        topics = listOf(createCveTopicConfigDefinition(displayName = "😀".repeat(n = 128))),
                        cveTopicRepository = cveTopicRepository,
                    )

                then("it is accepted") {
                    bootstrap.bootstrapTopics()
                    verify(exactly = 1) { cveTopicRepository.upsert(definition = any()) }
                }
            }

            `when`("a name has 129 characters") {
                val bootstrap =
                    CveTopicBootstrap(
                        topics =
                            listOf(
                                createCveTopicConfigDefinition(key = "long", displayName = "n".repeat(n = 129)),
                            ),
                        cveTopicRepository = cveTopicRepository,
                    )

                then("boot fails before touching the repository") {
                    shouldThrow<IllegalArgumentException> { bootstrap.bootstrapTopics() }
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = match { it.topicKey == "long" }) }
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

        given("no declared topics") {
            val cveTopicRepository = mockk<CveTopicRepository>()
            val bootstrap = CveTopicBootstrap(topics = emptyList(), cveTopicRepository = cveTopicRepository)

            `when`("the boot sync runs") {
                bootstrap.bootstrapTopics()

                then("the repository is never called") {
                    verify(exactly = 0) { cveTopicRepository.upsert(definition = any()) }
                }
            }
        }
    })
