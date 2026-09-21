package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.entity.CommandDetailType

enum class SubmissionIgnoreReason {
    MISSING_SUBMISSION,
    PARSE_REJECTED,
}

fun interface SubmissionParseObserver {
    fun ignored(detailType: CommandDetailType, reason: SubmissionIgnoreReason)

    companion object {
        val NONE: SubmissionParseObserver = SubmissionParseObserver { _, _ -> }
    }
}
