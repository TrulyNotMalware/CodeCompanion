package dev.notypie.repository.user

import dev.notypie.repository.user.schema.JpaUserSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface JpaUserEntityRepository: JpaRepository<JpaUserSchema, Long> {

    @Query("SELECT DISTINCT u FROM users u LEFT JOIN FETCH u.teams t WHERE u.slackUserId = :slackUserId")
    fun findBySlackUserIdWithTeams(@Param("slackUserId") slackUserId: String): JpaUserSchema?
}