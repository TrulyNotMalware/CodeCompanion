package dev.notypie.application.service.cve.subscription

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import dev.notypie.repository.cve.CveSubscriptionRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.schema.createCveTopic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

class CveSubscriptionSlashServiceImplTest :
    BehaviorSpec({
        val noHeaders: MultiValueMap<String, String> = LinkedMultiValueMap()
        val payload = mockk<SlashCommandRequestBody>(relaxed = true)
        val commandData =
            InboundCommand(
                appId = "A123",
                appToken = "t",
                actorId = "U_SUB",
                actorName = "sub",
                channel = "C_CMD",
                channelName = "cmd",
                kind = InboundKind.SLASH,
                payload = SlashInvocation(trigger = TriggerHandle(raw = "trig")),
            )

        fun serviceWith(
            enabled: Boolean,
            topicRepository: CveTopicRepository,
            subscriptionRepository: CveSubscriptionRepository,
            commandExecutor: CommandExecutor,
            stager: OutboundMessageStager,
            publisher: EventPublisher = mockk(relaxed = true),
        ): CveSubscriptionSlashServiceImpl =
            CveSubscriptionSlashServiceImpl(
                appConfig = AppConfig(cve = AppConfig.Cve(enabled = enabled)),
                cveTopicRepository = topicRepository,
                cveSubscriptionRepository = subscriptionRepository,
                commandExecutor = commandExecutor,
                outboundStager = stager,
                eventPublisher = publisher,
            )

        fun stagerCapturing(staged: CapturingSlot<OutboundMessage>): OutboundMessageStager {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(staged), basicInfo = any()) } returns
                mockk<CommandEvent<EventPayload>>(relaxed = true)
            return stager
        }

        given("the feature is disabled") {
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val stager = mockk<OutboundMessageStager>(relaxed = true)
            val service =
                serviceWith(
                    enabled = false,
                    topicRepository = topicRepository,
                    subscriptionRepository = mockk(relaxed = true),
                    commandExecutor = commandExecutor,
                    stager = stager,
                )

            `when`("/subscribe is received") {
                service.handleSubscribe(headers = noHeaders, payload = payload, commandData = commandData)

                then("nothing is executed, queried, or staged") {
                    verify(exactly = 0) { commandExecutor.execute<SubCommandDefinition>(command = any()) }
                    verify(exactly = 0) { stager.stage(message = any(), basicInfo = any()) }
                    verify(exactly = 0) { topicRepository.findActiveTopics() }
                }
            }
        }

        given("subscribe with active topics available") {
            val topicRepository = mockk<CveTopicRepository>()
            every { topicRepository.findActiveTopics() } returns
                listOf(createCveTopic(id = 1L, topicKey = "kotlin", displayName = "Kotlin"))
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val stager = mockk<OutboundMessageStager>(relaxed = true)
            val service =
                serviceWith(
                    enabled = true,
                    topicRepository = topicRepository,
                    subscriptionRepository = mockk(relaxed = true),
                    commandExecutor = commandExecutor,
                    stager = stager,
                )

            `when`("/subscribe is received") {
                service.handleSubscribe(headers = noHeaders, payload = payload, commandData = commandData)

                then("the modal command is executed and no info ephemeral is staged") {
                    verify(exactly = 1) { commandExecutor.execute<SubCommandDefinition>(command = any()) }
                    verify(exactly = 0) { stager.stage(message = any(), basicInfo = any()) }
                }
            }
        }

        given("subscribe with no active topics") {
            val topicRepository = mockk<CveTopicRepository>()
            every { topicRepository.findActiveTopics() } returns emptyList()
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val staged = slot<OutboundMessage>()
            val publisher = mockk<EventPublisher>(relaxed = true)
            val service =
                serviceWith(
                    enabled = true,
                    topicRepository = topicRepository,
                    subscriptionRepository = mockk(relaxed = true),
                    commandExecutor = commandExecutor,
                    stager = stagerCapturing(staged = staged),
                    publisher = publisher,
                )

            `when`("/subscribe is received") {
                service.handleSubscribe(headers = noHeaders, payload = payload, commandData = commandData)

                then("an informative ephemeral is staged and published instead of opening a modal") {
                    verify(exactly = 0) { commandExecutor.execute<SubCommandDefinition>(command = any()) }
                    val ephemeral = staged.captured.shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldContain
                        "no CVE topics available"
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }

        given("unsubscribe with no current subscriptions") {
            val subscriptionRepository = mockk<CveSubscriptionRepository>()
            every { subscriptionRepository.findSubscribedTopics(userId = "U_SUB") } returns emptyList()
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val staged = slot<OutboundMessage>()
            val service =
                serviceWith(
                    enabled = true,
                    topicRepository = mockk(relaxed = true),
                    subscriptionRepository = subscriptionRepository,
                    commandExecutor = commandExecutor,
                    stager = stagerCapturing(staged = staged),
                )

            `when`("/unsubscribe is received") {
                service.handleUnsubscribe(headers = noHeaders, payload = payload, commandData = commandData)

                then("an informative ephemeral is staged instead of opening a modal") {
                    verify(exactly = 0) { commandExecutor.execute<SubCommandDefinition>(command = any()) }
                    val ephemeral = staged.captured.shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldContain
                        "no CVE topic subscriptions to remove"
                }
            }
        }

        given("subscriptions is received when enabled") {
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val stager = mockk<OutboundMessageStager>(relaxed = true)
            val service =
                serviceWith(
                    enabled = true,
                    topicRepository = mockk(relaxed = true),
                    subscriptionRepository = mockk(relaxed = true),
                    commandExecutor = commandExecutor,
                    stager = stager,
                )

            `when`("/subscriptions is received") {
                service.handleSubscriptions(headers = noHeaders, payload = payload, commandData = commandData)

                then("the list command is executed without staging a modal") {
                    verify(exactly = 1) { commandExecutor.execute<SubCommandDefinition>(command = any()) }
                    verify(exactly = 0) { stager.stage(message = any(), basicInfo = any()) }
                }
            }
        }
    })
