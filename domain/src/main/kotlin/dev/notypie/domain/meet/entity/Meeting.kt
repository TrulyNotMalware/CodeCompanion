package dev.notypie.domain.meet.entity

import dev.notypie.domain.common.validate
import java.time.LocalDateTime

class Meeting(
    val creator: String,
    val title: String,
    val reason: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime = startAt.plusHours(1),
    val isCanceled: Boolean = false,
) {
    private val participants: MutableList<MeetingParticipants> = mutableListOf()

    init {
        validate {
        }
    }

    companion object {
        const val UNAUTHORIZED_CHARACTERS =
            "('.+--)|(--)|(%7C)|(;)|(" +
                "\\b(SELECT|INSERT|UPDATE|DELETE|DROP|TRUNCATE|CREATE|ALTER|GRANT|REVOKE|UNION|ALL)\\b)"
    }

    fun addNewParticipant() {}
}

internal data class MeetingParticipants(
    val participantId: String,
    val participantName: String,
)
