package dev.notypie.domain.command.dto.interactions

/**
 * Canonical Slack interaction payload `type` strings. Producer (templates / test fixtures) and
 * consumer (SlackInteractionRequestParser) must reference the same constants so the rename of
 * either end is a compile-time failure rather than a silent runtime drift.
 */
object InteractionTypes {
    const val VIEW_SUBMISSION: String = "view_submission"
    const val BLOCK_ACTIONS: String = "block_actions"
}
