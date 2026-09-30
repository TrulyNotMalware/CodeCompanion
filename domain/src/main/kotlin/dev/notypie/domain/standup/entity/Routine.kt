package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class Routine(
    val name: String,
    val creatorId: String,
    val commandChannel: String,
    val summaryChannel: String,
    questions: List<String>,
    val triggerLocalTime: LocalTime,
    val cutoffOffset: Duration,
    weekdays: Set<DayOfWeek>,
    val routineTimezone: ZoneId,
    val isActive: Boolean = true,
    val routineUid: UUID = UUID.randomUUID(),
) {
    val questions: List<String> = questions.toList()
    val weekdays: Set<DayOfWeek> = weekdays.toSet()
    private val members: MutableSet<RoutineMember> = mutableSetOf()

    init {
        validate(className = this.javaClass.simpleName) {
            notBlank {
                "name" of name
                "creatorId" of creatorId
                "commandChannel" of commandChannel
                "summaryChannel" of summaryChannel
            }
            "name length" of name shouldBeShorterThan MAX_NAME_LENGTH
            "questions" of questions shouldHaveMinSize MIN_QUESTIONS
            "questions" of questions shouldHaveMaxSize MAX_QUESTIONS
            ("weekdays" of weekdays).shouldNotBeEmpty(message = "weekdays must include at least one day")
            ("cutoffOffset" of cutoffOffset).shouldSatisfy("must be positive") { it > Duration.ZERO }
            // A session covers one day, so a cutoff past 24 h would overlap the next session; an unbounded value
            // also overflows the scheduler's Instant arithmetic and MariaDB DATETIME (review T2).
            ("cutoffOffset" of cutoffOffset).shouldSatisfy("must be at most $MAX_CUTOFF_MINUTES minutes") {
                it <= MAX_CUTOFF_OFFSET
            }

            // Questions are joined with `\n` in persistence and rendered into Slack modal labels.
            questions.forEachIndexed { index, question ->
                ("questions[$index]" of question).shouldSatisfy("must not be blank") { it.isNotBlank() }
                ("questions[$index]" of question).shouldSatisfy("must not contain a newline character") {
                    !it.contains('\n')
                }
                "questions[$index]" of question shouldBeShorterThan MAX_QUESTION_LENGTH
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 60
        const val MIN_QUESTIONS: Int = 1
        const val MAX_QUESTIONS: Int = 8
        const val MAX_QUESTION_LENGTH: Int = 200
        const val MAX_MEMBERS: Int = 30
        const val MIN_CUTOFF_MINUTES: Long = 1L
        const val MAX_CUTOFF_MINUTES: Long = 1440L
        val MAX_CUTOFF_OFFSET: Duration = Duration.ofMinutes(MAX_CUTOFF_MINUTES)
        val DEFAULT_WEEKDAYS: Set<DayOfWeek> =
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            )
        val DEFAULT_CUTOFF_OFFSET: Duration = Duration.ofHours(1L)
    }

    // Capacity counts distinct users: re-adding an existing id replaces it, so a full routine must accept it.
    fun addMember(member: RoutineMember) {
        val othersCount = members.count { it.userId != member.userId }
        validate(className = this.javaClass.simpleName) {
            "members" of (othersCount + 1) shouldBeLessThanOrEqualTo MAX_MEMBERS
        }
        members.removeIf { it.userId == member.userId }
        members.add(member)
    }

    fun memberSnapshot(): List<RoutineMember> = members.toList()

    fun memberIdSnapshot(): Set<String> = members.map { it.userId }.toSet()
}
