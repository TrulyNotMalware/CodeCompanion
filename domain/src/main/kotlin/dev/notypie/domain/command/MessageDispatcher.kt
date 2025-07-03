package dev.notypie.domain.command

import dev.notypie.domain.common.event.ActionEventContents
import dev.notypie.domain.common.event.DelayHandleEventContents
import dev.notypie.domain.common.event.PostEventContents
import dev.notypie.domain.common.event.SlackEvent
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType

interface MessageDispatcher {
    fun dispatch(event: PostEventContents, commandType: CommandType): CommandOutput
    fun dispatch(event: ActionEventContents, commandType: CommandType): CommandOutput
    fun dispatch(event: DelayHandleEventContents, commandType: CommandType): CommandOutput
    fun dispatch(event: SlackEvent): CommandOutput
}