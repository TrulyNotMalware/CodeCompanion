package dev.notypie.repository.authorization

import dev.notypie.domain.command.authorization.UserRole

interface UserCommandRoleRepository {
    fun findRole(userId: String): UserRole?

    fun findAllGrants(): Map<String, UserRole>

    fun saveRole(userId: String, role: UserRole)

    fun deleteRole(userId: String): Boolean
}
