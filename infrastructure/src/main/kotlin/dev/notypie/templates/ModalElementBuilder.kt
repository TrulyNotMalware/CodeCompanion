package dev.notypie.templates

import com.slack.api.model.block.composition.ConfirmationDialogObject
import com.slack.api.model.block.element.BlockElement
import com.slack.api.model.block.element.BlockElements.button

class ModalElementBuilder(
    private val modalObjectBuilder: ModalSimpleObjectBuilder = ModalSimpleObjectBuilder(),
){

    private fun elements(vararg blockElements: BlockElement): List<BlockElement> = listOf(*blockElements)

    fun createButton(buttonName: String, actionId: String, buttonType: ButtonType = ButtonType.DEFAULT, confirmationDialogObject: ConfirmationDialogObject? = null ) =
        button{
            it.text( modalObjectBuilder.plainTextObject(text = buttonName) )
            it.style(buttonType.toString().lowercase())
            it.actionId(actionId)
            confirmationDialogObject?.let { confirmationDialogObject -> it.confirm(confirmationDialogObject) }
        }
}