package dev.notypie.repository.authorization

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.schema.UserCommandRoleSchema
import org.springframework.transaction.annotation.Transactional

open class UserCommandRoleRepositoryImpl(
    private val jpaUserCommandRoleRepository: JpaUserCommandRoleRepository,
) : UserCommandRoleRepository {
    override fun findRole(userId: String): UserRole? = jpaUserCommandRoleRepository.findByUserId(userId = userId)?.role

    override fun findAllGrants(): Map<String, UserRole> =
        jpaUserCommandRoleRepository.findAll().associate { it.userId to it.role }

    @Transactional
    override fun saveRole(userId: String, role: UserRole) {
        val existing = jpaUserCommandRoleRepository.findByUserId(userId = userId)
        if (existing == null) {
            jpaUserCommandRoleRepository.save(UserCommandRoleSchema(userId = userId, role = role))
        } else if (existing.role != role) {
            existing.role = role
            jpaUserCommandRoleRepository.save(existing)
        }
    }

    @Transactional
    override fun deleteRole(userId: String): Boolean = jpaUserCommandRoleRepository.deleteByUserId(userId = userId) > 0L
}
