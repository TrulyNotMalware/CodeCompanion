package dev.notypie.domain.command.dto.modals

data class SelectBoxDetails(
    val name: String,
    val isMarkDown: Boolean = false,
    val value: Any
)