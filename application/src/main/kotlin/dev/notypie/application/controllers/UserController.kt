package dev.notypie.application.controllers

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RequestMapping("/api/users")
@RestController
@ConditionalOnProperty(name = ["slack.app.mode.standalone"], havingValue = "true", matchIfMissing = false)
class UserController {

}