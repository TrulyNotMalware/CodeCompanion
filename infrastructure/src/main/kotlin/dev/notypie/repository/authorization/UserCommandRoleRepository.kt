package dev.notypie.repository.authorization

import dev.notypie.domain.command.authorization.UserRole

interface UserCommandRoleRepository {
    /** The role granted to [userId], or null when no grant exists (callers default to USER). */
    fun findRole(userId: String): UserRole?

    /** Every explicit grant, keyed by user id. */
    fun findAllGrants(): Map<String, UserRole>

    /** Assigns [role] to [userId] (insert or update). */
    fun saveRole(userId: String, role: UserRole)

    /** Removes the grant for [userId]; false when there was none. */
    fun deleteRole(userId: String): Boolean
}
