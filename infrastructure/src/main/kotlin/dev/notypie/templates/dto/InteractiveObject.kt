package dev.notypie.templates.dto

import com.slack.api.model.block.element.BlockElement
import dev.notypie.domain.command.dto.interactions.States

data class InteractiveObject(
    val state: States,
    val element: BlockElement,
)