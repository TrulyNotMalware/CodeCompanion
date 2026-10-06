package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.PARTICIPANT_UNIQUE_KEY
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException

private const val MAX_CAUSE_DEPTH = 16

fun RuntimeException.isMeetingWriteConflict(): Boolean =
    this is ConcurrencyFailureException ||
        (this is DataIntegrityViolationException && violatesParticipantUniqueKey())

private fun Throwable.violatesParticipantUniqueKey(): Boolean =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { it.message?.contains(PARTICIPANT_UNIQUE_KEY, ignoreCase = true) == true }
