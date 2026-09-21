package dev.notypie.impl.command.slack

data class States(
    val isSelected: Boolean = false,
    val type: ActionElementTypes,
    val selectedValue: String = "",
    val blockId: String? = null,
)
