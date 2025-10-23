package dev.notypie.application.controllers.dto

import dev.notypie.domain.command.entity.CommandDetailType
import java.util.UUID

data class EventResponseDto(
    val message: String,
    val event: Event,
    val isAccepted: Boolean,
)

data class Event(
    val eventId: UUID,
    val type: CommandDetailType,
    val acceptedTime: Long,
)
