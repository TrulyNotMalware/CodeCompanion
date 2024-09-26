package dev.notypie.domain.command.dto.interactions

@Deprecated(message = "forRemoval")
data class Actions(
    val state: ActionStates = ActionStates.NOT_DETERMINED,
    val type: ActionElementTypes,

    val rawValue: String,
    val selectedOptions: List<States> = listOf(),
)