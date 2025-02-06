package dev.notypie.application.controllers

import dev.notypie.application.service.user.UserService
import org.springframework.http.MediaType
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*


@RequestMapping("/api/users")
@RestController
class UserController(
    private val userService: UserService
) {

    @PostMapping(value = ["/team"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun createNewTeam(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>
    ){
        this.userService.createNewTeam(headers = headers, data = data)
    }
}