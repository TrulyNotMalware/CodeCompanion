package dev.notypie.domain.user.entity

import dev.notypie.domain.user.UserRole
import java.util.*

class User(
    val id: Long,
    val slackUserId: String,
    val userName: String,
    val isAdmin: Boolean = false,
    val role: UserRole = UserRole.MEMBER
) {

}