package dev.notypie.application.service.user

import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.user.repository.TeamRepository
import org.springframework.util.MultiValueMap

class DefaultUserServiceImpl(
    private val slackEventBuilder: SlackEventBuilder,
    private val teamRepository: TeamRepository
): UserService {

    override fun createNewTeam(headers: MultiValueMap<String, String>, data: Map<String, String>){
        val body = parseRequestBodyData(data = data)
        val subCommands = body.subCommandList()
    }
}