package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.entity.CommandDetailType

/** Low-cardinality reason a submission route produced no executable context. */
enum class SubmissionIgnoreReason {
    MISSING_SUBMISSION,
    PARSE_REJECTED,
}

/**
 * Observation port for submissions that fall open (success with no effects). The domain stays
 * dependency-free; the application may bind this to metrics/logging. Expected per-flow defaults
 * (e.g. an empty standup answer) are NOT ignores and never reach this port.
 */
fun interface SubmissionParseObserver {
    fun ignored(detailType: CommandDetailType, reason: SubmissionIgnoreReason)

    companion object {
        val NONE: SubmissionParseObserver = SubmissionParseObserver { _, _ -> }
    }
}
