package dev.notypie.domain.meet.entity

import dev.notypie.domain.common.validate
import java.time.LocalDateTime

class Meeting(
    val title: String,
    publisher: String,
    members: Set<String>,
    val reason: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime = startAt.plusHours(1),
    val isCanceled: Boolean = false,
) {
    private val participants: MutableSet<Member> = mutableSetOf()
    val host: Member

    init {

        validate(domain = this.javaClass.simpleName) {
            notBlank {
                "publisher" of publisher
                "title" of title
            }
            "meeting participants" of members.size shouldBeLessThanOrEqualTo MAX_PARTICIPANTS
            "meeting title length" of title shouldBeShorterThan MAX_TITLE_LENGTH
            "meeting reason" of reason shouldBeShorterThan MAX_REASON_LENGTH
            "meeting start time" of startAt shouldBeAfter LocalDateTime.now()
            "meeting end time" of endAt shouldBeAfter startAt
        }
        host = Member(userId = publisher, isHost = false)
        participants.addAll(elements = members.map { Member(userId = it) })
    }

    companion object {
        const val MAX_PARTICIPANTS = 20
        const val MAX_TITLE_LENGTH = 20
        const val MAX_REASON_LENGTH = 200
    }

    fun addParticipant(user: Member) {
        validate(domain = this.javaClass.simpleName) {
            "meeting participants" of (participants.size + 1) shouldBeLessThanOrEqualTo MAX_PARTICIPANTS
        }
        participants.add(user)
    }

    fun memberSnapshot(): List<Member> = participants.toList()

    fun memberIdSnapshot(): Set<String> = participants.map { it.userId }.toSet()
}

class Member(
    val userId: String,
    val isGuest: Boolean = false,
    val isHost: Boolean = false,
) {
    init {
        validate(domain = this.javaClass.simpleName) {
            notBlank {
                "userId" of userId
            }
        }
    }
}
