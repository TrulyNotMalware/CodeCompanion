package dev.notypie.templates

import com.slack.api.model.block.composition.*
import com.slack.api.model.block.composition.BlockCompositions.*
import com.slack.api.model.block.element.*
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.MultiUserSelectContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.InteractiveObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ModalElementBuilder {

    fun textObject(text: String, isMarkDown: Boolean): TextObject =
        if( !isMarkDown ) this.plainTextObject(text = text)
        else this.markdownTextObject(markdownText = text)

    fun plainTextObject(text: String): PlainTextObject = plainText {
        it.text(text)
        it.emoji(true)
    }

    fun markdownTextObject(markdownText: String): MarkdownTextObject = markdownText {
        it.text(markdownText)
        it.verbatim(true)
    }

    fun imageBlockElement(imageUrl: String, altText: String): ImageElement =
        ImageElement.builder().imageUrl(imageUrl).altText(altText).build()

    fun approvalButtonElement(approvalButtonName: String, interactionPayload: String): InteractiveObject =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.APPLY_BUTTON )
            ,element = this.buttonElement( buttonName = approvalButtonName,
                interactionPayload = interactionPayload, style = ButtonType.PRIMARY)
        )

    fun rejectButtonElement(rejectButtonName: String, interactionPayload: String): InteractiveObject =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.REJECT_BUTTON),
            element = this.buttonElement( buttonName = rejectButtonName, interactionPayload = interactionPayload,
                style = ButtonType.DANGER )
        )

    private fun buttonElement(buttonName: String, interactionPayload: String, style: ButtonType = ButtonType.DEFAULT): ButtonElement =
        ButtonElement.builder().apply {
            text(plainTextObject(text = buttonName))
            value(interactionPayload)
            if(style != ButtonType.DEFAULT) style(style.toString().lowercase())
        }.build()

    //Reference from https://api.slack.com/reference/block-kit/composition-objects
    fun confirmationDialogObject( title: String, text: String, confirmText: String, denyText: String): ConfirmationDialogObject =
        confirmationDialog {
            it.title( this.plainTextObject(text = title) )
            it.text( this.plainTextObject(text = text) )
            it.confirm( this.plainTextObject(text = confirmText) )
            it.deny( this.plainTextObject(text = denyText) )
        }

    fun selectionElement(placeholderText: String, contents: List<SelectBoxDetails>) =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.MULTI_STATIC_SELECT),
            element = MultiStaticSelectElement.builder()
                .placeholder(this.plainTextObject(text = placeholderText))
                .options(contents.map {
                    OptionObject.builder()
                        .text(plainTextObject(it.name))
                        .value(it.value.toString())
                        .build()
                })
                .build()
        )

    fun multiUserSelectionElement(contents: MultiUserSelectContents) =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.MULTI_USERS_SELECT),
            element = MultiUsersSelectElement.builder()
                .placeholder(this.plainTextObject(text = contents.placeholderText))
                .build()
        )

    fun plainTextInputElement(contents: TextInputContents, multiline: Boolean = false) = PlainTextInputElement.builder()
        .placeholder(this.plainTextObject(text = contents.placeholderText))
        .multiline(multiline)
        .build()

    fun timePickerElement(placeholderText: String = "Select time") =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.TIME_PICKER),
            element = TimePickerElement.builder()
                .initialTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                .placeholder(this.plainTextObject(text = placeholderText))
                .build()
        )

    fun datePickerElement(placeholderText: String = "Select a date") =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.DATE_PICKER),
            element = DatePickerElement.builder()
                .initialDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .placeholder(this.plainTextObject(text = placeholderText))
                .build()
        )

    fun optionElement(text: String, description: String = "", isMarkDown: Boolean = true) = option {
        it.text(this.textObject(text = text, isMarkDown = isMarkDown))
        it.description(this.plainTextObject(text = description))
    }

    fun checkboxElements(vararg options: CheckBoxOptions, isMarkDown: Boolean = true) =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.CHECKBOX),
            element = CheckboxesElement.builder()
                .options(
                    options.map { this.optionElement(text = it.text, description = it.description, isMarkDown = isMarkDown) }
                )
                .build()
        )

    fun radioButtonElements(vararg options: CheckBoxOptions, description: String ) =
        this.toInteractiveObject(
            state = States(type = ActionElementTypes.RADIO_BUTTONS),
            element = RadioButtonsElement.builder()
                .options(
                    options.map { this.optionElement(text = it.text, description = it.description, isMarkDown = true) }
                )
                .build()
        )

    //FIXME change for record history.
    private fun toInteractiveObject(state: States, element: BlockElement): InteractiveObject =
        InteractiveObject(state = state, element = element)
}