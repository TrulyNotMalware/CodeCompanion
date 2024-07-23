package dev.notypie.templates.dto

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.interactions.States

data class InteractionLayoutBlock (
    val interactiveObjects: List<States>,
    val layout: LayoutBlock,
)