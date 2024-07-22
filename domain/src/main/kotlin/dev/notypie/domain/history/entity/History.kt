package dev.notypie.domain.history.entity

import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandType
import java.time.LocalDateTime
import java.util.*

class History(
    private val publisherId: String,
    private val states: List<States>,
    private val channel: String,
    private val ok: Boolean,
    private val token: String,

    private val apiAppId: String,
    private val type: String, // RequestType.
    private val commandType: CommandType
) {
    val historyId: UUID = UUID.randomUUID()
    private val time = LocalDateTime.now()

    //Interaction Contexts
    fun containsActionStates(): Boolean = this.states.isNotEmpty()

}