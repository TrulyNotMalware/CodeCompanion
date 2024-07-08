package dev.notypie.slack

import com.slack.api.model.block.LayoutBlock

/**
 * A class that implements the SlackTemplateBuilder interface and provides methods for building modal templates.
 */
class ModalTemplateBuilder(
    private val modalBlockBuilder: ModalBlockBuilder = ModalBlockBuilder(),
    private val modalElementBuilder: ModalElementBuilder = ModalElementBuilder()
): SlackTemplateBuilder {

    private fun createLayouts(vararg blocks: LayoutBlock) = listOf(*blocks)

    override fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): List<LayoutBlock>
    = this.createLayouts(
        this.modalBlockBuilder.createHeadLine(text = headLineText),
        this.modalBlockBuilder.createDivider(),
        this.modalBlockBuilder.simpleText(text = body, isMarkDown = isMarkDown)
    )
}