package dev.notypie.slack

import com.slack.api.model.block.ActionsBlock
import com.slack.api.model.block.Blocks.*
import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.TextObject
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo

class ModalBlockBuilder(
    private val modalSimpleObjectBuilder: ModalSimpleObjectBuilder = ModalSimpleObjectBuilder(),
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
        it.text(this.modalSimpleObjectBuilder.plainTextObject( text = text ))
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
            if(isMarkDown) it.text( this.modalSimpleObjectBuilder.markdownTextObject( markdownText = timeScheduleInfo.toString() ))
            else it.text( this.modalSimpleObjectBuilder.plainTextObject( text = timeScheduleInfo.toString() ))
            it.accessory(
                this.modalSimpleObjectBuilder.imageBlockElement(imageUrl = DEFAULT_CALENDAR_IMAGE_URLS, altText = "calendar thumbnail")
            )
        }

    /**
     * Generates an `ActionsBlock` object representing a block with approval and reject buttons.
     *
     * @param approvalContents The approval contents including button names and interaction values.
     * @return An `ActionsBlock` object representing the approval block.
     */
    fun approvalBlock(approvalContents: ApprovalContents): ActionsBlock = actions {
        it.elements(
            listOf(
                this.modalSimpleObjectBuilder.approvalButtonElement(
                    approvalButtonName = approvalContents.approvalButtonName,
                    interactionPayload = approvalContents.approvalInteractionValue,
                ),
                this.modalSimpleObjectBuilder.rejectButtonElement(
                    rejectButtonName = approvalContents.rejectButtonName,
                    interactionPayload = approvalContents.rejectInteractionValue,
                )
            )
        )
    }

    /**
     * Generates a section block with a simple text.
     *
     * @param text The text content to be displayed.
     * @param isMarkDown A flag indicating whether the text is in Markdown format or not. Default is false.
     * @return A `SectionBlock` object representing the section block with the specified text.
     */
    fun simpleText(text: String, isMarkDown: Boolean = false ): SectionBlock = section {
        if(isMarkDown) it.text( this.modalSimpleObjectBuilder.markdownTextObject( markdownText = text))
        else it.text( this.modalSimpleObjectBuilder.plainTextObject( text = text ))
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
                if (isMarkDown) modalSimpleObjectBuilder.markdownTextObject(markdownText = text)
                else modalSimpleObjectBuilder.plainTextObject(text = text)
            }
        )
    }
    /**
     * Creates a divider block for Slack templates.
     *
     * @return A `DividerBlock` object representing a horizontal divider.
     */
    fun dividerBlock(): DividerBlock = divider()

}