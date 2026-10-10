package dev.notypie.impl.command

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.command.createApprovalContents
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.event.ActionEventPayloadContents
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.PostEventPayloadContents
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate

class SlackApiEventConstructorTest :
    BehaviorSpec({
        val templateBuilder = mockk<SlackTemplateBuilder>()
        val emptyLayout = LayoutBlocks(template = emptyList())

        val constructor =
            SlackApiEventConstructor(
                botToken = TEST_BOT_TOKEN,
                templateBuilder = templateBuilder,
            )

        beforeContainer { testCase -> if (testCase.parent == null) clearMocks(templateBuilder) }

        val commandBasicInfo = createCommandBasicInfo()
        val idempotencyKey = commandBasicInfo.idempotencyKey

        given("simpleTextRequest") {
            `when`("called with valid parameters") {
                every {
                    templateBuilder.simpleTextResponseTemplate(
                        headLineText = any(),
                        body = any(),
                        isMarkDown = any(),
                    )
                } returns emptyLayout

                val result =
                    constructor.simpleTextRequest(
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        headLineText = "Test Title",
                        commandBasicInfo = commandBasicInfo,
                        simpleString = "Hello World",
                    )

                then("calls templateBuilder.simpleTextResponseTemplate with given arguments") {
                    verify(exactly = 1) {
                        templateBuilder.simpleTextResponseTemplate(
                            headLineText = "Test Title",
                            body = "Hello World",
                            isMarkDown = true,
                        )
                    }
                }

                then("returns SendSlackMessageEvent with matching idempotencyKey and type") {
                    result shouldNotBe null
                    result.idempotencyKey shouldBe idempotencyKey
                    result.type shouldBe CommandDetailType.SIMPLE_TEXT
                }

                then("payload is PostEventPayloadContents with CHANNEL_ALERT messageType") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload
                    payload.messageType shouldBe MessageType.CHANNEL_ALERT
                    payload.channel shouldBe commandBasicInfo.channel
                    payload.publisherId shouldBe commandBasicInfo.publisherId
                    payload.apiAppId shouldBe commandBasicInfo.appId
                }
            }
        }

        given("standupSummaryRequest") {
            `when`("a summary part is built") {
                every {
                    templateBuilder.standupSummaryTemplate(
                        routineName = any(),
                        sessionDate = any(),
                        members = any(),
                        answers = any(),
                        questions = any(),
                    )
                } returns emptyLayout

                val result =
                    constructor.standupSummaryRequest(
                        commandBasicInfo = commandBasicInfo,
                        routineName = "Daily Standup",
                        sessionDate = LocalDate.of(2026, 5, 1),
                        members = emptyList(),
                        answers = emptyList(),
                        questions = listOf("What did you do yesterday?"),
                    )

                then("the payload carries STANDUP_SUMMARY, which the success event hands the marker listener") {
                    result.type shouldBe CommandDetailType.STANDUP_SUMMARY
                    result.payload.commandDetailType shouldBe CommandDetailType.STANDUP_SUMMARY
                }
            }
        }

        given("simpleEphemeralTextRequest") {
            `when`("called without targetUserId") {
                every {
                    templateBuilder.onlyTextTemplate(
                        message = any(),
                        isMarkDown = any(),
                    )
                } returns emptyLayout

                val result =
                    constructor.simpleEphemeralTextRequest(
                        headLineText = null,
                        textMessage = "Ephemeral Message",
                        commandBasicInfo = commandBasicInfo,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    )

                then("payload is PostEventPayloadContents with EPHEMERAL_MESSAGE messageType") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
                    payload.body["user"] shouldBe commandBasicInfo.publisherId
                }

                then("idempotencyKey matches commandBasicInfo") {
                    result.idempotencyKey shouldBe idempotencyKey
                }

                then("a null headline renders the body alone through onlyTextTemplate") {
                    verify(exactly = 1) {
                        templateBuilder.onlyTextTemplate(message = "Ephemeral Message", isMarkDown = true)
                    }
                    verify(exactly = 0) {
                        templateBuilder.simpleTextResponseTemplate(
                            headLineText = any(),
                            body = "Ephemeral Message",
                            isMarkDown = any(),
                        )
                    }
                }
            }

            `when`("called with targetUserId") {
                every {
                    templateBuilder.onlyTextTemplate(
                        message = any(),
                        isMarkDown = any(),
                    )
                } returns emptyLayout

                val targetUserId = "U999999"
                val result =
                    constructor.simpleEphemeralTextRequest(
                        headLineText = null,
                        textMessage = "DM Message",
                        commandBasicInfo = commandBasicInfo,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        targetUserId = targetUserId,
                    )

                then("ephemeral posts into commandBasicInfo.channel and only `user` targets targetUserId") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
                    payload.body["channel"] shouldBe commandBasicInfo.channel
                    payload.body["user"] shouldBe targetUserId
                }
            }

            `when`("called with a headline") {
                every {
                    templateBuilder.simpleTextResponseTemplate(
                        headLineText = any(),
                        body = any(),
                        isMarkDown = any(),
                    )
                } returns emptyLayout

                val result =
                    constructor.simpleEphemeralTextRequest(
                        headLineText = "Report Title",
                        textMessage = "Report Body",
                        commandBasicInfo = commandBasicInfo,
                        commandDetailType = CommandDetailType.STATUS_REPORT,
                    )

                then("the headline renders through simpleTextResponseTemplate, as simpleTextRequest does") {
                    verify(exactly = 1) {
                        templateBuilder.simpleTextResponseTemplate(
                            headLineText = "Report Title",
                            body = "Report Body",
                            isMarkDown = true,
                        )
                    }
                    verify(exactly = 0) {
                        templateBuilder.onlyTextTemplate(message = "Report Body", isMarkDown = any())
                    }
                }

                then("it is still an ephemeral in the command channel for the publisher") {
                    result.type shouldBe CommandDetailType.STATUS_REPORT
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
                    payload.body["channel"] shouldBe commandBasicInfo.channel
                    payload.body["user"] shouldBe commandBasicInfo.publisherId
                }
            }
        }

        given("detailErrorTextRequest") {
            `when`("called with error info") {
                every {
                    templateBuilder.errorNoticeTemplate(
                        headLineText = any(),
                        errorMessage = any(),
                        details = any(),
                    )
                } returns emptyLayout

                val result =
                    constructor.detailErrorTextRequest(
                        commandDetailType = CommandDetailType.ERROR_RESPONSE,
                        errorClassName = "IllegalArgumentException",
                        errorMessage = "Invalid input",
                        details = "detail info",
                        commandBasicInfo = commandBasicInfo,
                    )

                then("calls templateBuilder with 'Error : ClassName' as headLineText") {
                    verify(exactly = 1) {
                        templateBuilder.errorNoticeTemplate(
                            headLineText = "Error : IllegalArgumentException",
                            errorMessage = "Invalid input",
                            details = "detail info",
                        )
                    }
                }

                then("payload is PostEventPayloadContents with CHANNEL_ALERT messageType") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload
                    payload.messageType shouldBe MessageType.CHANNEL_ALERT
                }
            }
        }

        given("replaceOriginalText") {
            `when`("called with responseUrl") {
                every {
                    templateBuilder.onlyTextTemplate(
                        message = any(),
                        isMarkDown = any(),
                    )
                } returns emptyLayout

                val responseUrl = TEST_BASE_URL
                val result =
                    constructor.replaceOriginalText(
                        markdownText = "Updated text",
                        responseUrl = responseUrl,
                        commandBasicInfo = commandBasicInfo,
                        commandDetailType = CommandDetailType.REPLACE_TEXT,
                    )

                then("payload is ActionEventPayloadContents with matching responseUrl") {
                    result.payload.shouldBeInstanceOf<ActionEventPayloadContents>()
                    val payload = result.payload
                    payload.responseUrl shouldBe responseUrl
                    payload.channel shouldBe commandBasicInfo.channel
                    payload.publisherId shouldBe commandBasicInfo.publisherId
                }

                then("idempotencyKey and type match given parameters") {
                    result.idempotencyKey shouldBe idempotencyKey
                    result.type shouldBe CommandDetailType.REPLACE_TEXT
                }
            }
        }

        given("requestMeetingFormRequest") {
            `when`("called with null approvalContents") {
                every {
                    templateBuilder.requestMeetingFormTemplate(approvalContents = any())
                } returns emptyLayout

                val result =
                    constructor.requestMeetingFormRequest(
                        commandBasicInfo = commandBasicInfo,
                        commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                        approvalContents = null,
                    )

                then("calls templateBuilder with default ApprovalContents") {
                    verify(exactly = 1) {
                        templateBuilder.requestMeetingFormTemplate(approvalContents = any())
                    }
                }

                then("payload is PostEventPayloadContents with EPHEMERAL_MESSAGE messageType") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
                }
            }

            `when`("called with explicit approvalContents") {
                every {
                    templateBuilder.requestMeetingFormTemplate(approvalContents = any())
                } returns emptyLayout

                val approvalContents =
                    createApprovalContents(
                        idempotencyKey = idempotencyKey,
                        commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                        reason = "Custom Reason",
                        publisherId = commandBasicInfo.publisherId,
                    )
                val result =
                    constructor.requestMeetingFormRequest(
                        commandBasicInfo = commandBasicInfo,
                        commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                        approvalContents = approvalContents,
                    )

                then("calls templateBuilder with the provided approvalContents") {
                    verify(exactly = 1) {
                        templateBuilder.requestMeetingFormTemplate(approvalContents = approvalContents)
                    }
                }
            }
        }
    })
