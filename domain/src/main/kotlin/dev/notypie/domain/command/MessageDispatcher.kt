package dev.notypie.domain.command

import dev.notypie.domain.command.dto.ActionEventContents
import dev.notypie.domain.command.dto.DelayHandleEventContents
import dev.notypie.domain.command.dto.PostEventContents
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType

interface MessageDispatcher {
    fun dispatch(event: PostEventContents, commandType: CommandType): CommandOutput
    fun dispatch(event: ActionEventContents, commandType: CommandType): CommandOutput
    fun dispatch(event: DelayHandleEventContents, commandType: CommandType): CommandOutput
}