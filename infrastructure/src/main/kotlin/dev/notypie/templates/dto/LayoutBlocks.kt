package dev.notypie.templates.dto

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.interactions.States

data class LayoutBlocks(
    val interactionStates: List<States> = listOf(),
    val template: List<LayoutBlock>
)