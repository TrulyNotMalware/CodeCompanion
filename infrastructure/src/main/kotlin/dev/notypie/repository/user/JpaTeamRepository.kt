package dev.notypie.repository.user

import dev.notypie.domain.user.entity.Team
import dev.notypie.domain.user.repository.TeamRepository

class JpaTeamRepository(
    private val teamRepository: JpaTeamEntityRepository,
) : TeamRepository {

    fun insertNewTeam(team: Team){

    }
}