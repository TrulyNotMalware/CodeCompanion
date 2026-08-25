package dev.notypie.repository.authorization.schema

import dev.notypie.domain.command.authorization.UserRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Role grant for one workspace member. Absence of a row means the default [UserRole.USER];
 * rows are managed directly in the DB for now (no in-bot grant commands yet).
 */
@Entity(name = "user_command_role")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_user_command_role_user_id", columnNames = ["user_id"]),
    ],
)
class UserCommandRoleSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "user_id", nullable = false, length = 64)
    val userId: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false, length = 32)
    var role: UserRole,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)
