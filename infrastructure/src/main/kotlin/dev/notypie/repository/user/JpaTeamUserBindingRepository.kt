package dev.notypie.repository.user

import dev.notypie.repository.user.schema.TeamUserBindings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface JpaTeamUserBindingRepository : JpaRepository<TeamUserBindings, Long> {

}