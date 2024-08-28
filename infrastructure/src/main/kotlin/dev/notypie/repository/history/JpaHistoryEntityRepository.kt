package dev.notypie.repository.history

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface JpaHistoryEntityRepository: JpaRepository<JpaHistoryEntity, UUID>