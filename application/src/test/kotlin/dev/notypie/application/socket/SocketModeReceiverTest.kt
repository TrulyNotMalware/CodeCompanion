package dev.notypie.application.socket

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.interaction.InteractionHandler
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class SocketModeReceiverTest :
    BehaviorSpec({
        val interactionHandler = mockk<InteractionHandler>()
        val receiver =
            SocketModeReceiver(
                appConfig = AppConfig(),
                meetingService = mockk(),
                standupSlashService = mockk(),
                cveSubscriptionSlashService = mockk(),
                cveQuerySlashService = mockk(),
                interactionHandler = interactionHandler,
                appMentionEventHandler = mockk(),
            )

        fun acksFor(payloadJson: String): List<String?> {
            val acks = mutableListOf<String?>()
            receiver.handleInteractive(payloadJson = payloadJson) { ackBody -> acks.add(ackBody) }
            return acks
        }

        given("an interactive envelope") {
            `when`("the handler succeeds with the normal empty ack") {
                every { interactionHandler.handleInteraction(headers = any(), payload = "ok") } returns null

                then("the envelope is acked once without a body") {
                    acksFor(payloadJson = "ok") shouldBe listOf(null)
                }
            }

            `when`("the handler returns a response_action body") {
                val body = """{"response_action":"errors","errors":{"block":"required"}}"""
                every { interactionHandler.handleInteraction(headers = any(), payload = "errors") } returns body

                then("the body rides the ack") {
                    acksFor(payloadJson = "errors") shouldBe listOf(body)
                }
            }

            `when`("the handler throws, e.g. because its transaction rolled back") {
                every { interactionHandler.handleInteraction(headers = any(), payload = "boom") } throws
                    IllegalStateException("db down")

                then("no ack is sent, so Slack reports the failure instead of a silent success") {
                    acksFor(payloadJson = "boom") shouldBe emptyList()
                }
            }
        }
    })
