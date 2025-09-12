package dev.notypie.application.configurations

import dev.notypie.application.controllers.mcp.MeetupToolProvider
import dev.notypie.application.service.meeting.MeetingService
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration // TODO add Conditions
class ToolCallbackProviders {
    @Bean
    fun meetupToolProviders(meetingService: MeetingService) = MeetupToolProvider(meetingService = meetingService)

    @Bean
    fun toolCallbackProvider(meetupToolProvider: MeetupToolProvider): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(meetupToolProvider).build()
}
