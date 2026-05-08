package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.standup.entity.enums.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One day's bucket of a [Routine]. The pair `(routineUid, sessionDate)` is unique — there
 * is exactly one session per channel per day (decision A: "channel × day = 1 session").
 *
 * `sessionDate` is interpreted in the routine's [Routine.routineTimezone] — it is the
 * creator's local calendar date for "today's standup". Each member receives their DM at
 * their own local trigger time (per-row [SessionDispatch.dmTriggerAt]), so a single session
 * can span a wide UTC window when members are scattered across timezones.
 *
 * `cutoffAt` is the absolute UTC instant after which no further answers count and the
 * channel summary is posted. The summary listener marks the session [SessionStatus.SUMMARIZED]
 * and writes back [summaryMessageTs] so re-runs (e.g. scheduler retried after restart) can
 * detect a session that already posted and short-circuit.
 */
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
            // SUMMARIZED state must carry the message_ts so we never re-post. Enforced here so
            // JPA loads cannot resurrect a logically-broken row from disk.
            ("summaryMessageTs" of summaryMessageTs).shouldSatisfy(
                "SUMMARIZED session must record summaryMessageTs (Slack message_ts of the summary post)",
            ) {
                status != SessionStatus.SUMMARIZED || !it.isNullOrBlank()
            }
        }
    }

    fun addDispatch(dispatch: SessionDispatch) {
        // Slack user_id is the natural key — duplicates collapse so retried session opens
        // do not double-count members.
        dispatches.removeIf { it.userId == dispatch.userId }
        dispatches.add(dispatch)
    }

    fun addAnswer(answer: StandupAnswer) {
        // A member resubmitting overwrites their prior answer — the modal flow lets users
        // edit before cutoff, and the latest submission is the authoritative one.
        answers.removeIf { it.userId == answer.userId }
        answers.add(answer)
    }

    fun dispatchSnapshot(): List<SessionDispatch> = dispatches.toList()

    fun answerSnapshot(): List<StandupAnswer> = answers.toList()
}
