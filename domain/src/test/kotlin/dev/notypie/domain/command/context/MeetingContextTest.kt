package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import io.kotest.core.spec.style.BehaviorSpec

class MeetingContextTest : BehaviorSpec(body = {

    val testCommandBasicInfo = createCommandBasicInfo()
    val headers = SlackRequestHeaders()
    val eventQueue = createDomainEventQueue()
    
})