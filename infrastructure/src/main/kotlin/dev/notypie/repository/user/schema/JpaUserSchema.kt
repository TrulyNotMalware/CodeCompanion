package dev.notypie.repository.user.schema

import dev.notypie.domain.user.UserRole
import jakarta.persistence.*
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

    @field:Enumerated(value = EnumType.STRING)
    @field:Column(nullable = false)
    val role: UserRole
)

fun JpaUserSchema.toDomainEntity(){

}