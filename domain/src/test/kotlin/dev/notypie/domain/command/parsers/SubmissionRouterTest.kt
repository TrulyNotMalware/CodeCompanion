package dev.notypie.domain.command.parsers

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.SubmissionRouter
import dev.notypie.domain.command.entity.context.IgnoredSubmissionContext
import dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveSubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.DeclineReasonSubmissionContext
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext
import dev.notypie.domain.command.entity.isSubmissionRoute
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.inbound.SubmissionIgnoreReason
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

private fun addParticipant(uidRaw: String = UUID.randomUUID().toString()) =
    InboundSubmission.AddParticipant(
        meetingUidRaw = uidRaw,
        requesterId = "U_HOST",
        participantUserIdsRaw = "U_A",
    )

class SubmissionRouterTest :
    BehaviorSpec({
        fun routerWith(observed: MutableList<Pair<CommandDetailType, SubmissionIgnoreReason>>) =
            SubmissionRouter(
                commandBasicInfo = createCommandBasicInfo(),
                intents = createIntentQueue(),
                observer = SubmissionParseObserver { detailType, reason -> observed += detailType to reason },
            )

        given("an interaction carrying a submission variant") {
            `when`("every variant routes") {
                val observed = mutableListOf<Pair<CommandDetailType, SubmissionIgnoreReason>>()
                val router = routerWith(observed = observed)

                fun route(submission: InboundSubmission) =
                    router.route(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.NOTHING,
                                submission = submission,
                            ),
                    )

                then("each parseable variant reaches its own leaf, regardless of the envelope detail type") {
                    route(addParticipant()).shouldBeInstanceOf<AddParticipantSubmissionContext>()
                    route(
                        InboundSubmission.RescheduleMeeting(
                            meetingUidRaw = UUID.randomUUID().toString(),
                            requesterId = "U",
                            date = "2026-10-01",
                            time = "10:00",
                        ),
                    ).shouldBeInstanceOf<RescheduleMeetingSubmissionContext>()
                    route(
                        InboundSubmission.DeclineReason(
                            meetingIdempotencyKeyRaw = UUID.randomUUID().toString(),
                            participantUserId = "U",
                            noticeChannel = "",
                            noticeMessageTs = "",
                            reasonRaw = "VACATION",
                            detailRaw = "",
                        ),
                    ).shouldBeInstanceOf<DeclineReasonSubmissionContext>()
                    route(
                        InboundSubmission.StandupAnswer(
                            sessionUidRaw = UUID.randomUUID().toString(),
                            userId = "U",
                            noticeChannel = "",
                            noticeMessageTs = "",
                            answers = emptyList(),
                        ),
                    ).shouldBeInstanceOf<StandupAnswerSubmissionContext>()
                    route(
                        InboundSubmission.StandupSetup(
                            idempotencyKeyRaw = UUID.randomUUID().toString(),
                            creatorId = "U",
                            commandChannel = "C",
                            name = "n",
                            questionsRaw = "Q",
                            membersRaw = "U_A",
                            summaryChannel = "C_S",
                            weekdaysRaw = "MONDAY",
                            timeRaw = "10:00",
                            cutoffRaw = "120",
                            timezoneRaw = "UTC",
                        ),
                    ).shouldBeInstanceOf<StandupSetupSubmissionContext>()
                    route(InboundSubmission.CveSubscribe(topicKeys = emptyList()))
                        .shouldBeInstanceOf<CveSubscribeSubmissionContext>()
                    route(InboundSubmission.CveUnsubscribe(topicKeys = emptyList()))
                        .shouldBeInstanceOf<CveUnsubscribeSubmissionContext>()
                    observed.shouldBeEmpty()
                }

                then("the variant-derived detail types and the isSubmissionRoute list agree") {
                    listOf(
                        CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                        CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                        CommandDetailType.MEETING_DECLINE_REASON,
                        CommandDetailType.STANDUP_ANSWER_SUBMIT,
                        CommandDetailType.STANDUP_SETUP_SUBMIT,
                        CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                        CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
                    ) shouldContainExactlyInAnyOrder CommandDetailType.entries.filter { it.isSubmissionRoute }
                }
            }

            `when`("the variant is rejected by its parser") {
                val observed = mutableListOf<Pair<CommandDetailType, SubmissionIgnoreReason>>()
                val router = routerWith(observed = observed)
                val context =
                    router.route(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                                submission = addParticipant(uidRaw = "not-a-uuid"),
                            ),
                    )

                then("it routes to Ignored with the variant-derived detail type and reports the rejection") {
                    context.shouldBeInstanceOf<IgnoredSubmissionContext>()
                    observed shouldContainExactly
                        listOf(
                            CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT to SubmissionIgnoreReason.PARSE_REJECTED,
                        )
                }
            }
        }

        given("an interaction without a submission") {
            `when`("every submission route misses its payload") {
                then("each of the routes resolves to Ignored and reports the missing payload") {
                    val submissionRoutes = CommandDetailType.entries.filter { it.isSubmissionRoute }
                    submissionRoutes shouldHaveSize 7
                    submissionRoutes.forEach { detailType ->
                        val observed = mutableListOf<Pair<CommandDetailType, SubmissionIgnoreReason>>()
                        val router = routerWith(observed = observed)
                        val context =
                            router.route(
                                interaction =
                                    createInboundInteraction(
                                        detailType = detailType,
                                        submission = null,
                                    ),
                            )
                        context.shouldBeInstanceOf<IgnoredSubmissionContext>()
                        observed shouldContainExactly
                            listOf(detailType to SubmissionIgnoreReason.MISSING_SUBMISSION)
                    }
                }
            }

            `when`("its detail type is a submission route") {
                val observed = mutableListOf<Pair<CommandDetailType, SubmissionIgnoreReason>>()
                val router = routerWith(observed = observed)
                val context =
                    router.route(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                                submission = null,
                            ),
                    )

                then("it routes to Ignored — never EmptyContext — and reports the missing payload") {
                    context.shouldBeInstanceOf<IgnoredSubmissionContext>()
                    observed shouldContainExactly
                        listOf(CommandDetailType.STANDUP_SETUP_SUBMIT to SubmissionIgnoreReason.MISSING_SUBMISSION)
                }
            }

            `when`("its detail type is not a submission route") {
                val observed = mutableListOf<Pair<CommandDetailType, SubmissionIgnoreReason>>()
                val router = routerWith(observed = observed)
                val context =
                    router.route(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.APPROVAL_REQUEST,
                                submission = null,
                            ),
                    )

                then("the router abstains so detail-type routing continues") {
                    context.shouldBeNull()
                    observed.shouldBeEmpty()
                }
            }
        }

        given("an envelope whose detail type disagrees with the submission variant") {
            `when`("CVE_SUBSCRIBE_SUBMIT carries an AddParticipant submission") {
                val observed = mutableListOf<Pair<CommandDetailType, SubmissionIgnoreReason>>()
                val router = routerWith(observed = observed)
                val context =
                    router.route(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                                submission = addParticipant(),
                            ),
                    )

                then("the variant wins: the envelope discriminator never decides a submission route") {
                    val leaf = context.shouldBeInstanceOf<AddParticipantSubmissionContext>()
                    leaf.commandDetailType shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                }
            }
        }
    })
