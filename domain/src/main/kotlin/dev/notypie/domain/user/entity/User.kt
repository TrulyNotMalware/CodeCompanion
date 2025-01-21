package dev.notypie.domain.user.entity

import dev.notypie.domain.user.UserRole
import java.util.*

class User(
    val id: Long,
    val identifier: UUID = UUID.randomUUID(),
    val slackUserId: String,
    val role: UserRole
) {

}