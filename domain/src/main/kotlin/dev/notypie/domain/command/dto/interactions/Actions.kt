package dev.notypie.domain.command.dto.interactions

data class Actions(//Primary actions ( ex, button click )
    val state: ActionStates = ActionStates.NOT_DETERMINED,
    val type: ActionElementTypes,

    val rawValue: String,
    val selectedOptions: List<States> = listOf(),
)