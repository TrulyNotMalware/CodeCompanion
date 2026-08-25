package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import java.time.Instant

/**
 * One member's submitted standup responses for a session. The order of [responses] mirrors
 * the order of [Routine.questions] at submission time — the persistence layer keeps the
 * 1:1 alignment so the summary renderer can pair `questions[i]` with `responses[i]` without
 * a separate question-id lookup.
 *
 * `responses` size may be < `Routine.questions` size if the user left optional questions
 * blank, but the standup modal currently requires all answers, so in practice the sizes
 * match. The model intentionally does not enforce equality here — that constraint belongs
 * at the modal-submission boundary, not at the entity level.
 */
class StandupAnswer(
    val userId: String,
    responses: List<String>,
    val submittedAt: Instant,
) {
    val responses: List<String> = responses.toList()

    init {
        validate(className = this.javaClass.simpleName) {
            notBlank {
                "userId" of userId
            }
            ("responses" of responses).shouldNotBeEmpty(
                message = "submitted answers must contain at least one response",
            )
        }
    }
}
