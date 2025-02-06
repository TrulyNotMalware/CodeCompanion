package dev.notypie.repository.user.schema

import dev.notypie.domain.user.entity.Team
import dev.notypie.domain.user.entity.TeamMemberRole
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity(name = "teams")
class JpaTeamSchema(

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long,

    @field:Column(name = "team_id",nullable = false)
    val teamId : UUID,

    @field:Column(name = "team_name",nullable = false)
    val teamName: String,

    @field:OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    val users: List<TeamUserBindings>,

    @field:Column(nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    val createdAt: LocalDateTime,
    @field:Column
    val updatedAt: LocalDateTime
)

fun JpaTeamSchema.toDomainEntity(): Team {
    val domainMemberRoles = this.users.map { binding ->
        TeamMemberRole(
            userId = binding.user.id,
            role = binding.role // UserRole
        )
    }
    return Team(
        teamId = this.teamId,
        teamName = this.teamName,
        memberRoles = domainMemberRoles
    )
}

fun JpaTeamSchema.toDomainEntityWithoutMembers() =
    Team(
        teamId = this.teamId,
        teamName = this.teamName,
        memberRoles = emptyList()
    )
