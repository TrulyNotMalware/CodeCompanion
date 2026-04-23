package dev.notypie.domain.command.dto.interactions

import java.time.Instant

data class Container(
    val type: String,
    val messageTime: Instant,
    val isEphemeral: Boolean,
    val isAppUnfurl: Boolean? = null,
    val appUnfurlUrl: String? = null,
    val threadTs: String? = null,
    val attachmentId: Int? = null,
    val viewId: String? = null,
    val text: String? = null,
    /**
     * Raw `message_ts` from Slack's block_actions container. Needed by flows that must call
     * `chat.update` on the originating message (e.g. the decline-reason flow collapses the
     * Accept/Deny notice into a "You declined — reason: X" summary once the modal submits).
     * Null for payloads that don't carry one (view_submission, ephemeral-only paths).
     */
    val messageTs: String? = null,
)

data class Channel(
    val id: String,
    val name: String,
)

data class Enterprise(
    val id: String,
    val name: String,
)
