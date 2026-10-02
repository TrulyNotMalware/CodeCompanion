package dev.notypie.application.controllers

import dev.notypie.application.exception.ControllerAdvice
import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    })
