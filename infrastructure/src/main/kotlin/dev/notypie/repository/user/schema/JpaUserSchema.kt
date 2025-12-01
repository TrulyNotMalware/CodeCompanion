package dev.notypie.repository.user.schema

import dev.notypie.domain.user.UserRole
import dev.notypie.domain.user.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity(name = "users")
class JpaUserSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long,
    @field:Column(nullable = false)
    val slackUserId: String,
    @field:Column(nullable = false)
    val userName: String,
    @field:Column(nullable = false)
    val isAdmin: Boolean,
    @field:Column(nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    val createdAt: LocalDateTime,
)

fun JpaUserSchema.toDomainEntity(role: UserRole? = null): User =
    User(
//        id = id,
        slackUserId = slackUserId,
        userName = userName,
        isAdmin = isAdmin,
        role = role ?: UserRole.MEMBER,
    )

fun User.toJpaEntity(): JpaUserSchema =
    JpaUserSchema(
        id = 0L,
        slackUserId = slackUserId,
        userName = userName,
        isAdmin = isAdmin,
        createdAt = LocalDateTime.now(),
    )
