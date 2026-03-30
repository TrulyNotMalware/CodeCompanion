package dev.notypie.impl.command

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.templates.SlackTemplateBuilder
import dev.notypie.templates.dto.LayoutBlocks
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class SlackApiEventConstructorTest :
    BehaviorSpec({
        val templateBuilder = mockk<SlackTemplateBuilder>()
        val emptyLayout = LayoutBlocks(template = emptyList())

        val constructor =
            SlackApiEventConstructor(
                botToken = TEST_BOT_TOKEN,
                templateBuilder = templateBuilder,
            )

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
                        commandType = CommandType.SIMPLE,
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
                    val payload = result.payload as PostEventPayloadContents
                    payload.messageType shouldBe MessageType.CHANNEL_ALERT
                    payload.channel shouldBe commandBasicInfo.channel
                    payload.publisherId shouldBe commandBasicInfo.publisherId
                    payload.apiAppId shouldBe commandBasicInfo.appId
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
                        textMessage = "Ephemeral Message",
                        commandBasicInfo = commandBasicInfo,
                        commandType = CommandType.SIMPLE,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    )

                then("payload is PostEventPayloadContents with EPHEMERAL_MESSAGE messageType") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload as PostEventPayloadContents
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
                }

                then("idempotencyKey matches commandBasicInfo") {
                    result.idempotencyKey shouldBe idempotencyKey
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
                        textMessage = "DM Message",
                        commandBasicInfo = commandBasicInfo,
                        commandType = CommandType.SIMPLE,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        targetUserId = targetUserId,
                    )

                then("payload channel and userId are set to targetUserId") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload as PostEventPayloadContents
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
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
                        commandType = CommandType.SIMPLE,
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
                    val payload = result.payload as PostEventPayloadContents
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
                        commandType = CommandType.RESPONSE,
                        commandDetailType = CommandDetailType.REPLACE_TEXT,
                    )

                then("payload is ActionEventPayloadContents with matching responseUrl") {
                    result.payload.shouldBeInstanceOf<ActionEventPayloadContents>()
                    val payload = result.payload as ActionEventPayloadContents
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
                        commandType = CommandType.SIMPLE,
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                        approvalContents = null,
                    )

                then("calls templateBuilder with default ApprovalContents") {
                    verify(exactly = 1) {
                        templateBuilder.requestMeetingFormTemplate(approvalContents = any())
                    }
                }

                then("payload is PostEventPayloadContents with EPHEMERAL_MESSAGE messageType") {
                    result.payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    val payload = result.payload as PostEventPayloadContents
                    payload.messageType shouldBe MessageType.EPHEMERAL_MESSAGE
                }
            }

            `when`("called with explicit approvalContents") {
                every {
                    templateBuilder.requestMeetingFormTemplate(approvalContents = any())
                } returns emptyLayout

                val approvalContents =
                    ApprovalContents(
                        idempotencyKey = idempotencyKey,
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                        reason = "Custom Reason",
                        publisherId = commandBasicInfo.publisherId,
                    )
                val result =
                    constructor.requestMeetingFormRequest(
                        commandBasicInfo = commandBasicInfo,
                        commandType = CommandType.SIMPLE,
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
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
