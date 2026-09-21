package dev.notypie.templates.dto

import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.element.BlockElement
import dev.notypie.impl.command.slack.States

data class LayoutBlocks(
    val interactionStates: List<States> = listOf(),
    val template: List<LayoutBlock>,
)

data class InteractionLayoutBlock(
    val interactiveObjects: List<States>,
    val layout: LayoutBlock,
)

data class InteractiveObject(
    val state: States,
    val element: BlockElement,
)
