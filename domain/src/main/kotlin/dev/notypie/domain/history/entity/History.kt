package dev.notypie.domain.history.entity

import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandType
import java.time.LocalDateTime
import java.util.*

class History(
    val publisherId: String,
    private val states: List<States>,
    val channel: String,
    val status: Status,
    val token: String,
    val idempotencyKey: String,

    val apiAppId: String,
    val type: String, // RequestType.
    val commandType: CommandType
) {
    val historyId: UUID = UUID.randomUUID()
    private val time = LocalDateTime.now()

    //Interaction Contexts
    fun containsActionStates(): Boolean = this.states.isNotEmpty()

}