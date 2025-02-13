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

    @field:Column(name = "slack_team_domain", nullable = false)
    val slackTeamDomain: String,

    @field:Column(name = "slack_team_id", nullable = false)
    val slackTeamId: String,

    @field:Column(name = "team_name",nullable = false)
    val teamName: String,

    @field:Column(name = "description")
    val description: String?,

    @field:OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    val users: List<TeamUserBindings>,

    @field:Column(nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    val createdAt: LocalDateTime,
    @field:Column
    val updatedAt: LocalDateTime? = null
)

fun Team.toJpaEntity(): JpaTeamSchema =
    JpaTeamSchema(
        id = this.id,
        teamId = this.teamId,
        slackTeamDomain = this.teamDomain,
        slackTeamId = this.slackTeamId,
        teamName = this.teamName,
        description = this.description,
        users = this.members.map { member ->
            TeamUserBindings(
                id = 0L,
                team = this.toJpaEntityWithoutMembers(),
                user = member.toJpaEntity(),
                role = member.role,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            )
        },
        createdAt = LocalDateTime.now()
    )

fun JpaTeamSchema.toDomainEntity() =
    Team(
        id = this.id,
        teamId = this.teamId,
        teamName = this.teamName,
        teamDomain = this.slackTeamDomain,
        slackTeamId = this.slackTeamId,
        description = this.description,
        members = this.users.map { binding ->
            binding.user.toDomainEntity(role = binding.role)
        }
    )


fun Team.toJpaEntityWithoutMembers(): JpaTeamSchema =
    JpaTeamSchema(
        id = this.id,
        teamId = this.teamId,
        slackTeamDomain = this.teamDomain,
        slackTeamId = this.slackTeamId,
        teamName = this.teamName,
        description = this.description,
        users = listOf(),
        createdAt = LocalDateTime.now()
    )

fun JpaTeamSchema.toDomainEntityWithoutMembers() =
    Team(
        teamId = this.teamId,
        teamName = this.teamName,
        members = emptyList(),
        slackTeamId = this.slackTeamId,
        teamDomain = this.slackTeamDomain,
        description = this.description
    )
