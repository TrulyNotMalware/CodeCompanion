package dev.notypie.repository.user

import dev.notypie.repository.user.schema.JpaTeamSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface JpaTeamEntityRepository: JpaRepository<JpaTeamSchema, Long> {


}