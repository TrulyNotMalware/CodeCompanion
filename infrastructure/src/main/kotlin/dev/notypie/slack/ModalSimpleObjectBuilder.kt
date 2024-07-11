package dev.notypie.slack

import com.slack.api.model.block.composition.BlockCompositions.*
import com.slack.api.model.block.composition.ConfirmationDialogObject
import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.composition.PlainTextObject
import com.slack.api.model.block.element.ButtonElement
import com.slack.api.model.block.element.ImageElement

class ModalSimpleObjectBuilder {

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

    fun approvalButtonElement(approvalButtonName: String, interactionPayload: String, responseUrl: String): ButtonElement =
        this.buttonElement( buttonName = approvalButtonName, interactionPayload = interactionPayload,
            responseUrl = responseUrl, style = ButtonType.PRIMARY)

    fun rejectButtonElement(rejectButtonName: String, interactionPayload: String, responseUrl: String): ButtonElement =
        this.buttonElement( buttonName = rejectButtonName, interactionPayload = interactionPayload,
            responseUrl = responseUrl, style = ButtonType.DANGER )

    fun buttonElement(buttonName: String, interactionPayload: String, responseUrl: String, style: ButtonType = ButtonType.DEFAULT): ButtonElement =
        ButtonElement.builder().apply {
            text(plainTextObject(text = buttonName))
            value(interactionPayload)
            url(responseUrl)
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
}