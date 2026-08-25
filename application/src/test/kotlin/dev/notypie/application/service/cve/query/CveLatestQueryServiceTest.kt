package dev.notypie.application.service.cve.query

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createCveLatestRequestEvent
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveSubscriptionRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.schema.createCveRecentEvent
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

class CveLatestQueryServiceTest :
    BehaviorSpec({
        fun serviceWith(
            subscriptionRepository: CveSubscriptionRepository,
            topicRepository: CveTopicRepository,
            eventRepository: CveEventRepository,
            stager: OutboundMessageStager,
            enabled: Boolean = true,
        ): CveLatestQueryService =
            CveLatestQueryService(
                appConfig = AppConfig(cve = AppConfig.Cve(enabled = enabled)),
                cveSubscriptionRepository = subscriptionRepository,
                cveTopicRepository = topicRepository,
                cveEventRepository = eventRepository,
                outboundStager = stager,
                eventPublisher = mockk<EventPublisher>(relaxed = true),
            )

        fun stagerCapturing(stagedMessage: CapturingSlot<OutboundMessage>): OutboundMessageStager {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(stagedMessage), basicInfo = any()) } returns
                mockk<CommandEvent<EventPayload>>(relaxed = true)
            return stager
        }

        fun CapturingSlot<OutboundMessage>.markdown(): String =
            captured
                .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                .also { it.target.id shouldBe TEST_USER_ID }
                .content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown

        given("the CVE feature disabled at handling time") {
            val stager = mockk<OutboundMessageStager>()
            val service =
                serviceWith(
                    subscriptionRepository = mockk(),
                    topicRepository = mockk(),
                    eventRepository = mockk(),
                    stager = stager,
                    enabled = false,
                )

            `when`("a latest request arrives") {
                service.handleCveLatest(event = createCveLatestRequestEvent())

                then("nothing is read or staged") {
                    verify(exactly = 0) { stager.stage(message = any(), basicInfo = any()) }
                }
            }
        }

        given("a caller with no subscriptions and no topic argument") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = TEST_USER_ID) } returns emptyList()
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = mockk(),
                    eventRepository = mockk(),
                    stager = stagerCapturing(stagedMessage = staged),
                )

            `when`("handled") {
                service.handleCveLatest(event = createCveLatestRequestEvent())

                then("the DM points the caller at /subscribe") {
                    staged.markdown() shouldContain "no CVE topic subscriptions"
                }
            }
        }

        given("a caller subscribed to two topics, no topic argument") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = TEST_USER_ID) } returns
                listOf(
                    createCveTopic(id = 11L, topicKey = "kotlin", displayName = "Kotlin"),
                    createCveTopic(id = 12L, topicKey = "cve-java", displayName = "Java CVE"),
                )
            every { eventRepository.findRecentDoneEvents(topicIds = listOf(11L, 12L), limit = 5) } returns
                listOf(
                    createCveRecentEvent(topicDisplayName = "Kotlin", title = "v2.3.0", aiSummary = "New release."),
                    createCveRecentEvent(
                        topicDisplayName = "Java CVE",
                        title = "CVE-2026-1111",
                        aiSummary = "A flaw.",
                    ),
                )
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = mockk(),
                    eventRepository = eventRepository,
                    stager = stagerCapturing(stagedMessage = staged),
                )

            `when`("handled") {
                service.handleCveLatest(event = createCveLatestRequestEvent())

                then("the DM lists the recent summaries across the subscribed topics") {
                    val markdown = staged.markdown()
                    markdown shouldContain "*Kotlin* — *v2.3.0*"
                    markdown shouldContain "New release."
                    markdown shouldContain "*Java CVE* — *CVE-2026-1111*"
                }
            }
        }

        given("a subscribed caller whose topics have no summarized events yet") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = TEST_USER_ID) } returns
                listOf(createCveTopic(id = 11L, topicKey = "kotlin", displayName = "Kotlin"))
            every { eventRepository.findRecentDoneEvents(topicIds = listOf(11L), limit = 5) } returns emptyList()
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = mockk(),
                    eventRepository = eventRepository,
                    stager = stagerCapturing(stagedMessage = staged),
                )

            `when`("handled") {
                service.handleCveLatest(event = createCveLatestRequestEvent())

                then("the DM says there is nothing yet") {
                    staged.markdown() shouldContain "No recent CVE updates"
                }
            }
        }

        given("five verbose events whose summaries pass the per-event cap") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = TEST_USER_ID) } returns
                listOf(createCveTopic(id = 11L, topicKey = "kotlin", displayName = "Kotlin"))
            every { eventRepository.findRecentDoneEvents(topicIds = listOf(11L), limit = 5) } returns
                List(5) { index ->
                    createCveRecentEvent(
                        topicDisplayName = "Kotlin",
                        title = "Advisory $index ${"t".repeat(500)}",
                        aiSummary = "s".repeat(700),
                    )
                }
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    subscriptionRepository = subscriptionRepository,
                    topicRepository = mockk(),
                    eventRepository = eventRepository,
                    stager = stagerCapturing(stagedMessage = staged),
                )

            `when`("handled") {
                service.handleCveLatest(event = createCveLatestRequestEvent())

                then("the aggregate body stays under the Slack section limit") {
                    val markdown = staged.markdown()
                    markdown.length shouldBe 2900 + "\n…(truncated)".length
                    markdown shouldContain "…(truncated)"
                }
            }
        }

        given("a topic argument that matches no active topic") {
            val topicRepository = mockk<CveTopicRepository>()
            every { topicRepository.findActiveTopics() } returns
                listOf(createCveTopic(id = 11L, topicKey = "kotlin", displayName = "Kotlin"))
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    subscriptionRepository = mockk(),
                    topicRepository = topicRepository,
                    eventRepository = mockk(),
                    stager = stagerCapturing(stagedMessage = staged),
                )

            `when`("handled with an unknown key") {
                service.handleCveLatest(event = createCveLatestRequestEvent(topicKey = "ghost"))

                then("the DM reports the topic as unavailable") {
                    staged.markdown() shouldContain "`ghost` is not available"
                }
            }
        }

        given("a topic argument matching an active topic") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            every { topicRepository.findActiveTopics() } returns
                listOf(createCveTopic(id = 11L, topicKey = "kotlin", displayName = "Kotlin"))
            every { eventRepository.findRecentDoneEvents(topicIds = listOf(11L), limit = 5) } returns
                listOf(createCveRecentEvent(topicDisplayName = "Kotlin", title = "v2.3.0", aiSummary = "New release."))
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    subscriptionRepository = mockk(),
                    topicRepository = topicRepository,
                    eventRepository = eventRepository,
                    stager = stagerCapturing(stagedMessage = staged),
                )

            `when`("handled with the topic key") {
                service.handleCveLatest(event = createCveLatestRequestEvent(topicKey = "kotlin"))

                then("the read is scoped to that topic only") {
                    staged.markdown() shouldContain "*Kotlin* — *v2.3.0*"
                    verify(exactly = 1) { eventRepository.findRecentDoneEvents(topicIds = listOf(11L), limit = 5) }
                }
            }
        }
    })
