package dev.notypie.domain.command

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.DelayHandleEventPayloadContents
import dev.notypie.domain.command.entity.event.OpenViewPayloadContents
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SlackEventPayload

interface MessageDispatcher {
    fun dispatch(event: PostEventPayloadContents, commandType: CommandType): CommandOutput

    fun dispatch(event: ActionEventPayloadContents, commandType: CommandType): CommandOutput

    fun dispatch(event: DelayHandleEventPayloadContents, commandType: CommandType): CommandOutput

    fun dispatch(event: SlackEventPayload): CommandOutput

    /**
     * Synchronous Slack API dispatch for payloads that cannot tolerate outbox-relay latency —
     * currently only `views.open`, whose `trigger_id` expires 3 seconds after issuance.
     * Implementations must invoke the Slack API inline on the caller's thread and must NOT
     * retry beyond what the underlying SDK already does internally (retrying after
     * `trigger_id` expiry is pointless). On failure the implementation is expected to
     * publish [dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent] so the
     * application layer can record the decline with a fallback reason.
     */
    fun dispatchImmediate(event: OpenViewPayloadContents): CommandOutput
}
