package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import java.time.Instant

// responses order must mirror Routine.questions order — the renderer pairs them by index.
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
