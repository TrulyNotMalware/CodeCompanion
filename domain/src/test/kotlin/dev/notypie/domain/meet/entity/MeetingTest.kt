package dev.notypie.domain.meet.entity

import dev.notypie.domain.command.exceptions.ValidationExceptionWithName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MeetingTest :
    BehaviorSpec({
        val futureStart = LocalDateTime.now().plusDays(1)
        val futureEnd = futureStart.plusHours(1)

        given("Meeting creation with valid data") {
            `when`("all fields are valid") {
                val meeting =
                    Meeting(
                        title = "Standup",
                        publisher = "U001",
                        members = setOf("U002", "U003"),
                        reason = "Daily sync",
                        startAt = futureStart,
                        endAt = futureEnd,
                    )

                then("host should be the publisher") {
                    meeting.host.userId shouldBe "U001"
                }

                then("memberSnapshot should contain all members") {
                    meeting.memberSnapshot().size shouldBe 2
                }

                then("memberIdSnapshot should contain member ids") {
                    meeting.memberIdSnapshot() shouldBe setOf("U002", "U003")
                }

                then("isCanceled should be false by default") {
                    meeting.isCanceled shouldBe false
                }
            }

            `when`("endAt defaults to startAt + 1 hour") {
                val meeting =
                    Meeting(
                        title = "Quick sync",
                        publisher = "U001",
                        members = setOf("U002"),
                        reason = "Brief",
                        startAt = futureStart,
                    )

                then("endAt should be startAt + 1 hour") {
                    meeting.endAt shouldBe futureStart.plusHours(1)
                }
            }
        }

        given("Meeting creation with invalid data") {
            `when`("publisher is blank") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "Test",
                            publisher = "",
                            members = setOf("U002"),
                            reason = "reason",
                            startAt = futureStart,
                            endAt = futureEnd,
                        )
                    }
                }
            }

            `when`("title is blank") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "",
                            publisher = "U001",
                            members = setOf("U002"),
                            reason = "reason",
                            startAt = futureStart,
                            endAt = futureEnd,
                        )
                    }
                }
            }

            `when`("title exceeds MAX_TITLE_LENGTH") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "A".repeat(Meeting.MAX_TITLE_LENGTH + 1),
                            publisher = "U001",
                            members = setOf("U002"),
                            reason = "reason",
                            startAt = futureStart,
                            endAt = futureEnd,
                        )
                    }
                }
            }

            `when`("reason exceeds MAX_REASON_LENGTH") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "Test",
                            publisher = "U001",
                            members = setOf("U002"),
                            reason = "R".repeat(Meeting.MAX_REASON_LENGTH + 1),
                            startAt = futureStart,
                            endAt = futureEnd,
                        )
                    }
                }
            }

            `when`("participants exceed MAX_PARTICIPANTS") {
                then("should throw ValidationExceptionWithName") {
                    val tooManyMembers = (1..Meeting.MAX_PARTICIPANTS + 1).map { "U$it" }.toSet()
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "Test",
                            publisher = "U001",
                            members = tooManyMembers,
                            reason = "reason",
                            startAt = futureStart,
                            endAt = futureEnd,
                        )
                    }
                }
            }

            `when`("startAt is in the past") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "Test",
                            publisher = "U001",
                            members = setOf("U002"),
                            reason = "reason",
                            startAt = LocalDateTime.now().minusDays(1),
                            endAt = LocalDateTime.now(),
                        )
                    }
                }
            }

            `when`("endAt is before startAt") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Meeting(
                            title = "Test",
                            publisher = "U001",
                            members = setOf("U002"),
                            reason = "reason",
                            startAt = futureStart,
                            endAt = futureStart.minusHours(1),
                        )
                    }
                }
            }
        }

        given("Meeting addParticipant") {
            `when`("adding a participant within limit") {
                val meeting =
                    Meeting(
                        title = "Test",
                        publisher = "U001",
                        members = setOf("U002"),
                        reason = "reason",
                        startAt = futureStart,
                        endAt = futureEnd,
                    )

                meeting.addParticipant(user = Member(userId = "U003"))

                then("memberSnapshot should include the new participant") {
                    meeting.memberSnapshot().size shouldBe 2
                    meeting.memberIdSnapshot().contains("U003") shouldBe true
                }
            }

            `when`("adding a participant exceeding MAX_PARTICIPANTS") {
                val members = (1..Meeting.MAX_PARTICIPANTS).map { "U$it" }.toSet()
                val meeting =
                    Meeting(
                        title = "Test",
                        publisher = "U_HOST",
                        members = members,
                        reason = "reason",
                        startAt = futureStart,
                        endAt = futureEnd,
                    )

                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        meeting.addParticipant(user = Member(userId = "U_OVERFLOW"))
                    }
                }
            }
        }

        given("Member creation") {
            `when`("userId is blank") {
                then("should throw ValidationExceptionWithName") {
                    shouldThrow<ValidationExceptionWithName> {
                        Member(userId = "")
                    }
                }
            }

            `when`("userId is valid") {
                val member = Member(userId = "U001", isGuest = true)

                then("should have correct fields") {
                    member.userId shouldBe "U001"
                    member.isGuest shouldBe true
                    member.isHost shouldBe false
                }
            }
        }
    })
