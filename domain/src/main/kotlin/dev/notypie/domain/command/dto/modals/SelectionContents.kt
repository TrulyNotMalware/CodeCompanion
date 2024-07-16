package dev.notypie.domain.command.dto.modals

data class SelectionContents(
    val title: String,
    val explanation: String,
    val placeholderText: String,

    val contents: List<SelectBoxDetails>
)