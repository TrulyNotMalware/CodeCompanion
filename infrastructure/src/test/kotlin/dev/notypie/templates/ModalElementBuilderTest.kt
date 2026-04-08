package dev.notypie.templates

import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.composition.PlainTextObject
import com.slack.api.model.block.element.ButtonElement
import com.slack.api.model.block.element.CheckboxesElement
import com.slack.api.model.block.element.DatePickerElement
import com.slack.api.model.block.element.MultiStaticSelectElement
import com.slack.api.model.block.element.MultiUsersSelectElement
import com.slack.api.model.block.element.PlainTextInputElement
import com.slack.api.model.block.element.RadioButtonsElement
import com.slack.api.model.block.element.TimePickerElement
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.modals.MultiUserSelectContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.templates.dto.CheckBoxOptions
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ModalElementBuilderTest :
    BehaviorSpec({
        val builder = ModalElementBuilder()

        given("textObject") {
            `when`("isMarkDown is false") {
                val result = builder.textObject(text = "hello", isMarkDown = false)

                then("returns PlainTextObject") {
                    result.shouldBeInstanceOf<PlainTextObject>().text shouldBe "hello"
                }
            }

            `when`("isMarkDown is true") {
                val result = builder.textObject(text = "*bold*", isMarkDown = true)

                then("returns MarkdownTextObject") {
                    result.shouldBeInstanceOf<MarkdownTextObject>().text shouldBe "*bold*"
                }
            }
        }

        given("plainTextObject") {
            `when`("called with text") {
                val result = builder.plainTextObject(text = "plain text")

                then("returns PlainTextObject with emoji enabled") {
                    result.text shouldBe "plain text"
                    result.emoji shouldBe true
                }
            }
        }

        given("markdownTextObject") {
            `when`("called with markdown text") {
                val result = builder.markdownTextObject(markdownText = "*bold*")

                then("returns MarkdownTextObject with verbatim enabled") {
                    result.text shouldBe "*bold*"
                    result.verbatim shouldBe true
                }
            }
        }

        given("imageBlockElement") {
            `when`("called with url and alt text") {
                val result = builder.imageBlockElement(imageUrl = "https://example.com/img.png", altText = "alt")

                then("returns ImageElement with matching fields") {
                    result.imageUrl shouldBe "https://example.com/img.png"
                    result.altText shouldBe "alt"
                }
            }
        }

        given("approvalButtonElement") {
            `when`("called with button name and payload") {
                val result =
                    builder.approvalButtonElement(
                        approvalButtonName = "Approve",
                        interactionPayload = "key, TYPE",
                    )

                then("state type is APPLY_BUTTON") {
                    result.state.type shouldBe ActionElementTypes.APPLY_BUTTON
                }

                then("element is ButtonElement with primary style") {
                    val button = result.element.shouldBeInstanceOf<ButtonElement>()
                    button.text.text shouldBe "Approve"
                    button.value shouldBe "key, TYPE"
                    button.style shouldBe "primary"
                }
            }
        }

        given("rejectButtonElement") {
            `when`("called with button name and payload") {
                val result =
                    builder.rejectButtonElement(
                        rejectButtonName = "Deny",
                        interactionPayload = "key, TYPE",
                    )

                then("state type is REJECT_BUTTON") {
                    result.state.type shouldBe ActionElementTypes.REJECT_BUTTON
                }

                then("element is ButtonElement with danger style") {
                    val button = result.element.shouldBeInstanceOf<ButtonElement>()
                    button.text.text shouldBe "Deny"
                    button.style shouldBe "danger"
                }
            }
        }

        given("confirmationDialogObject") {
            `when`("called with all parameters") {
                val result =
                    builder.confirmationDialogObject(
                        title = "Confirm?",
                        text = "Are you sure?",
                        confirmText = "Yes",
                        denyText = "No",
                    )

                then("dialog fields match the given parameters") {
                    result.title.text shouldBe "Confirm?"
                    result.text.text shouldBe "Are you sure?"
                    result.confirm.text shouldBe "Yes"
                    result.deny.text shouldBe "No"
                }
            }
        }

        given("selectionElement") {
            `when`("called with options") {
                val contents =
                    listOf(
                        SelectBoxDetails(name = "Option A", value = "a"),
                        SelectBoxDetails(name = "Option B", value = "b"),
                    )
                val result = builder.selectionElement(placeholderText = "Select", contents = contents)

                then("state type is MULTI_STATIC_SELECT") {
                    result.state.type shouldBe ActionElementTypes.MULTI_STATIC_SELECT
                }

                then("element is MultiStaticSelectElement with correct options") {
                    val select = result.element.shouldBeInstanceOf<MultiStaticSelectElement>()
                    select.options.size shouldBe 2
                    select.options[0].text.text shouldBe "Option A"
                    select.options[0].value shouldBe "a"
                    select.options[1].text.text shouldBe "Option B"
                    select.options[1].value shouldBe "b"
                }
            }
        }

        given("multiUserSelectionElement") {
            `when`("called with contents") {
                val result =
                    builder.multiUserSelectionElement(
                        contents =
                            MultiUserSelectContents(
                                title = "Users",
                                placeholderText = "Select users",
                            ),
                    )

                then("state type is MULTI_USERS_SELECT") {
                    result.state.type shouldBe ActionElementTypes.MULTI_USERS_SELECT
                }

                then("element is MultiUsersSelectElement") {
                    result.element.shouldBeInstanceOf<MultiUsersSelectElement>()
                }
            }
        }

        given("plainTextInputElement") {
            `when`("called with contents") {
                val result =
                    builder.plainTextInputElement(
                        contents = TextInputContents(title = "Input", placeholderText = "Type here"),
                    )

                then("returns PlainTextInputElement with correct placeholder") {
                    result.shouldBeInstanceOf<PlainTextInputElement>()
                    result.placeholder.text shouldBe "Type here"
                }
            }
        }

        given("timePickerElement") {
            `when`("called with default placeholder") {
                val result = builder.timePickerElement()

                then("state type is TIME_PICKER") {
                    result.state.type shouldBe ActionElementTypes.TIME_PICKER
                }

                then("element is TimePickerElement") {
                    val picker = result.element.shouldBeInstanceOf<TimePickerElement>()
                    picker.placeholder.text shouldBe "Select time"
                }
            }
        }

        given("datePickerElement") {
            `when`("called with default placeholder") {
                val result = builder.datePickerElement()

                then("state type is DATE_PICKER") {
                    result.state.type shouldBe ActionElementTypes.DATE_PICKER
                }

                then("element is DatePickerElement") {
                    val picker = result.element.shouldBeInstanceOf<DatePickerElement>()
                    picker.placeholder.text shouldBe "Select a date"
                }
            }
        }

        given("checkboxElements") {
            `when`("called with options") {
                val result =
                    builder.checkboxElements(
                        CheckBoxOptions(text = "Option 1", description = "desc1"),
                        CheckBoxOptions(text = "Option 2", description = "desc2"),
                    )

                then("state type is CHECKBOX") {
                    result.state.type shouldBe ActionElementTypes.CHECKBOX
                }

                then("element is CheckboxesElement with correct options") {
                    val checkbox = result.element.shouldBeInstanceOf<CheckboxesElement>()
                    checkbox.options.size shouldBe 2
                }
            }
        }

        given("radioButtonElements") {
            `when`("called with options") {
                val result =
                    builder.radioButtonElements(
                        CheckBoxOptions(text = "Radio 1"),
                        CheckBoxOptions(text = "Radio 2"),
                        description = "Pick one",
                    )

                then("state type is RADIO_BUTTONS") {
                    result.state.type shouldBe ActionElementTypes.RADIO_BUTTONS
                }

                then("element is RadioButtonsElement with correct options") {
                    val radio = result.element.shouldBeInstanceOf<RadioButtonsElement>()
                    radio.options.size shouldBe 2
                }
            }
        }

        given("optionElement") {
            `when`("called with text and description") {
                val result = builder.optionElement(text = "Choice", description = "A choice")

                then("option text and description match") {
                    result.text.shouldBeInstanceOf<MarkdownTextObject>().text shouldBe "Choice"
                    result.description.text shouldBe "A choice"
                }
            }

            `when`("called with isMarkDown false") {
                val result = builder.optionElement(text = "Plain", description = "desc", isMarkDown = false)

                then("option text is PlainTextObject") {
                    result.text.shouldBeInstanceOf<PlainTextObject>().text shouldBe "Plain"
                }
            }
        }
    })
