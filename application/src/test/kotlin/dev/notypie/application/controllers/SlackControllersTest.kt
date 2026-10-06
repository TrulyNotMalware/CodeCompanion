package dev.notypie.application.controllers

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.application.exception.ControllerAdvice
import dev.notypie.application.service.cve.query.CveQuerySlashService
import dev.notypie.application.service.cve.subscription.CveSubscriptionSlashService
import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.application.service.standup.StandupSlashService
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.SlackViewOpenDispatcher
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.createOpenViewEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.util.LinkedMultiValueMap

class SlackControllersTest :
    BehaviorSpec({
        val eventHandler = mockk<AppMentionEventHandler>()
        val meetingService = mockk<MeetingService>(relaxed = true)
        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    SlackEventController(eventHandler = eventHandler, interactionHandler = mockk<InteractionHandler>()),
                    SlashCommandController(
                        meetingService = meetingService,
                        standupSlashService = mockk(relaxed = true),
                        cveSubscriptionSlashService = mockk(relaxed = true),
                        cveQuerySlashService = mockk(relaxed = true),
                    ),
                ).setControllerAdvice(ControllerAdvice())
                .build()

        given("an app_mention whose command failed with an internal exception") {
            every { eventHandler.handleEvent(headers = any(), payload = any()) } returns
                CommandOutput.fail(
                    basicInfo = createCommandBasicInfo(),
                    commandDetailType = CommandDetailType.ERROR_RESPONSE,
                    reason = "java.lang.IllegalStateException: jdbc:mariadb://internal-host/db refused",
                )

            `when`("Slack delivers the event") {
                val response =
                    mockMvc
                        .perform(
                            post("/api/slack/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"type":"event_callback","event":{"type":"app_mention"}}"""),
                        ).andReturn()
                        .response

                then("the ack is an empty 200, so the exception text never reaches Slack") {
                    response.status shouldBe 200
                    response.contentAsString shouldBe ""
                }
            }
        }

        given("app_mentions the handler ignored or failed") {
            fun deliverAndCaptureWarnings(output: CommandOutput): List<String> {
                every { eventHandler.handleEvent(headers = any(), payload = any()) } returns output
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                val logger = LoggerFactory.getLogger(SlackEventController::class.java.packageName) as Logger
                logger.addAppender(appender)
                try {
                    mockMvc.perform(
                        post("/api/slack/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"type":"event_callback","event":{"type":"app_mention"}}"""),
                    )
                } finally {
                    logger.detachAppender(appender)
                }
                return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            }

            `when`("the mention had nothing to do, as one from the bot itself or a workflow") {
                val warnings = deliverAndCaptureWarnings(output = CommandOutput.empty())

                then("no warning is logged for it") {
                    warnings shouldBe emptyList()
                }
            }

            `when`("the mention's command failed") {
                val warnings =
                    deliverAndCaptureWarnings(
                        output =
                            CommandOutput.fail(
                                basicInfo = createCommandBasicInfo(),
                                commandDetailType = CommandDetailType.ERROR_RESPONSE,
                                reason = "boom",
                            ),
                    )

                then("it is still logged as a warning") {
                    warnings shouldBe listOf("app_mention command failed: boom")
                }
            }
        }

        given("Slack's URL verification request") {
            `when`("it is delivered with extra fields") {
                val response =
                    mockMvc
                        .perform(
                            post("/api/slack/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    """{"type":"url_verification","challenge":"abc123","token":"verification-token"}""",
                                ),
                        ).andReturn()
                        .response

                then("only the challenge is echoed") {
                    response.status shouldBe 200
                    response.contentAsString shouldBe """{"challenge":"abc123"}"""
                }
            }
        }

        given("a /meetup slash command") {
            val form =
                LinkedMultiValueMap<String, String>().apply {
                    createSlashCommandForm().forEach { (k, v) ->
                        add(k, v)
                    }
                }

            `when`("Slack posts it as a form and accepts only JSON") {
                val response =
                    mockMvc
                        .perform(
                            post("/api/slash/meet")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .accept(MediaType.APPLICATION_JSON)
                                .params(form),
                        ).andReturn()
                        .response

                then("the endpoint is chosen by the request's form content type, not by the Accept header") {
                    response.status shouldBe 200
                    verify(exactly = 1) {
                        meetingService.handleMeeting(headers = any(), payload = any(), commandData = any())
                    }
                }
            }

            `when`("it is posted as JSON instead of a form") {
                val response =
                    mockMvc
                        .perform(
                            post("/api/slash/meet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        ).andReturn()
                        .response

                then("it is rejected as an unsupported media type") {
                    response.status shouldBe 415
                }
            }
        }

        given("slash commands whose service opens a modal") {
            val messageDispatcher = mockk<MessageDispatcher>()
            var opens = 0
            every { messageDispatcher.dispatchImmediate(event = any()) } answers {
                opens++
                CommandOutput.empty()
            }
            val viewOpenDispatcher = SlackViewOpenDispatcher(messageDispatcher = messageDispatcher)
            var opensInsideService = -1
            val openModal: () -> Unit = {
                viewOpenDispatcher.listenOpenViewEvent(event = createOpenViewEvent())
                opensInsideService = opens
            }
            val meeting = mockk<MeetingService>()
            every { meeting.handleMeeting(headers = any(), payload = any(), commandData = any()) } answers
                { openModal() }
            val standup = mockk<StandupSlashService>()
            every { standup.handleStandup(headers = any(), payload = any(), commandData = any()) } answers
                { openModal() }
            val subscription = mockk<CveSubscriptionSlashService>()
            every { subscription.handleSubscribe(headers = any(), payload = any(), commandData = any()) } answers {
                openModal()
            }
            every { subscription.handleUnsubscribe(headers = any(), payload = any(), commandData = any()) } answers {
                openModal()
            }
            every { subscription.handleSubscriptions(headers = any(), payload = any(), commandData = any()) } answers {
                openModal()
            }
            val query = mockk<CveQuerySlashService>()
            every { query.handleLatest(headers = any(), payload = any(), commandData = any()) } answers { openModal() }
            val slashMvc =
                MockMvcBuilders
                    .standaloneSetup(
                        SlashCommandController(
                            meetingService = meeting,
                            standupSlashService = standup,
                            cveSubscriptionSlashService = subscription,
                            cveQuerySlashService = query,
                        ),
                    ).build()
            val form =
                LinkedMultiValueMap<String, String>().apply {
                    createSlashCommandForm().forEach { (k, v) ->
                        add(k, v)
                    }
                }

            listOf("/meet", "/standup", "/subscribe", "/unsubscribe", "/subscriptions", "/latest").forEach { path ->
                `when`("$path runs its service, which publishes a views.open") {
                    opens = 0
                    opensInsideService = -1
                    val status =
                        slashMvc
                            .perform(
                                post("/api/slash$path")
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .params(form),
                            ).andReturn()
                            .response.status

                    then("the modal opens once, after the service (and its transaction) has returned") {
                        status shouldBe 200
                        opensInsideService shouldBe 0
                        opens shouldBe 1
                    }
                }
            }
        }
    })
