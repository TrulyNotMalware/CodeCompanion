package dev.notypie.impl.command.event

import dev.notypie.domain.command.dto.response.CommandOutput

interface MessageDispatcher {
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
