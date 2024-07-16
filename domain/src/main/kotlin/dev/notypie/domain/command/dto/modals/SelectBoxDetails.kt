package dev.notypie.domain.command.dto.modals

data class SelectBoxDetails(
    val name: String,
    val isMarkDown: Boolean = false,

    //[NOTE] value convert to string type.
    val value: Any
)