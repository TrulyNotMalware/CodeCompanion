package dev.notypie.application.service.interaction

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.SubmissionIgnoreReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class MeteredSubmissionParseObserverTest :
    BehaviorSpec({
        given("a metered observer bound to a registry") {
            val registry = SimpleMeterRegistry()
            val observer = MeteredSubmissionParseObserver(meterRegistry = registry)

            `when`("ignored submissions are reported") {
                observer.ignored(
                    detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                    reason = SubmissionIgnoreReason.PARSE_REJECTED,
                )
                observer.ignored(
                    detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                    reason = SubmissionIgnoreReason.PARSE_REJECTED,
                )
                observer.ignored(
                    detailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    reason = SubmissionIgnoreReason.MISSING_SUBMISSION,
                )

                then("one counter per detail-type × reason pair accumulates") {
                    registry
                        .counter(
                            "codecompanion.submission.ignored",
                            "detail_type",
                            CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT.name,
                            "reason",
                            SubmissionIgnoreReason.PARSE_REJECTED.name,
                        ).count() shouldBe 2.0
                    registry
                        .counter(
                            "codecompanion.submission.ignored",
                            "detail_type",
                            CommandDetailType.STANDUP_SETUP_SUBMIT.name,
                            "reason",
                            SubmissionIgnoreReason.MISSING_SUBMISSION.name,
                        ).count() shouldBe 1.0
                }
            }
        }
    })
