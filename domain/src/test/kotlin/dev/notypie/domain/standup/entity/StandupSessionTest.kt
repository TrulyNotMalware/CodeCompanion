package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.error.ValidationExceptionWithName
import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupAnswer
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class StandupSessionTest :
    BehaviorSpec({
        given("StandupSession creation") {
            `when`("status is SUMMARIZED but summaryMessageTs is null") {
                then("rejects so a SUMMARIZED row cannot exist without a Slack message_ts") {
                    shouldThrow<ValidationExceptionWithName> {
                        createStandupSession(
                            status = SessionStatus.SUMMARIZED,
                            summaryMessageTs = null,
                        )
                    }
                }
            }

            `when`("status is SUMMARIZED and summaryMessageTs is set") {
                val session =
                    createStandupSession(
                        status = SessionStatus.SUMMARIZED,
                        summaryMessageTs = "1700000000.000100",
                    )

                then("the row constructs cleanly") {
                    session.status shouldBe SessionStatus.SUMMARIZED
                    session.summaryMessageTs shouldBe "1700000000.000100"
                }
            }
        }

        given("addDispatch") {
            `when`("the same userId is dispatched twice") {
                val session = createStandupSession()
                session.addDispatch(dispatch = createSessionDispatch(userId = "U_DUP"))
                session.addDispatch(
                    dispatch =
                        createSessionDispatch(
                            userId = "U_DUP",
                            dmStatus = DispatchStatus.SENT,
                            dmSentAt = Instant.parse("2026-05-01T01:30:00Z"),
                        ),
                )

                then("the latest row replaces the prior one (no duplicates)") {
                    session.dispatchSnapshot().size shouldBe 1
                    session.dispatchSnapshot().single().dmStatus shouldBe DispatchStatus.SENT
                }
            }
        }

        given("addAnswer") {
            `when`("the same user resubmits") {
                val session = createStandupSession()
                session.addAnswer(answer = createStandupAnswer(userId = "U_RESUB", responses = listOf("first")))
                session.addAnswer(answer = createStandupAnswer(userId = "U_RESUB", responses = listOf("latest")))

                then("the latest submission overwrites the earlier one") {
                    session.answerSnapshot().size shouldBe 1
                    session.answerSnapshot().single().responses shouldBe listOf("latest")
                }
            }
        }

        given("SessionDispatch invariants") {
            `when`("status SENT but dmSentAt is null") {
                then("rejects — SENT must record when it happened") {
                    shouldThrow<ValidationExceptionWithName> {
                        createSessionDispatch(dmStatus = DispatchStatus.SENT, dmSentAt = null)
                    }
                }
            }

            `when`("status FAILED but failureReason is blank") {
                then("rejects — FAILED must capture why") {
                    shouldThrow<ValidationExceptionWithName> {
                        createSessionDispatch(dmStatus = DispatchStatus.FAILED, failureReason = null)
                    }
                }
            }
        }

        given("StandupAnswer invariants") {
            `when`("responses list is empty") {
                then("rejects — at least one response is required") {
                    shouldThrow<ValidationExceptionWithName> {
                        createStandupAnswer(responses = emptyList())
                    }
                }
            }
        }
    })
