package dev.notypie.application.service.user

import org.springframework.util.MultiValueMap

interface UserService {
    fun createNewTeam(headers: MultiValueMap<String, String>, data: Map<String, String>)
}