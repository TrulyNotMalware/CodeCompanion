package dev.notypie.repository.user.schema

import jakarta.persistence.*
import java.util.UUID

@Entity(name = "teams")
class JpaTeamSchema(

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long,

    @field:Column(nullable = false)
    val teamId : UUID,

    @field:Column(nullable = false)
    val teamName: String,


) {
}