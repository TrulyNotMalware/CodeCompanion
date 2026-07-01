package dev.notypie.impl.command.slack

data class States(
    val isSelected: Boolean = false,
    val type: ActionElementTypes,
    val selectedValue: String = "",
    /**
     * The Slack `block_id` of the block that produced this state. Captured by the
     * view_submission parser so that callers needing deterministic ordering — chiefly the
     * standup-answer flow, which pairs `responses[i]` with `routine.questions[i]` — can sort
     * states by their declared block id (e.g. `standup_q_<index>`) instead of relying on the
     * iteration order of Slack's `view.state.values` map, which is not guaranteed.
     */
    val blockId: String? = null,
)
