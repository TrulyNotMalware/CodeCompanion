package dev.notypie.domain.user.entity

import dev.notypie.domain.user.UserRole
import java.util.UUID

data class TeamMemberRole(
    val userId: Long,
    val role: UserRole
)

class Team(
    val teamId: UUID = UUID.randomUUID(),
    val teamName: String,
    val memberRoles: List<TeamMemberRole>
) {
}