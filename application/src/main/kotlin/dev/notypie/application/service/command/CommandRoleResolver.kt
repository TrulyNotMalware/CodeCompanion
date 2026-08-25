package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import org.springframework.stereotype.Service

/**
 * Resolves the [UserRole] for a command actor: configured bootstrap admins win, then the
 * `user_command_role` grant, then the [UserRole.USER] default.
 */
@Service
class CommandRoleResolver(
    appConfig: AppConfig,
    private val userCommandRoleRepository: UserCommandRoleRepository,
) {
    /** Config-managed ADMIN ids; immutable from chat, so role management must treat them specially. */
    val bootstrapAdmins: Set<String> = appConfig.authorization.bootstrapAdmins.toSet()

    fun resolve(userId: String): UserRole =
        when {
            isBootstrapAdmin(userId = userId) -> UserRole.ADMIN
            else -> userCommandRoleRepository.findRole(userId = userId) ?: UserRole.USER
        }

    fun isBootstrapAdmin(userId: String): Boolean = userId in bootstrapAdmins
}
