package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.standup.entity.enums.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class StandupSession(
    val routineUid: UUID,
    val sessionDate: LocalDate,
    val cutoffAt: Instant,
    val status: SessionStatus = SessionStatus.COLLECTING,
    val summaryMessageTs: String? = null,
    val sessionUid: UUID = UUID.randomUUID(),
) {
    private val dispatches: MutableList<SessionDispatch> = mutableListOf()
    private val answers: MutableList<StandupAnswer> = mutableListOf()

    init {
        validate(className = this.javaClass.simpleName) {
            // Enforced at construction so a JPA-loaded row can't resurrect a broken SUMMARIZED state.
            ("summaryMessageTs" of summaryMessageTs).shouldSatisfy(
                "SUMMARIZED session must record summaryMessageTs (message_ts of the summary post)",
            ) {
                status != SessionStatus.SUMMARIZED || !it.isNullOrBlank()
            }
        }
    }

    fun addDispatch(dispatch: SessionDispatch) {
        dispatches.removeIf { it.userId == dispatch.userId }
        dispatches.add(dispatch)
    }

    fun addAnswer(answer: StandupAnswer) {
        answers.removeIf { it.userId == answer.userId }
        answers.add(answer)
    }

    fun dispatchSnapshot(): List<SessionDispatch> = dispatches.toList()

    fun answerSnapshot(): List<StandupAnswer> = answers.toList()
}
