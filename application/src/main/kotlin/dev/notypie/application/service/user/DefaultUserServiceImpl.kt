package dev.notypie.application.service.user

import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.user.repository.UserRepository
import org.springframework.util.MultiValueMap

class DefaultUserServiceImpl(
    private val slackApiRequester: SlackApiRequester,
    private val userRepository: UserRepository
): UserService {

    override fun createNewTeam(headers: MultiValueMap<String, String>, data: Map<String, String>){
        val slackCommandData: SlackCommandData = parseRequestBodyData(headers, data)
        println(slackCommandData)
    }
}