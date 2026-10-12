package dev.notypie.application.socket

import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.controllers.createSlashCommandForm
import dev.notypie.application.service.agent.AskSlashService
import dev.notypie.application.service.calendar.CalendarSlashService
import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.standup.StandupSlashService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.util.LinkedMultiValueMap

class SocketModeReceiverTest :
    BehaviorSpec({
        val interactionHandler = mockk<InteractionHandler>()
        val meetingService = mockk<MeetingService>(relaxed = true)
        val standupSlashService = mockk<StandupSlashService>(relaxed = true)
        val calendarSlashService = mockk<CalendarSlashService>(relaxed = true)
        val askSlashService = mockk<AskSlashService>(relaxed = true)

        fun receiverWith(appConfig: AppConfig): SocketModeReceiver =
            SocketModeReceiver(
                appConfig = appConfig,
                meetingService = meetingService,
                standupSlashService = standupSlashService,
                cveSubscriptionSlashService = mockk(),
                cveQuerySlashService = mockk(),
                calendarSlashService = calendarSlashService,
                askSlashService = askSlashService,
                interactionHandler = interactionHandler,
                appMentionEventHandler = mockk(),
            )

        val receiver = receiverWith(appConfig = AppConfig())

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

        given("a slash command delivered over Socket Mode") {
            `when`("its command is the default calendar command name") {
                val (payload, commandData) =
                    parseRequestBodyData(
                        headers = LinkedMultiValueMap(),
                        data = createSlashCommandForm(command = "/calendar", text = "status"),
                    )
                receiver.dispatchSlash(payload = payload, commandData = commandData)

                then("only the calendar slash service handles it") {
                    verify(exactly = 1) {
                        calendarSlashService.handleCalendar(
                            headers = any(),
                            payload = payload,
                            commandData = commandData,
                        )
                    }
                    verify(exactly = 0) {
                        meetingService.handleMeeting(headers = any(), payload = any(), commandData = any())
                    }
                    verify(exactly = 0) {
                        standupSlashService.handleStandup(headers = any(), payload = any(), commandData = any())
                    }
                }
            }

            `when`("its command is the default ask command name") {
                val (payload, commandData) =
                    parseRequestBodyData(
                        headers = LinkedMultiValueMap(),
                        data = createSlashCommandForm(command = "/ask", text = "why is CI red?"),
                    )
                receiver.dispatchSlash(payload = payload, commandData = commandData)

                then("only the ask slash service handles it") {
                    verify(exactly = 1) {
                        askSlashService.handleAsk(
                            headers = any(),
                            payload = payload,
                            commandData = commandData,
                        )
                    }
                    verify(exactly = 0) {
                        calendarSlashService.handleCalendar(
                            headers = any(),
                            payload = payload,
                            commandData = commandData,
                        )
                    }
                    verify(exactly = 0) {
                        meetingService.handleMeeting(
                            headers = any(),
                            payload = payload,
                            commandData = commandData,
                        )
                    }
                }
            }

            `when`("the ask command is configured under a custom name and that name arrives") {
                val (payload, commandData) =
                    parseRequestBodyData(
                        headers = LinkedMultiValueMap(),
                        data = createSlashCommandForm(command = "/question", text = "why is CI red?"),
                    )
                val customConfig = AppConfig(socket = AppConfig.Socket(askCommand = "/question"))
                receiverWith(appConfig = customConfig).dispatchSlash(payload = payload, commandData = commandData)

                then("the custom name routes to the ask slash service") {
                    verify(exactly = 1) {
                        askSlashService.handleAsk(
                            headers = any(),
                            payload = payload,
                            commandData = commandData,
                        )
                    }
                }
            }

            `when`("its command matches no configured name") {
                val (payload, commandData) =
                    parseRequestBodyData(
                        headers = LinkedMultiValueMap(),
                        data = createSlashCommandForm(command = "/unmapped", text = "anything"),
                    )
                receiver.dispatchSlash(payload = payload, commandData = commandData)

                then("no slash service handles it") {
                    verify(exactly = 0) {
                        askSlashService.handleAsk(headers = any(), payload = payload, commandData = any())
                    }
                    verify(exactly = 0) {
                        calendarSlashService.handleCalendar(headers = any(), payload = payload, commandData = any())
                    }
                    verify(exactly = 0) {
                        meetingService.handleMeeting(headers = any(), payload = payload, commandData = any())
                    }
                    verify(exactly = 0) {
                        standupSlashService.handleStandup(headers = any(), payload = payload, commandData = any())
                    }
                }
            }
        }
    })
