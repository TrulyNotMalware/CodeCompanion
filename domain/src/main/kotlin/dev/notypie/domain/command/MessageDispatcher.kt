package dev.notypie.domain.command

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.DelayHandleEventPayloadContents
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SlackEventPayload

interface MessageDispatcher {
    fun dispatch(event: PostEventPayloadContents, commandType: CommandType): CommandOutput

    fun dispatch(event: ActionEventPayloadContents, commandType: CommandType): CommandOutput

    fun dispatch(event: DelayHandleEventPayloadContents, commandType: CommandType): CommandOutput

    fun dispatch(event: SlackEventPayload): CommandOutput
}
