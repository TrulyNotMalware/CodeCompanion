package dev.notypie.domain.command.dto.interactions

@Deprecated(message = "forRemoval")
enum class ActionStates {
    NOT_DETERMINED,//Not Determined yet.
    APPROVED, NORMAL, REJECTED //These are button state.
}