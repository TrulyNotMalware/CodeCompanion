package dev.notypie.application.service.cve.subscription

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.createCveSubscriptionRequestEvent
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.CveSubscriptionAction
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.cve.CveSubscriptionRepository
import dev.notypie.repository.cve.CveTopicRepository
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

class CveSubscriptionServiceTest :
    BehaviorSpec({
        val userId = "U_SUB"
        val stubStagedEvent = mockk<CommandEvent<EventPayload>>(relaxed = true)

        fun serviceWith(
            subscriptionRepository: CveSubscriptionRepository,
            topicRepository: CveTopicRepository,
            stagedMessage: CapturingSlot<OutboundMessage>,
            enabled: Boolean = true,
        ): Pair<CveSubscriptionService, EventPublisher> {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(stagedMessage), basicInfo = any()) } returns stubStagedEvent
            val publisher = mockk<EventPublisher>(relaxed = true)
            val service =
                CveSubscriptionService(
                    appConfig = AppConfig(cve = AppConfig.Cve(enabled = enabled)),
                    cveSubscriptionRepository = subscriptionRepository,
                    cveTopicRepository = topicRepository,
                    outboundStager = stager,
                    eventPublisher = publisher,
                )
            return service to publisher
        }

        // The confirmation is a DM: a ChannelMessage whose channel/target is the user id itself.
        fun CapturingSlot<OutboundMessage>.dmMarkdown(): String =
            captured
                .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                .also { it.target.id shouldBe userId }
                .content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown

        given("a SUBSCRIBE event with one known and one deactivated key") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            every { topicRepository.findActiveTopics() } returns
                listOf(createCveTopic(id = 10L, topicKey = "kotlin", displayName = "Kotlin"))
            val stagedMessage = slot<OutboundMessage>()
            val (service, publisher) =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = topicRepository,
                    stagedMessage = stagedMessage,
                )

            `when`("handled") {
                service.handleCveSubscription(
                    event =
                        createCveSubscriptionRequestEvent(
                            action = CveSubscriptionAction.SUBSCRIBE,
                            userId = userId,
                            topicKeys = listOf("kotlin", "ghost"),
                        ),
                )

                then("only the resolved active topic id is subscribed") {
                    verify(exactly = 1) {
                        subscriptionRepository.subscribe(userId = userId, topicIds = listOf(10L))
                    }
                }

                then("the DM confirms the known topic and notes the skipped one, then publishes") {
                    val markdown = stagedMessage.dmMarkdown()
                    markdown shouldContain "Subscribed to 1 topic(s): *Kotlin*."
                    markdown shouldContain "Skipped unavailable topics: `ghost`."
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }

        given("an UNSUBSCRIBE event") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = userId) } returns
                listOf(createCveTopic(id = 5L, topicKey = "cve-java", displayName = "Java CVE"))
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = topicRepository,
                    stagedMessage = stagedMessage,
                )

            `when`("handled") {
                service.handleCveSubscription(
                    event =
                        createCveSubscriptionRequestEvent(
                            action = CveSubscriptionAction.UNSUBSCRIBE,
                            userId = userId,
                            topicKeys = listOf("cve-java"),
                        ),
                )

                then("the resolved subscription is removed and confirmed in a DM") {
                    verify(exactly = 1) {
                        subscriptionRepository.unsubscribe(userId = userId, topicIds = listOf(5L))
                    }
                    stagedMessage.dmMarkdown() shouldContain "Unsubscribed from 1 topic(s): *Java CVE*."
                }
            }
        }

        given("a LIST event with subscriptions") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            val topicRepository = mockk<CveTopicRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = userId) } returns
                listOf(
                    createCveTopic(id = 5L, topicKey = "cve-java", displayName = "Java CVE"),
                    createCveTopic(id = 6L, topicKey = "kotlin", displayName = "Kotlin"),
                )
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = topicRepository,
                    stagedMessage = stagedMessage,
                )

            `when`("handled") {
                service.handleCveSubscription(
                    event =
                        createCveSubscriptionRequestEvent(
                            action = CveSubscriptionAction.LIST,
                            userId = userId,
                            topicKeys = emptyList(),
                        ),
                )

                then("every subscribed topic is rendered in the DM") {
                    val markdown = stagedMessage.dmMarkdown()
                    markdown shouldContain "You're subscribed to 2 topic(s):"
                    markdown shouldContain "• *Java CVE* (`cve-java`)"
                    markdown shouldContain "• *Kotlin* (`kotlin`)"
                }
            }
        }

        given("a LIST event without subscriptions") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            val topicRepository = mockk<CveTopicRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = userId) } returns emptyList()
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = topicRepository,
                    stagedMessage = stagedMessage,
                )

            `when`("handled") {
                service.handleCveSubscription(
                    event =
                        createCveSubscriptionRequestEvent(
                            action = CveSubscriptionAction.LIST,
                            userId = userId,
                            topicKeys = emptyList(),
                        ),
                )

                then("the DM states there are no subscriptions") {
                    stagedMessage.dmMarkdown() shouldBe "You have no CVE topic subscriptions."
                }
            }
        }
        given("an UNSUBSCRIBE event with a key that is not subscribed") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = userId) } returns
                listOf(createCveTopic(id = 5L, topicKey = "cve-java", displayName = "Java CVE"))
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = topicRepository,
                    stagedMessage = stagedMessage,
                )

            `when`("handled") {
                service.handleCveSubscription(
                    event =
                        createCveSubscriptionRequestEvent(
                            action = CveSubscriptionAction.UNSUBSCRIBE,
                            userId = userId,
                            topicKeys = listOf("cve-java", "ghost"),
                        ),
                )

                then("only the subscribed topic is removed and the unknown key is named as skipped") {
                    verify(exactly = 1) {
                        subscriptionRepository.unsubscribe(userId = userId, topicIds = listOf(5L))
                    }
                    val markdown = stagedMessage.dmMarkdown()
                    markdown shouldContain "Unsubscribed from 1 topic(s): *Java CVE*."
                    markdown shouldContain "Skipped not subscribed topics: `ghost`."
                }
            }
        }

        given("an event arriving while the feature is disabled") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            val topicRepository = mockk<CveTopicRepository>()
            val stagedMessage = slot<OutboundMessage>()
            val (service, publisher) =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = topicRepository,
                    stagedMessage = stagedMessage,
                    enabled = false,
                )

            `when`("handled") {
                service.handleCveSubscription(
                    event =
                        createCveSubscriptionRequestEvent(
                            action = CveSubscriptionAction.SUBSCRIBE,
                            userId = userId,
                            topicKeys = listOf("kotlin"),
                        ),
                )

                then("nothing is written, staged, or published") {
                    verify(exactly = 0) { subscriptionRepository.subscribe(userId = any(), topicIds = any()) }
                    verify(exactly = 0) { publisher.publishEvent(events = any()) }
                    stagedMessage.isCaptured shouldBe false
                }
            }
        }
    })
