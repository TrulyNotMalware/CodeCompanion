package dev.notypie.domain.command.dto.interactions

data class States(
    val isSelected: Boolean = false,
    val type: ActionElementTypes,
    val selectedValue: String = ""
)