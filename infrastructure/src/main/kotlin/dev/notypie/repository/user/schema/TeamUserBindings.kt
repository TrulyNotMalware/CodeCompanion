package dev.notypie.repository.user.schema

import jakarta.persistence.*
import java.time.LocalDateTime


@Entity(name = "team_user_bindings")
class TeamUserBindings(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long,

    @field:Column(nullable = false)
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {

}