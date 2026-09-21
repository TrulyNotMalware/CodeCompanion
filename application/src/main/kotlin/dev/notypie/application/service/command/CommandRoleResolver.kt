package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import org.springframework.stereotype.Service

@Service
class CommandRoleResolver(
    appConfig: AppConfig,
    private val userCommandRoleRepository: UserCommandRoleRepository,
) {
    val bootstrapAdmins: Set<String> = appConfig.authorization.bootstrapAdmins.toSet()

    fun resolve(userId: String): UserRole =
        when {
            isBootstrapAdmin(userId = userId) -> UserRole.ADMIN
            else -> userCommandRoleRepository.findRole(userId = userId) ?: UserRole.USER
        }

    fun isBootstrapAdmin(userId: String): Boolean = userId in bootstrapAdmins
}
