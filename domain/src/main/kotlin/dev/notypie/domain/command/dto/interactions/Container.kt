package dev.notypie.domain.command.dto.interactions

import java.time.LocalDateTime

data class Container(
    val type: String,
    val messageTime: LocalDateTime,
    val isEphemeral: Boolean,
    val isAppUnfurl: Boolean? = null,
    val appUnfurlUrl: String? = null,
    val threadTs: String? = null,
    val attachmentId: Int? = null,
    val viewId: String? = null,
    val text: String? = null
)