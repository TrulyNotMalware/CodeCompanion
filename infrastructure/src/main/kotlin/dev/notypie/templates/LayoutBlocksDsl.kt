package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.impl.command.slack.States
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.LayoutBlocks

@DslMarker
annotation class LayoutBlocksDsl

fun layoutBlocks(block: LayoutBlocksBuilder.() -> Unit): LayoutBlocks = LayoutBlocksBuilder().apply(block).build()

@LayoutBlocksDsl
class LayoutBlocksBuilder {
    private val blocks = mutableListOf<LayoutBlock>()
    private val states = mutableListOf<States>()

    fun add(block: LayoutBlock) {
        blocks.add(block)
    }

    fun add(layout: InteractionLayoutBlock) {
        blocks.add(layout.layout)
        states.addAll(layout.interactiveObjects)
    }

    fun addAll(layouts: Iterable<InteractionLayoutBlock>) = layouts.forEach { add(layout = it) }

    internal fun build(): LayoutBlocks =
        LayoutBlocks(
            interactionStates = states.toList(),
            template = blocks.toList(),
        )
}
