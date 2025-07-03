package dev.notypie.domain.command

import dev.notypie.domain.common.event.ActionEventPayloadContents
import dev.notypie.domain.common.event.DelayHandleEventPayloadContents
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SlackEventPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType

interface MessageDispatcher {
    fun dispatch(event: PostEventPayloadContents, commandType: CommandType): CommandOutput
    fun dispatch(event: ActionEventPayloadContents, commandType: CommandType): CommandOutput
    fun dispatch(event: DelayHandleEventPayloadContents, commandType: CommandType): CommandOutput
    fun dispatch(event: SlackEventPayload): CommandOutput
}