package dev.notypie.templates.dto

import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.element.BlockElement
import dev.notypie.domain.command.dto.interactions.States

/*
 * Wrapper types that pair Slack SDK rendering objects with the [States] entries the
 * interaction parser needs to recover after a click or view submission. The three shapes
 * live in one file because they share the same role — "Slack block/element + captured
 * interaction state" — and travel together through the template builder pipeline:
 * an [InteractiveObject] composes into an [InteractionLayoutBlock], which composes into a
 * [LayoutBlocks] alongside non-interactive blocks.
 */

/**
 * Bundle of fully-rendered Slack `LayoutBlock`s plus the union of [States] objects produced
 * by every interactive element inside them. The bundle is what [SlackTemplateBuilder] hands
 * back to the request builder — non-interactive templates supply only [template] with an
 * empty [interactionStates] list.
 */
data class LayoutBlocks(
    val interactionStates: List<States> = listOf(),
    val template: List<LayoutBlock>,
)

/**
 * Single rendered [LayoutBlock] that owns one or more interactive elements; the
 * [interactiveObjects] are the [States] that the parser will rehydrate when the user clicks
 * or submits the rendered block.
 */
data class InteractionLayoutBlock(
    val interactiveObjects: List<States>,
    val layout: LayoutBlock,
)

/**
 * Single rendered [BlockElement] (button, select, picker, …) paired with the [States] entry
 * it produces. Composed into an [InteractionLayoutBlock] by [ModalBlockBuilder].
 */
data class InteractiveObject(
    val state: States,
    val element: BlockElement,
)
