package dev.notypie.domain.user.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.user.UserRole

class User(
//    val id: Long,
    val slackUserId: String,
    val userName: String,
    val isAdmin: Boolean = false,
    val role: UserRole = UserRole.MEMBER,
) {
    init {
        validate(className = this.javaClass.simpleName) {
            notBlank {
                "userName" of userName
                "slackUserId" of slackUserId
            }
            "slackId" of slackUserId shouldMatchPattern "^U[A-Z0-9]{10}$"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is User) return false
        return slackUserId == other.slackUserId
    }

    override fun hashCode(): Int = slackUserId.hashCode()
}
