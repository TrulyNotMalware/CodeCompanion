package dev.notypie.repository.authorization

import dev.notypie.repository.authorization.schema.UserCommandRoleSchema
import org.springframework.data.jpa.repository.JpaRepository

interface JpaUserCommandRoleRepository : JpaRepository<UserCommandRoleSchema, Long> {
    fun findByUserId(userId: String): UserCommandRoleSchema?

    fun deleteByUserId(userId: String): Long
}
