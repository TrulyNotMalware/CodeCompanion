package dev.notypie.domain.user.entity

import java.util.*

class User(
    val id: Long,
    val identifier: UUID = UUID.randomUUID(),
    val slackUserId: String,
    val teams: List<Team>,
    val isAdmin: Boolean = false
) {

}