package dev.notypie.repository.user.schema

import dev.notypie.domain.user.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.*


@Entity(name = "users")
class JpaUserSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id : Long,

    @field:Column(nullable = false)
    val userIdentifier: UUID,

    @field:Column(nullable = false)
    val slackUserId : String,

    @field:Column(nullable = false)
    val isAdmin: Boolean,

    @field:OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val teams: List<TeamUserBindings>,

    @field:Column(nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    val createdAt: LocalDateTime
)

fun JpaUserSchema.toDomainEntity(): User {
    val teams = this.teams.map { binding ->
        binding.team.toDomainEntity()
    }
    return User(
        id = this.id,
        identifier = this.userIdentifier,
        slackUserId = this.slackUserId,
        teams = teams,
        isAdmin = this.isAdmin
    )
}