package dev.notypie.domain.command.dto.modals

data class SelectionContents(
    val title: String,
    val explanation: String,
    val placeholderText: String,
    val contents: List<SelectBoxDetails>,
)

data class SelectBoxDetails(
    val name: String,
    val isMarkDown: Boolean = false,
    val value: Any,
)

data class MultiUserSelectContents(
    val title: String,
    val placeholderText: String,
)
