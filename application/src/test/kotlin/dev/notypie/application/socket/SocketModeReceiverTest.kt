package dev.notypie.application.socket

import ch.qos.logback.classic.Level
import com.slack.api.socket_mode.SocketModeClient
import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.controllers.createSlashCommandForm
import dev.notypie.application.service.agent.AskSlashService
import dev.notypie.application.service.calendar.CalendarSlashService
import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.application.service.mention.createAppMentionPayload
import dev.notypie.application.service.standup.StandupSlashService
import dev.notypie.common.jsonMapper
import dev.notypie.logging.captureLogs
import dev.notypie.logging.messagesAt
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.util.ReflectionTestUtils
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

        given("slash and event envelopes whose handlers throw") {
            val failingCalendar = mockk<CalendarSlashService>()
            val mentionHandler = mockk<AppMentionEventHandler>()
            val failingReceiver =
                SocketModeReceiver(
                    appConfig = AppConfig(),
                    meetingService = mockk(),
                    standupSlashService = mockk(),
                    cveSubscriptionSlashService = mockk(),
                    cveQuerySlashService = mockk(),
                    calendarSlashService = failingCalendar,
                    askSlashService = mockk(),
                    interactionHandler = mockk(),
                    appMentionEventHandler = mentionHandler,
                )
            val slashJson =
                jsonMapper.writeValueAsString(
                    createSlashCommandForm(command = "/calendar", text = "status"),
                )
            val mentionJson = jsonMapper.writeValueAsString(createAppMentionPayload())
            val loggerName = SocketModeReceiver::class.java.packageName

            `when`("the slash service throws an Exception") {
                every { failingCalendar.handleCalendar(headers = any(), payload = any(), commandData = any()) } throws
                    IllegalStateException("db down")

                val errors =
                    captureLogs(loggerName = loggerName) {
                        shouldNotThrowAny { failingReceiver.handleSlash(payloadJson = slashJson) }
                    }.messagesAt(level = Level.ERROR)

                then("the service ran, the failure is logged and nothing escapes the listener") {
                    verify(exactly = 1) {
                        failingCalendar.handleCalendar(headers = any(), payload = any(), commandData = any())
                    }
                    errors shouldBe listOf("Socket Mode slash-command handling failed.")
                }
            }

            `when`("the slash service throws an Error") {
                val thrown = StackOverflowError("deep")
                every { failingCalendar.handleCalendar(headers = any(), payload = any(), commandData = any()) } throws
                    thrown

                then("the same Error propagates") {
                    shouldThrow<StackOverflowError> {
                        failingReceiver.handleSlash(payloadJson = slashJson)
                    } shouldBeSameInstanceAs thrown
                }
            }

            `when`("the mention handler throws an Exception") {
                every { mentionHandler.handleEvent(headers = any(), payload = any()) } throws
                    IllegalStateException("db down")

                val errors =
                    captureLogs(loggerName = loggerName) {
                        shouldNotThrowAny { failingReceiver.handleEvent(payloadJson = mentionJson) }
                    }.messagesAt(level = Level.ERROR)

                then("the handler ran, the failure is logged and nothing escapes the listener") {
                    verify(exactly = 1) { mentionHandler.handleEvent(headers = any(), payload = any()) }
                    errors shouldBe listOf("Socket Mode event handling failed.")
                }
            }

            `when`("the mention handler throws an Error") {
                val thrown = StackOverflowError("deep")
                every { mentionHandler.handleEvent(headers = any(), payload = any()) } throws thrown

                then("the same Error propagates") {
                    shouldThrow<StackOverflowError> {
                        failingReceiver.handleEvent(payloadJson = mentionJson)
                    } shouldBeSameInstanceAs thrown
                }
            }

            `when`("an envelope listener's work throws an Error") {
                val thrown = StackOverflowError("deep")
                val (escaped, logs) =
                    captureLogs(loggerName = loggerName) {
                        shouldThrow<StackOverflowError> {
                            failingReceiver.logErrorThenRethrow(kind = "slash", envelopeId = "env-1") { throw thrown }
                        }
                    }

                then("the Error is logged with the envelope before the SDK loop drops it, then rethrown as itself") {
                    escaped shouldBeSameInstanceAs thrown
                    logs.messagesAt(level = Level.ERROR).single() shouldContain "slash envelope envelopeId=env-1"
                }
            }

            `when`("an envelope listener's work throws an Exception") {
                val thrown = IllegalStateException("ack failed")
                val (escaped, logs) =
                    captureLogs(loggerName = loggerName) {
                        shouldThrow<IllegalStateException> {
                            failingReceiver.logErrorThenRethrow(kind = "event", envelopeId = "env-2") { throw thrown }
                        }
                    }

                then("it passes through untouched for the SDK loop, which logs and survives an Exception") {
                    escaped shouldBeSameInstanceAs thrown
                    logs.messagesAt(level = Level.ERROR) shouldBe emptyList()
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

        given("a connected receiver whose client fails to disconnect") {
            val loggerName = SocketModeReceiver::class.java.packageName

            fun connectedReceiver(client: SocketModeClient): SocketModeReceiver =
                receiverWith(appConfig = AppConfig()).also { ReflectionTestUtils.setField(it, "client", client) }

            `when`("disconnect throws an Error") {
                val thrown = StackOverflowError("deep")
                val stopping = connectedReceiver(client = mockk { every { disconnect() } throws thrown })

                then("the same Error propagates and the receiver still reports itself stopped") {
                    shouldThrow<StackOverflowError> { stopping.stop() } shouldBeSameInstanceAs thrown
                    stopping.isRunning() shouldBe false
                }
            }

            `when`("disconnect throws an Exception") {
                val client = mockk<SocketModeClient> { every { disconnect() } throws IllegalStateException("gone") }
                val stopping = connectedReceiver(client = client)
                val (_, logs) = captureLogs(loggerName = loggerName) { shouldNotThrowAny { stopping.stop() } }

                then("it is logged at WARN and the receiver reports itself stopped") {
                    logs.messagesAt(level = Level.WARN).single() shouldContain "Failed to disconnect Socket Mode client"
                    stopping.isRunning() shouldBe false
                }
            }
        }
    })
