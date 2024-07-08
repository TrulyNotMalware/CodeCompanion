package dev.notypie.slack

import com.slack.api.model.block.Blocks.*
import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.SectionBlock

class ModalBlockBuilder(
    private val modalSimpleObjectBuilder: ModalSimpleObjectBuilder = ModalSimpleObjectBuilder(),
){
    //Reference from https://api.slack.com/reference/block-kit/blocks

    /**
     * Creates a header block with the specified text as the headline.
     *
     * @param text The text content of the headline.
     * @return A `HeaderBlock` object representing the header block with the specified headline.
     */
    fun createHeadLine(text: String ): HeaderBlock = header{
        it.text(this.modalSimpleObjectBuilder.plainTextObject( text = text ))
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
     * Creates a divider block for Slack templates.
     *
     * @return A `DividerBlock` object representing a horizontal divider.
     */
    fun createDivider(): DividerBlock = divider()

}