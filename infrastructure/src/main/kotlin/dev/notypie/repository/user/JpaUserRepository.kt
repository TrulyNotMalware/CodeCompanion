package dev.notypie.repository.user

import dev.notypie.domain.user.entity.User
import dev.notypie.domain.user.repository.UserRepository
import dev.notypie.repository.user.schema.toDomainEntity

class JpaUserRepository(
    private val jpaUserEntityRepository: JpaUserEntityRepository,
    private val jpaTeamEntityRepository: JpaTeamEntityRepository
) : UserRepository {

    //Remove user side association
//    fun selectUser(slackUserId: String): User{
//        val jpaUserSchema = this.jpaUserEntityRepository.findBySlackUserIdWithTeams(slackUserId = slackUserId)
//            ?: throw IllegalArgumentException("User not found.")
//        return jpaUserSchema.toDomainEntity()
//    }
}