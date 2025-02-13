package dev.notypie.domain.user.entity

import dev.notypie.domain.user.UserRole
import java.util.UUID

data class TeamMemberRole(
    val userId: Long,
    val role: UserRole
)

class Team(
    val id: Long = 0L,
    val teamId: UUID = UUID.randomUUID(),
    val teamName: String,
    val teamDomain: String,
    val slackTeamId: String,
    val members: List<User>,
    val description: String? = null
) {
}