package dev.notypie.domain.app.entity

import dev.notypie.domain.app.CommandType
import java.util.*

class Command(
    val commandType: CommandType,
    val publisherId: String,

    val channel: String,
    val botToken: String,
) {

    val commandId: UUID = this.generateIdValue()
    val baseUrl: String = "https://slack.com/api/"



    private fun generateIdValue(): UUID = UUID.randomUUID()
}