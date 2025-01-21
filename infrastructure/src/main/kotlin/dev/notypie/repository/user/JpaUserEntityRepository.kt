package dev.notypie.repository.user

import dev.notypie.repository.user.schema.JpaUserSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface JpaUserEntityRepository: JpaRepository<JpaUserSchema, Long> {
}