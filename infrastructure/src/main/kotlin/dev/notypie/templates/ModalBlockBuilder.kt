package dev.notypie.templates

import com.slack.api.model.block.Blocks.*
import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.SectionBlock
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.InteractiveObject

class ModalBlockBuilder(
    private val modalElementBuilder: ModalElementBuilder = ModalElementBuilder(),
){
    companion object{
        const val DEFAULT_CALENDAR_IMAGE_URLS = "https://api.slack.com/img/blocks/bkb_template_images/notifications.png"
    }
    //Reference from https://api.slack.com/reference/block-kit/blocks

    /**
     * Creates a header block with the specified text as the headline.
     *
     * @param text The text content of the headline.
     * @return A `HeaderBlock` object representing the header block with the specified headline.
     */
    fun headerBlock(text: String ): HeaderBlock = header{
        it.text(this.modalElementBuilder.plainTextObject( text = text ))
    }

    /**
     * Generates a section block for time schedule information.
     *
     * @param timeScheduleInfo The time schedule information.
     * @param isMarkDown A flag indicating whether the text should be formatted as Markdown. The default value is false.
     * @return A `SectionBlock` object representing the section block with the time schedule information.
     */
    fun timeScheduleBlock(timeScheduleInfo: TimeScheduleInfo, isMarkDown: Boolean = false): SectionBlock =
        section{
            if(isMarkDown) it.text( this.modalElementBuilder.markdownTextObject( markdownText = timeScheduleInfo.toString() ))
            else it.text( this.modalElementBuilder.plainTextObject( text = timeScheduleInfo.toString() ))
            it.accessory(
                this.modalElementBuilder.imageBlockElement(imageUrl = DEFAULT_CALENDAR_IMAGE_URLS, altText = "calendar thumbnail")
            )
        }

    /**
     * Generates an `ActionsBlock` object representing a block with approval and reject buttons.
     *
     * @param approvalContents The approval contents including button names and interaction values.
     * @return An `ActionsBlock` object representing the approval block.
     */
    fun approvalBlock(approvalContents: ApprovalContents): InteractionLayoutBlock {
        val approvalButton: InteractiveObject = this.modalElementBuilder.approvalButtonElement(
            approvalButtonName = approvalContents.approvalButtonName,
            interactionPayload = approvalContents.approvalInteractionValue)
        val rejectButton: InteractiveObject = this.modalElementBuilder.rejectButtonElement(
            rejectButtonName = approvalContents.rejectButtonName,
            interactionPayload = approvalContents.rejectInteractionValue)

        val layout = actions {
            it.elements(
                listOf(
                    approvalButton.element,
                    rejectButton.element
                )
            )
        }
        return this.toInteractionLayout(approvalButton.state, rejectButton.state, layout = layout)
    }

    /**
     * Generates a section block with a simple text.
     *
     * @param text The text content to be displayed.
     * @param isMarkDown A flag indicating whether the text is in Markdown format or not. Default is false.
     * @return A `SectionBlock` object representing the section block with the specified text.
     */
    fun simpleText(text: String, isMarkDown: Boolean = false ): SectionBlock = section {
        if(isMarkDown) it.text( this.modalElementBuilder.markdownTextObject( markdownText = text))
        else it.text( this.modalElementBuilder.plainTextObject( text = text ))
    }

    /**
     * A function that generates a section block with multiple texts.
     *
     * @param texts The texts to be included in the section block.
     * @param isMarkDown A flag indicating whether the texts should be formatted as Markdown. The default value is false.
     * @return A `SectionBlock` object representing the section block with the specified texts.
     */
    fun textBlock(vararg texts: String, isMarkDown: Boolean = false): SectionBlock = section {
        it.fields(
            texts.map { text ->
                if (isMarkDown) modalElementBuilder.markdownTextObject(markdownText = text)
                else modalElementBuilder.plainTextObject(text = text)
            }
        )
    }
    /**
     * Creates a divider block for Slack templates.
     *
     * @return A `DividerBlock` object representing a horizontal divider.
     */
    fun dividerBlock(): DividerBlock = divider()

    /**
     * Creates a section block for a modal with a selection element.
     *
     * @param selectionContents The contents of the selection element.
     * @return The created section block.
     */
    fun selectionBlock(selectionContents: SelectionContents): InteractionLayoutBlock{
        val multiSelection = this.modalElementBuilder.selectionElement(
            placeholderText = selectionContents.placeholderText, contents = selectionContents.contents)
        val layout = section {
            it.text(this.modalElementBuilder.markdownTextObject( markdownText = "*${selectionContents.title}*\n${selectionContents.explanation}"))
            it.accessory(multiSelection.element)
        }
        return this.toInteractionLayout(multiSelection.state, layout = layout)
    }

    /**
     * Generates a section block with a multi-user selection element for a modal.
     *
     * @param contents The contents of the multi-user selection element.
     * @return A `SectionBlock` object representing the section block with the multi-user selection element.
     */
    fun multiUserSelectBlock(contents: MultiUserSelectContents): InteractionLayoutBlock {
        val multiUserSelection = this.modalElementBuilder.multiUserSelectionElement(contents = contents)
        val layout = input {
            it.label(this.modalElementBuilder.plainTextObject(text = contents.title))
            it.element(multiUserSelection.element)
        }
        return this.toInteractionLayout(multiUserSelection.state, layout = layout)
    }

    /**
     * Generates a section block with a plain text input element.
     *
     * @param contents The contents of the plain text input element.
     * @return The generated section block.
     */
    fun plainTextInputBlock(contents: TextInputContents) = input {
        it.label(this.modalElementBuilder.plainTextObject(text = contents.title))
        it.element(this.modalElementBuilder.plainTextInputElement(contents = contents))
    }

    fun calendarThumbnailBlock(title: String, markdownBody: String) = section {
        it.text(this.modalElementBuilder.markdownTextObject(markdownText = "*${title}*\n${markdownBody}"))
        it.accessory(this.modalElementBuilder.imageBlockElement(imageUrl = DEFAULT_CALENDAR_IMAGE_URLS, altText = "calendar thumbnail"))
    }

    fun selectDateTimeScheduleBlock():InteractionLayoutBlock{
        val datePickerElement = this.modalElementBuilder.datePickerElement()
        val timePickerElement = this.modalElementBuilder.timePickerElement()
        val layout = actions {
            it.elements(
                listOf(
                    datePickerElement.element, timePickerElement.element
                )
            )
        }
        return toInteractionLayout(
            datePickerElement.state, timePickerElement.state, layout = layout
        )
    }

    fun checkBoxesBlock( vararg options: CheckBoxOptions, isMarkDown: Boolean = true ): InteractionLayoutBlock {
        val checkboxElements = this.modalElementBuilder.checkboxElements( options = options, isMarkDown = isMarkDown )
        val layout = actions {
            it.elements(listOf(checkboxElements.element))
        }
        return this.toInteractionLayout(
            checkboxElements.state, layout = layout
        )
    }


    private fun toInteractionLayout(vararg states: States, layout: LayoutBlock): InteractionLayoutBlock =
        InteractionLayoutBlock(
            layout = layout,
            interactiveObjects = listOf(*states)
        )
}