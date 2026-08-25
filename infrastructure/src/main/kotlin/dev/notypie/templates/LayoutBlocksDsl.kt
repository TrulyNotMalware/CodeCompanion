package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.impl.command.slack.States
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.LayoutBlocks

/**
 * Type-safe builder for [LayoutBlocks] — the domain bundle of Slack `LayoutBlock` objects
 * plus the [States] objects produced by interactive elements. The DSL collapses the recurring
 * `mutableListOf<LayoutBlock>() + manual states += interactive.interactiveObjects` boilerplate
 * in [ModalTemplateBuilder] into a single overloaded `add()` call: the [LayoutBlock] overload
 * appends a non-interactive block; the [InteractionLayoutBlock] overload also pulls the
 * captured states up so callers no longer have to thread them by hand.
 *
 * Scope is intentionally minimal — only the operations [ModalTemplateBuilder] needs today.
 * Add new helpers as new template shapes are introduced rather than mirroring the entire
 * Slack Block Kit surface preemptively.
 */
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

    fun addAll(layouts: Iterable<InteractionLayoutBlock>) {
        layouts.forEach { add(layout = it) }
    }

    internal fun build(): LayoutBlocks =
        LayoutBlocks(
            interactionStates = states.toList(),
            template = blocks.toList(),
        )
}
