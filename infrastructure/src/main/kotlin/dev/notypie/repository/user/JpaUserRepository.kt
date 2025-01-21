package dev.notypie.repository.user

import dev.notypie.domain.user.repository.UserRepository

class JpaUserRepository(
    private val jpaUserEntityRepository: JpaUserEntityRepository
) : UserRepository {
}