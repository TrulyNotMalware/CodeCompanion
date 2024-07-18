package dev.notypie.domain.history.entity

import dev.notypie.domain.command.dto.interactions.User
import java.util.*

//NOT IMPLEMENTED YET
class History(
    private val user: User
) {
    val historyId: UUID = UUID.randomUUID()

}