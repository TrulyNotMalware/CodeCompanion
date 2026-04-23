package dev.notypie.templates

import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.MarkdownTextObject
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.Profile
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.templates.dto.TimeScheduleAlertContents
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.util.UUID

class ModalTemplateBuilderTest :
    BehaviorSpec({
        val restRequester = mockk<RestRequester>()

        val templateBuilder =
            ModalTemplateBuilder(
                modalBlockBuilder = ModalBlockBuilder(),
                restRequester = restRequester,
                slackApiToken = TEST_BOT_TOKEN,
            )

        val testIdempotencyKey = UUID.randomUUID()
        val testApprovalContents =
            ApprovalContents(
                idempotencyKey = testIdempotencyKey,
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                reason = "Test Reason",
                publisherId = TEST_USER_ID,
            )

        given("requestApprovalFormTemplate") {
            `when`("called with selection fields") {
                val selectionFields =
                    listOf(
                        SelectionContents(
                            title = "Category",
                            explanation = "Select a category",
                            placeholderText = "SELECT",
                            contents =
                                listOf(
                                    SelectBoxDetails(name = "Option A", value = "a"),
                                ),
                        ),
                    )

                val result =
                    templateBuilder.requestApprovalFormTemplate(
                        headLineText = "Test Approval",
                        selectionFields = selectionFields,
                        approvalContents = testApprovalContents,
                    )

                then("interactionStates should contain approval, reject, userSelect, and selection states") {
                    val stateTypes = result.interactionStates.map { it.type }
                    stateTypes.shouldContainAll(
                        ActionElementTypes.APPLY_BUTTON,
                        ActionElementTypes.REJECT_BUTTON,
                        ActionElementTypes.MULTI_USERS_SELECT,
                        ActionElementTypes.MULTI_STATIC_SELECT,
                    )
                }

                then("interactionStates size should be 4") {
                    result.interactionStates.size shouldBe 4
                }

                then("template should not be empty") {
                    result.template.size shouldBe 5
                }
            }
        }

        given("requestMeetingFormTemplate") {
            `when`("called with default parameters") {
                val result =
                    templateBuilder.requestMeetingFormTemplate(
                        approvalContents = testApprovalContents,
                    )

                then(
                    "interactionStates should contain checkbox, multiUser, datePicker, timePicker, and approval states",
                ) {
                    val stateTypes = result.interactionStates.map { it.type }
                    stateTypes.shouldContainAll(
                        ActionElementTypes.CHECKBOX,
                        ActionElementTypes.MULTI_USERS_SELECT,
                        ActionElementTypes.DATE_PICKER,
                        ActionElementTypes.TIME_PICKER,
                        ActionElementTypes.APPLY_BUTTON,
                        ActionElementTypes.REJECT_BUTTON,
                    )
                }

                then("template should not be empty") {
                    result.template.size shouldBe 10
                }
            }
        }

        given("timeScheduleNoticeTemplate") {
            `when`("called with valid parameters") {
                val timeScheduleInfo =
                    TimeScheduleAlertContents(
                        startTime = LocalDateTime.of(2026, 3, 31, 10, 0),
                        host = TEST_USER_ID,
                        rejectReasons = setOf("Schedule conflict", "Not available"),
                    )

                val result =
                    templateBuilder.timeScheduleNoticeTemplate(
                        timeScheduleInfo = timeScheduleInfo,
                        approvalContents = testApprovalContents,
                    )

                then("interactionStates should contain approval, reject, and radioButton states") {
                    val stateTypes = result.interactionStates.map { it.type }
                    stateTypes.shouldContainAll(
                        ActionElementTypes.APPLY_BUTTON,
                        ActionElementTypes.REJECT_BUTTON,
                        ActionElementTypes.RADIO_BUTTONS,
                    )
                }

                then("interactionStates size should be 3") {
                    result.interactionStates.size shouldBe 3
                }
            }
        }

        given("simpleScheduleNoticeTemplate") {
            `when`("called with valid parameters") {
                val timeScheduleInfo =
                    TimeScheduleInfo(
                        scheduleName = "Team Standup",
                        startTime = LocalDateTime.of(2026, 3, 31, 9, 0),
                        endTime = LocalDateTime.of(2026, 3, 31, 9, 30),
                    )

                val result =
                    templateBuilder.simpleScheduleNoticeTemplate(
                        headLineText = "Schedule Notice",
                        timeScheduleInfo = timeScheduleInfo,
                    )

                then("interactionStates should be empty") {
                    result.interactionStates shouldBe emptyList()
                }

                then("template should contain header, divider, and timeSchedule blocks") {
                    result.template.size shouldBe 3
                }
            }
        }

        given("approvalTemplate") {
            `when`("called with valid parameters") {
                val mockProfile =
                    Profile(
                        title = "",
                        phone = "",
                        skype = "",
                        realName = "Test User",
                        realNameNormalized = "Test User",
                        displayName = "testuser",
                        displayNameNormalized = "testuser",
                        fields = emptyMap(),
                        statusText = "",
                        statusEmoji = "",
                        statusExpiration = 0,
                        avatarHash = "abc123",
                        email = "test@example.com",
                        firstName = "Test",
                        lastName = "User",
                        imageSize24 = "https://example.com/img24.png",
                        imageSize32 = "https://example.com/img32.png",
                        imageSize48 = "https://example.com/img48.png",
                        imageSize72 = "https://example.com/img72.png",
                        imageSize192 = "https://example.com/img192.png",
                        imageSize512 = "https://example.com/img512.png",
                        statusTextCanonical = "",
                    )
                every {
                    restRequester.get(
                        uri = "users.profile.get?user=$TEST_USER_ID",
                        authorizationHeader = TEST_BOT_TOKEN,
                        responseType = SlackUserProfileDto::class.java,
                    )
                } returns SlackUserProfileDto(ok = true, profile = mockProfile)

                val result =
                    templateBuilder.approvalTemplate(
                        headLineText = "Approval Request",
                        approvalContents = testApprovalContents,
                        idempotencyKey = testIdempotencyKey,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    )

                then("interactionStates should contain approval and reject states") {
                    val stateTypes = result.interactionStates.map { it.type }
                    stateTypes.shouldContainAll(
                        ActionElementTypes.APPLY_BUTTON,
                        ActionElementTypes.REJECT_BUTTON,
                    )
                }

                then("interactionStates size should be 2") {
                    result.interactionStates.size shouldBe 2
                }

                then("template should contain header, divider, userThumbnail, text, and approval blocks") {
                    result.template.size shouldBe 5
                }
            }
        }

        given("onlyTextTemplate") {
            `when`("called with a simple message") {
                val result = templateBuilder.onlyTextTemplate(message = "Hello", isMarkDown = false)

                then("interactionStates should be empty") {
                    result.interactionStates shouldBe emptyList()
                }

                then("template should contain one block") {
                    result.template.size shouldBe 1
                }
            }
        }

        given("simpleTextResponseTemplate") {
            `when`("called with headline and body") {
                val result =
                    templateBuilder.simpleTextResponseTemplate(
                        headLineText = "Title",
                        body = "Body text",
                        isMarkDown = true,
                    )

                then("interactionStates should be empty") {
                    result.interactionStates shouldBe emptyList()
                }

                then("template should contain header, divider, and text blocks") {
                    result.template.size shouldBe 3
                }
            }
        }

        given("meetingListFormTemplate") {
            `when`("called with an empty meeting list") {
                val result = templateBuilder.meetingListFormTemplate(meetings = emptyList())

                then("template has header, divider, and empty-state section (3 blocks)") {
                    result.template.size shouldBe 3
                    result.template[0].shouldBeInstanceOf<HeaderBlock>()
                    result.template[1].shouldBeInstanceOf<DividerBlock>()
                    val emptySection = result.template[2].shouldBeInstanceOf<SectionBlock>()
                    val mrkdwn = emptySection.text.shouldBeInstanceOf<MarkdownTextObject>()
                    mrkdwn.text shouldContain "No upcoming meetings found"
                }

                then("interactionStates is empty") {
                    result.interactionStates shouldBe emptyList()
                }
            }

            `when`("called with a single non-canceled meeting") {
                val meetingUid = UUID.fromString("11111111-2222-3333-4444-555555555555")
                val meeting =
                    createMeetingDto(
                        meetingUid = meetingUid,
                        title = "Project sync",
                        startAt = LocalDateTime.of(2026, 4, 20, 10, 0),
                        endAt = LocalDateTime.of(2026, 4, 20, 11, 0),
                        participantIds = listOf("U1", "U2", "U3"),
                        isCanceled = false,
                    )

                val result = templateBuilder.meetingListFormTemplate(meetings = listOf(meeting))

                then("template has header + divider + single meeting section (3 blocks, no trailing divider)") {
                    result.template.size shouldBe 3
                    result.template[0].shouldBeInstanceOf<HeaderBlock>()
                    result.template[1].shouldBeInstanceOf<DividerBlock>()
                    result.template[2].shouldBeInstanceOf<SectionBlock>()
                }

                then("meeting section renders title, formatted times, participant count, and meetingUid in backticks") {
                    val section = result.template[2] as SectionBlock
                    val mrkdwn = section.text.shouldBeInstanceOf<MarkdownTextObject>()
                    mrkdwn.text shouldContain "*Project sync*"
                    mrkdwn.text shouldContain "2026-04-20 10:00"
                    mrkdwn.text shouldContain "~ 2026-04-20 11:00"
                    mrkdwn.text shouldContain "Participants: 3"
                    mrkdwn.text shouldContain "`$meetingUid`"
                }

                then("no CANCELED marker appears") {
                    val section = result.template[2] as SectionBlock
                    val mrkdwn = section.text as MarkdownTextObject
                    (mrkdwn.text.contains("CANCELED")) shouldBe false
                }
            }

            `when`("meeting has no participants") {
                val meeting =
                    createMeetingDto(
                        title = "Solo sync",
                        participantIds = emptyList(),
                    )

                val result = templateBuilder.meetingListFormTemplate(meetings = listOf(meeting))

                then("participant line renders as zero") {
                    val section = result.template[2] as SectionBlock
                    val mrkdwn = section.text.shouldBeInstanceOf<MarkdownTextObject>()
                    mrkdwn.text shouldContain "Participants: 0"
                }
            }

            `when`("called with a canceled meeting") {
                val meeting =
                    createMeetingDto(
                        title = "Standup",
                        isCanceled = true,
                    )

                val result = templateBuilder.meetingListFormTemplate(meetings = listOf(meeting))

                then("meeting section includes [CANCELED] marker") {
                    val section = result.template[2] as SectionBlock
                    val mrkdwn = section.text.shouldBeInstanceOf<MarkdownTextObject>()
                    mrkdwn.text shouldContain "*[CANCELED]*"
                }
            }

            `when`("called with multiple meetings") {
                val meetings =
                    listOf(
                        createMeetingDto(title = "First"),
                        createMeetingDto(title = "Second"),
                        createMeetingDto(title = "Third"),
                    )

                val result = templateBuilder.meetingListFormTemplate(meetings = meetings)

                then("template has header + divider + (section+divider)*2 + section (7 blocks)") {
                    // 1 header + 1 top divider + 3 sections + 2 inter-meeting dividers = 7
                    result.template.size shouldBe 7
                }

                then("dividers separate consecutive meetings but not the last") {
                    result.template[0].shouldBeInstanceOf<HeaderBlock>()
                    result.template[1].shouldBeInstanceOf<DividerBlock>()
                    result.template[2].shouldBeInstanceOf<SectionBlock>()
                    result.template[3].shouldBeInstanceOf<DividerBlock>()
                    result.template[4].shouldBeInstanceOf<SectionBlock>()
                    result.template[5].shouldBeInstanceOf<DividerBlock>()
                    result.template[6].shouldBeInstanceOf<SectionBlock>()
                }
            }

            `when`("called with more meetings than MAX_MEETINGS_PER_LIST (24)") {
                val overflowSize = ModalTemplateBuilder.MAX_MEETINGS_PER_LIST + 5
                val meetings = (1..overflowSize).map { createMeetingDto(title = "M$it") }

                val result = templateBuilder.meetingListFormTemplate(meetings = meetings)

                then("renders only the first 24 meetings plus a truncation notice") {
                    // header(1) + top divider(1) + 24 meeting sections + 23 inter-dividers
                    // + overflow-notice section(1) = 50 blocks exactly. No divider before notice.
                    val sectionCount = result.template.count { it is SectionBlock }
                    sectionCount shouldBe ModalTemplateBuilder.MAX_MEETINGS_PER_LIST + 1 // meetings + notice
                    val lastBlock = result.template.last()
                    val lastSection = lastBlock.shouldBeInstanceOf<SectionBlock>()
                    val mrkdwn = lastSection.text.shouldBeInstanceOf<MarkdownTextObject>()
                    mrkdwn.text shouldContain "Showing the first ${ModalTemplateBuilder.MAX_MEETINGS_PER_LIST}"
                    mrkdwn.text shouldContain "5 more omitted"
                }

                then("total block count must not exceed Slack's 50-block message limit") {
                    (result.template.size <= 50) shouldBe true
                }
            }

            `when`("meeting has no endAt") {
                val meeting =
                    createMeetingDto(
                        title = "Half-open",
                        startAt = LocalDateTime.of(2026, 4, 20, 10, 0),
                        endAt = null,
                    )

                val result = templateBuilder.meetingListFormTemplate(meetings = listOf(meeting))

                then("section omits the end-time suffix") {
                    val section = result.template[2] as SectionBlock
                    val mrkdwn = section.text as MarkdownTextObject
                    mrkdwn.text shouldContain "2026-04-20 10:00"
                    (mrkdwn.text.contains("~")) shouldBe false
                }
            }
        }

        given("errorNoticeTemplate") {
            `when`("called without details") {
                val result =
                    templateBuilder.errorNoticeTemplate(
                        headLineText = "Error",
                        errorMessage = "Something failed",
                        details = null,
                    )

                then("template should contain header, divider, and text blocks") {
                    result.template.size shouldBe 3
                }
            }

            `when`("called with details") {
                val result =
                    templateBuilder.errorNoticeTemplate(
                        headLineText = "Error",
                        errorMessage = "Err",
                        details = "Detail info",
                    )

                then("template should contain header, divider, text, and detail blocks") {
                    result.template.size shouldBe 4
                }
            }
        }
    })
