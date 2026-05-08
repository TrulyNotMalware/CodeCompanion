package dev.notypie.domain.standup.entity

import dev.notypie.domain.command.exceptions.ValidationExceptionWithName
import dev.notypie.domain.standup.createRoutine
import dev.notypie.domain.standup.createRoutineMember
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

class RoutineTest :
    BehaviorSpec({
        given("Routine creation with valid data") {
            `when`("all fields are within limits") {
                val routine =
                    createRoutine(
                        name = "Daily Standup",
                        questions = listOf("Yesterday?", "Today?", "Blockers?"),
                        triggerLocalTime = LocalTime.of(10, 0),
                        weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                        routineTimezone = ZoneId.of("Asia/Seoul"),
                    )

                then("questions list is preserved in order") {
                    routine.questions shouldBe listOf("Yesterday?", "Today?", "Blockers?")
                }

                then("weekdays set is preserved") {
                    routine.weekdays shouldBe setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
                }

                then("isActive defaults to true") {
                    routine.isActive shouldBe true
                }

                then("memberSnapshot starts empty") {
                    routine.memberSnapshot().size shouldBe 0
                }
            }
        }

        given("Routine creation with invalid data") {
            `when`("name is blank") {
                then("throws ValidationException") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(name = "")
                    }
                }
            }

            `when`("questions list is empty") {
                then("throws ValidationException — at least one question is required") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(questions = emptyList())
                    }
                }
            }

            `when`("a question is blank") {
                then("throws ValidationException") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(questions = listOf(" "))
                    }
                }
            }

            `when`("a question contains a newline character") {
                then("rejects to keep the persistence-layer delimiter unambiguous") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(questions = listOf("line1\nline2"))
                    }
                }
            }

            `when`("a question is too long") {
                then("throws ValidationException") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(questions = listOf("q".repeat(Routine.MAX_QUESTION_LENGTH + 1)))
                    }
                }
            }

            `when`("weekdays is empty") {
                then("rejects — a routine must fire on at least one weekday") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(weekdays = emptySet())
                    }
                }
            }

            `when`("cutoffOffset is non-positive") {
                then("rejects — cutoff must run after the trigger") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(cutoffOffset = Duration.ZERO)
                    }
                }
            }

            `when`("too many questions are provided") {
                then("rejects beyond MAX_QUESTIONS to keep the modal compact") {
                    shouldThrow<ValidationExceptionWithName> {
                        createRoutine(questions = (1..(Routine.MAX_QUESTIONS + 1)).map { "Q$it" })
                    }
                }
            }
        }

        given("addMember") {
            `when`("the same userId is added twice") {
                val routine = createRoutine()
                routine.addMember(member = createRoutineMember(userId = "U_DUP", userTimezone = ZoneId.of("UTC")))
                routine.addMember(
                    member = createRoutineMember(userId = "U_DUP", userTimezone = ZoneId.of("Asia/Tokyo")),
                )

                then("only the latest entry is kept (no duplicates)") {
                    routine.memberSnapshot().size shouldBe 1
                    routine.memberSnapshot().single().userTimezone shouldBe ZoneId.of("Asia/Tokyo")
                }
            }

            `when`("more than MAX_MEMBERS members are added") {
                val routine = createRoutine()
                (1..Routine.MAX_MEMBERS).forEach { idx ->
                    routine.addMember(
                        member = createRoutineMember(userId = "U_$idx", userTimezone = ZoneId.of("UTC")),
                    )
                }

                then("the next addMember rejects to keep the standup compact") {
                    shouldThrow<ValidationExceptionWithName> {
                        routine.addMember(
                            member = createRoutineMember(userId = "U_OVER", userTimezone = ZoneId.of("UTC")),
                        )
                    }
                }
            }
        }
    })
