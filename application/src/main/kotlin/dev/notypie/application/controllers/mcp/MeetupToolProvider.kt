package dev.notypie.application.controllers.mcp

import dev.notypie.application.controllers.dto.GetMeetupListRequestDto
import dev.notypie.application.service.meeting.MeetingService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

class MeetupToolProvider(
    private val meetingService: MeetingService
) {
    @Tool(
        name = "getMyMeetupLists",
        description = "Fetches meetup lists assigned to the user."
    )
    fun getMyMeetupList(
        @ToolParam(description = "User id", required = true) requestDto: GetMeetupListRequestDto
    ){
        TODO()
    }
}