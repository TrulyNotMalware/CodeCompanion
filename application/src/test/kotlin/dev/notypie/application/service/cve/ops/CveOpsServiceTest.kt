package dev.notypie.application.service.cve.ops

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.command.createCveOpsRequestEvent
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.CveOpsAction
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.cve.TopicEventCount
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.schema.createCveTopic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class CveOpsServiceTest :
    BehaviorSpec({
        val maxRetries = 5

        fun serviceWith(
            topicRepository: CveTopicRepository,
            eventRepository: CveEventRepository,
            stagedMessage: CapturingSlot<OutboundMessage>,
            enabled: Boolean = true,
        ): Pair<CveOpsService, EventPublisher> {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(stagedMessage), basicInfo = any()) } returns
                mockk<CommandEvent<EventPayload>>(relaxed = true)
            val publisher = mockk<EventPublisher>(relaxed = true)
            val service =
                CveOpsService(
                    appConfig =
                        AppConfig(
                            cve = AppConfig.Cve(enabled = enabled),
                            ai = AppConfig.Ai(maxRetries = maxRetries),
                        ),
                    cveTopicRepository = topicRepository,
                    cveEventRepository = eventRepository,
                    outboundStager = stager,
                    eventPublisher = publisher,
                )
            return service to publisher
        }

        fun CapturingSlot<OutboundMessage>.markdown(): String =
            captured
                .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                .also { it.target.id shouldBe TEST_CHANNEL_ID }
                .content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown

        given("a LIST_TOPICS event") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { topicRepository.findAllTopics() } returns
                listOf(
                    createCveTopic(
                        id = 1L,
                        topicKey = "kotlin",
                        displayName = "Kotlin",
                        deliveryMode = CveDeliveryMode.DIGEST,
                        active = true,
                    ),
                    createCveTopic(
                        id = 2L,
                        topicKey = "cve-java",
                        displayName = "Java CVE",
                        deliveryMode = CveDeliveryMode.IMMEDIATE,
                        active = false,
                    ),
                )
            every { eventRepository.countEventsByTopic(topicIds = listOf(1L, 2L)) } returns
                listOf(TopicEventCount(topicId = 1L, count = 7L))
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(event = createCveOpsRequestEvent(action = CveOpsAction.LIST_TOPICS))

                then("every topic is listed with mode, active flag, and event count (zero when absent)") {
                    val markdown = staged.markdown()
                    markdown shouldContain "CVE topics (2):"
                    markdown shouldContain "• *Kotlin* (`kotlin`) — digest, active, 7 event(s)"
                    markdown shouldContain "• *Java CVE* (`cve-java`) — immediate, inactive, 0 event(s)"
                }
            }
        }

        given("a DEACTIVATE_TOPIC event for a known active topic") {
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val eventRepository = mockk<CveEventRepository>()
            every { topicRepository.findAllTopics() } returns
                listOf(createCveTopic(id = 1L, topicKey = "kotlin", displayName = "Kotlin", active = true))
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.DEACTIVATE_TOPIC, topicKey = "kotlin"),
                )

                then("the flag is flipped and the reply confirms the old to new transition") {
                    verify(exactly = 1) { topicRepository.setActive(topicKey = "kotlin", active = false) }
                    staged.markdown() shouldBe "Topic *Kotlin* (`kotlin`): active → inactive."
                }
            }
        }

        given("topic names and keys carrying Slack control sequences") {
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val eventRepository = mockk<CveEventRepository>()
            every { topicRepository.findAllTopics() } returns
                listOf(createCveTopic(id = 1L, topicKey = "r&d", displayName = "<!channel>", active = true))
            every { eventRepository.countEventsByTopic(topicIds = listOf(1L)) } returns emptyList()
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("the topics are listed") {
                service.handleCveOps(event = createCveOpsRequestEvent(action = CveOpsAction.LIST_TOPICS))

                then("the name and key are escaped") {
                    staged.markdown() shouldContain "• *&lt;!channel&gt;* (`r&amp;d`) — "
                }
            }

            `when`("the topic is toggled") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.DEACTIVATE_TOPIC, topicKey = "r&d"),
                )

                then("the echoed name and key are escaped") {
                    staged.markdown() shouldBe "Topic *&lt;!channel&gt;* (`r&amp;d`): active → inactive."
                }
            }

            `when`("an unknown key is typed") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.ACTIVATE_TOPIC, topicKey = "<!here>"),
                )

                then("the echoed key is escaped") {
                    staged.markdown() shouldBe "No CVE topic with key `&lt;!here&gt;`."
                }
            }
        }

        given("a DEACTIVATE_TOPIC event whose key the mention parser lower-cased") {
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val eventRepository = mockk<CveEventRepository>()
            every { topicRepository.findAllTopics() } returns
                listOf(createCveTopic(id = 1L, topicKey = "springBoot", displayName = "Spring Boot", active = true))
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.DEACTIVATE_TOPIC, topicKey = "springboot"),
                )

                then("the stored mixed-case key is found and toggled") {
                    verify(exactly = 1) { topicRepository.setActive(topicKey = "springBoot", active = false) }
                    staged.markdown() shouldBe "Topic *Spring Boot* (`springBoot`): active → inactive."
                }
            }
        }

        given("an ACTIVATE_TOPIC event for an unknown key") {
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val eventRepository = mockk<CveEventRepository>()
            every { topicRepository.findAllTopics() } returns emptyList()
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.ACTIVATE_TOPIC, topicKey = "ghost"),
                )

                then("an error text is returned without flipping anything") {
                    verify(exactly = 0) { topicRepository.setActive(topicKey = any(), active = any()) }
                    staged.markdown() shouldBe "No CVE topic with key `ghost`."
                }
            }
        }

        given("a RETRY_ALL event") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { eventRepository.resetDeadLetters(maxRetries = maxRetries) } returns 3
            val staged = slot<OutboundMessage>()
            val (service, publisher) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(event = createCveOpsRequestEvent(action = CveOpsAction.RETRY_ALL))

                then("the revived count is reported and the reply is published") {
                    staged.markdown() shouldBe "Re-queued 3 dead-letter event(s) for summarization."
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }

        given("a RETRY_EVENT event that matches a dead-letter") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { eventRepository.resetDeadLetter(id = 42L, maxRetries = maxRetries) } returns 1
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.RETRY_EVENT, targetEventId = 42L),
                )

                then("the event is reported as re-queued") {
                    staged.markdown() shouldBe "Re-queued event #42 for summarization."
                }
            }
        }

        given("a RETRY_EVENT event that matches nothing") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { eventRepository.resetDeadLetter(id = 99L, maxRetries = maxRetries) } returns 0
            val staged = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                )

            `when`("handled") {
                service.handleCveOps(
                    event = createCveOpsRequestEvent(action = CveOpsAction.RETRY_EVENT, targetEventId = 99L),
                )

                then("a not-a-dead-letter text is returned rather than an exception") {
                    staged.markdown() shouldContain "Event #99 is not a dead-letter"
                }
            }
        }

        given("any event while the feature is disabled") {
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val staged = slot<OutboundMessage>()
            val (service, publisher) =
                serviceWith(
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stagedMessage = staged,
                    enabled = false,
                )

            `when`("handled") {
                service.handleCveOps(event = createCveOpsRequestEvent(action = CveOpsAction.RETRY_ALL))

                then("nothing is written but the admin still gets a disabled reply") {
                    verify(exactly = 0) { eventRepository.resetDeadLetters(maxRetries = any()) }
                    verify(exactly = 0) { topicRepository.findAllTopics() }
                    staged.markdown() shouldBe "The CVE feature is currently disabled."
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }
    })
