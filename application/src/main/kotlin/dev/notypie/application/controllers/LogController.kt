package dev.notypie.application.controllers

import dev.notypie.domain.monitor.Monitoring
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// FIXME THIS IS EXAMPLE
@RestController
@RequestMapping("/api/log")
class LogController(
    private val monitoring: Monitoring
) {

    @GetMapping(value = [""], produces = [ MediaType.APPLICATION_JSON_VALUE] )
    fun test(){
        this.monitoring.getLog()
    }
}