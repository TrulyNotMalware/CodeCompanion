package dev.notypie.slack

import com.slack.api.model.block.composition.BlockCompositions.*
import com.slack.api.model.block.composition.ConfirmationDialogObject
import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.composition.PlainTextObject

class ModalSimpleObjectBuilder {

    fun plainTextObject(text: String): PlainTextObject = plainText{
        it.text(text)
        it.emoji(true)
    }

    fun markdownTextObject(markdownText: String): MarkdownTextObject = markdownText {
        it.text(markdownText)
        it.verbatim(true)
    }

    //Reference from https://api.slack.com/reference/block-kit/composition-objects
    fun confirmationDialogObject( title: String, text: String, confirmText: String, denyText: String): ConfirmationDialogObject =
        confirmationDialog {
            it.title( this.plainTextObject(text = title) )
            it.text( this.plainTextObject(text = text) )
            it.confirm( this.plainTextObject(text = confirmText) )
            it.deny( this.plainTextObject(text = denyText) )
        }
}