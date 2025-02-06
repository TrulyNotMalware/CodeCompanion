package dev.notypie.repository.user.schema

import dev.notypie.domain.user.UserRole
import jakarta.persistence.*
import java.time.LocalDateTime


@Entity(name = "team_user_bindings")
class TeamUserBindings(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "team_id")
    val team: JpaTeamSchema,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "user_id")
    val user: JpaUserSchema,

    @field:Enumerated(value = EnumType.STRING)
    @field:Column(nullable = false)
    val role: UserRole,

    @field:Column(nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {

}