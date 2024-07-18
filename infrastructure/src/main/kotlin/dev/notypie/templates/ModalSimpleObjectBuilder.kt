package dev.notypie.templates

import com.slack.api.model.block.composition.*
import com.slack.api.model.block.composition.BlockCompositions.*
import com.slack.api.model.block.element.BlockElement
import com.slack.api.model.block.element.ButtonElement
import com.slack.api.model.block.element.ImageElement
import com.slack.api.model.block.element.MultiStaticSelectElement
import com.slack.api.model.block.element.MultiUsersSelectElement
import com.slack.api.model.block.element.PlainTextInputElement
import dev.notypie.domain.command.dto.modals.MultiUserSelectContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.templates.dto.InteractiveObject

class ModalSimpleObjectBuilder {//FIXME rename to "ModalElementBuilder"

    fun textObject(text: String, isMarkDown: Boolean): TextObject =
        if(isMarkDown) this.plainTextObject(text = text)
        else this.markdownTextObject(markdownText = text)

    fun plainTextObject(text: String): PlainTextObject = plainText{
        it.text(text)
        it.emoji(true)
    }

    fun markdownTextObject(markdownText: String): MarkdownTextObject = markdownText {
        it.text(markdownText)
        it.verbatim(true)
    }

    fun imageBlockElement(imageUrl: String, altText: String): ImageElement =
        ImageElement.builder().imageUrl(imageUrl).altText(altText).build()

    fun approvalButtonElement(approvalButtonName: String, interactionPayload: String): ButtonElement =
        this.buttonElement( buttonName = approvalButtonName, interactionPayload = interactionPayload,
            style = ButtonType.PRIMARY)

    fun rejectButtonElement(rejectButtonName: String, interactionPayload: String): ButtonElement =
        this.buttonElement( buttonName = rejectButtonName, interactionPayload = interactionPayload,
            style = ButtonType.DANGER )

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
        MultiStaticSelectElement.builder()
            .placeholder(this.plainTextObject(text = placeholderText))
            .options(contents.map {
                OptionObject.builder()
                    .text(plainTextObject(it.name))
                    .value(it.value.toString())
                    .build()
            })
            .build()

    fun multiUserSelectionElement(contents: MultiUserSelectContents) = MultiUsersSelectElement.builder()
        .placeholder(this.plainTextObject(text = contents.placeholderText))
        .build()

    fun plainTextInputElement(contents: TextInputContents) = PlainTextInputElement.builder()
        .placeholder(this.plainTextObject(text = contents.placeholderText))
        .multiline(true)
        .build()

    //FIXME change for record history.
//    private fun toInteractiveObject(element: BlockElement): InteractiveObject =
//        InteractiveObject(state = , element = element)
}