package dev.notypie.templates

import com.slack.api.model.block.ActionsBlock
import com.slack.api.model.block.ContextBlock
import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.InputBlock
import com.slack.api.model.block.SectionBlock
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.MultiUserSelectContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.dto.CheckBoxOptions
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
import java.util.UUID

class ModalBlockBuilderTest :
    BehaviorSpec({
        val builder = ModalBlockBuilder()

        given("headerBlock") {
            `when`("called with text") {
                val result = builder.headerBlock(text = "My Header")

                then("returns HeaderBlock with matching text") {
                    result.shouldBeInstanceOf<HeaderBlock>()
                    result.text.text shouldBe "My Header"
                }
            }
        }

        given("dividerBlock") {
            `when`("called") {
                val result = builder.dividerBlock()

                then("returns DividerBlock") {
                    result.shouldBeInstanceOf<DividerBlock>()
                }
            }
        }

        given("simpleText") {
            `when`("called with plain text") {
                val result = builder.simpleText(text = "Hello", isMarkDown = false)

                then("returns SectionBlock with plain text") {
                    result.shouldBeInstanceOf<SectionBlock>()
                    result.text shouldNotBe null
                    result.text.text shouldBe "Hello"
                }
            }

            `when`("called with markdown") {
                val result = builder.simpleText(text = "*bold*", isMarkDown = true)

                then("returns SectionBlock with markdown text") {
                    result.shouldBeInstanceOf<SectionBlock>()
                    result.text.text shouldBe "*bold*"
                }
            }
        }

        given("textBlock") {
            `when`("called with multiple texts") {
                val result = builder.textBlock("field1", "field2", isMarkDown = false)

                then("returns SectionBlock with fields") {
                    result.shouldBeInstanceOf<SectionBlock>()
                    result.fields.size shouldBe 2
                    result.fields[0].text shouldBe "field1"
                    result.fields[1].text shouldBe "field2"
                }
            }
        }

        given("timeScheduleBlock") {
            `when`("called with time schedule info") {
                val info =
                    TimeScheduleInfo(
                        scheduleName = "Standup",
                        startTime = LocalDateTime.of(2026, 3, 31, 9, 0),
                        endTime = LocalDateTime.of(2026, 3, 31, 9, 30),
                    )
                val result = builder.timeScheduleBlock(timeScheduleInfo = info)

                then("returns SectionBlock with accessory image") {
                    result.shouldBeInstanceOf<SectionBlock>()
                    result.accessory shouldNotBe null
                    result.text.text shouldBe info.toString()
                }
            }
        }

        given("approvalBlock") {
            `when`("called with approval contents") {
                val contents =
                    ApprovalContents(
                        idempotencyKey = UUID.randomUUID(),
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        reason = "Test",
                        publisherId = "U012ABCDEFG",
                    )
                val result = builder.approvalBlock(approvalContents = contents)

                then("layout is ActionsBlock") {
                    result.layout.shouldBeInstanceOf<ActionsBlock>()
                }

                then("interactiveObjects contain APPLY_BUTTON and REJECT_BUTTON") {
                    val types = result.interactiveObjects.map { it.type }
                    types.shouldContainAll(ActionElementTypes.APPLY_BUTTON, ActionElementTypes.REJECT_BUTTON)
                }

                then("interactiveObjects size is 2") {
                    result.interactiveObjects.size shouldBe 2
                }
            }
        }

        given("selectionBlock") {
            `when`("called with selection contents") {
                val contents =
                    SelectionContents(
                        title = "Category",
                        explanation = "Pick one",
                        placeholderText = "SELECT",
                        contents =
                            listOf(
                                SelectBoxDetails(name = "A", value = "a"),
                            ),
                    )
                val result = builder.selectionBlock(selectionContents = contents)

                then("layout is SectionBlock") {
                    result.layout.shouldBeInstanceOf<SectionBlock>()
                }

                then("interactiveObjects contain MULTI_STATIC_SELECT") {
                    result.interactiveObjects.size shouldBe 1
                    result.interactiveObjects[0].type shouldBe ActionElementTypes.MULTI_STATIC_SELECT
                }
            }
        }

        given("multiUserSelectBlock") {
            `when`("called with contents") {
                val result =
                    builder.multiUserSelectBlock(
                        contents =
                            MultiUserSelectContents(
                                title = "Users",
                                placeholderText = "Select",
                            ),
                    )

                then("layout is InputBlock") {
                    result.layout.shouldBeInstanceOf<InputBlock>()
                }

                then("interactiveObjects contain MULTI_USERS_SELECT") {
                    result.interactiveObjects.size shouldBe 1
                    result.interactiveObjects[0].type shouldBe ActionElementTypes.MULTI_USERS_SELECT
                }
            }
        }

        given("plainTextInputBlock") {
            `when`("called with contents") {
                val result =
                    builder.plainTextInputBlock(
                        contents = TextInputContents(title = "Name", placeholderText = "Enter name"),
                    )

                then("returns InputBlock") {
                    result.shouldBeInstanceOf<InputBlock>()
                    result.label.text shouldBe "Name"
                }
            }
        }

        given("calendarThumbnailBlock") {
            `when`("called with title and body") {
                val result =
                    builder.calendarThumbnailBlock(
                        title = "Schedule",
                        markdownBody = "Details here",
                    )

                then("returns SectionBlock with accessory image") {
                    result.shouldBeInstanceOf<SectionBlock>()
                    result.accessory shouldNotBe null
                    result.text.text shouldBe "*Schedule*\nDetails here"
                }
            }
        }

        given("userNameWithThumbnailBlock") {
            `when`("called with user info") {
                val result =
                    builder.userNameWithThumbnailBlock(
                        userName = "testuser",
                        userThumbnailUrl = "https://example.com/img.png",
                        mkdIntroduceComment = "*Publisher* :",
                    )

                then("returns ContextBlock with elements") {
                    result.shouldBeInstanceOf<ContextBlock>()
                    result.elements.size shouldBe 3
                }
            }
        }

        given("selectDateTimeScheduleBlock") {
            `when`("called") {
                val result = builder.selectDateTimeScheduleBlock()

                then("layout is ActionsBlock") {
                    result.layout.shouldBeInstanceOf<ActionsBlock>()
                }

                then("interactiveObjects contain DATE_PICKER and TIME_PICKER") {
                    val types = result.interactiveObjects.map { it.type }
                    types.shouldContainAll(ActionElementTypes.DATE_PICKER, ActionElementTypes.TIME_PICKER)
                }

                then("interactiveObjects size is 2") {
                    result.interactiveObjects.size shouldBe 2
                }
            }
        }

        given("checkBoxesBlock") {
            `when`("called with options") {
                val result =
                    builder.checkBoxesBlock(
                        CheckBoxOptions(text = "Check A"),
                        CheckBoxOptions(text = "Check B"),
                    )

                then("layout is ActionsBlock") {
                    result.layout.shouldBeInstanceOf<ActionsBlock>()
                }

                then("interactiveObjects contain CHECKBOX") {
                    result.interactiveObjects.size shouldBe 1
                    result.interactiveObjects[0].type shouldBe ActionElementTypes.CHECKBOX
                }
            }
        }

        given("radioButtonBlock") {
            `when`("called with options") {
                val result =
                    builder.radioButtonBlock(
                        "Option 1",
                        "Option 2",
                        description = "Pick one",
                    )

                then("layout is SectionBlock") {
                    result.layout.shouldBeInstanceOf<SectionBlock>()
                }

                then("interactiveObjects contain RADIO_BUTTONS") {
                    result.interactiveObjects.size shouldBe 1
                    result.interactiveObjects[0].type shouldBe ActionElementTypes.RADIO_BUTTONS
                }
            }
        }
    })
