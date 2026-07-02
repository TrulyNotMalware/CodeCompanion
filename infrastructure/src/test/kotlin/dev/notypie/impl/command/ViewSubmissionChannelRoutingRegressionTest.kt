package dev.notypie.impl.command

import com.slack.api.model.view.View
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.templates.AddParticipantModalIds
import dev.notypie.templates.DeclineReasonModalIds
import dev.notypie.templates.ModalBlockBuilder
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.RescheduleMeetingModalIds
import dev.notypie.templates.StandupModalIds
import dev.notypie.templates.StandupSetupModalIds
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Regression guard for commits 9963f80/2a5c006 (Slack `view_submission` channel routing).
 *
 * Every flow below drives the *real* [ModalTemplateBuilder] to render a modal, pulls the
 * `private_metadata` string out of that actual JSON output (never hand-built), and feeds it into a
 * `view_submission` payload run through the real [SlackInteractionRequestParser]. This couples the
 * writer's token order directly to [SlackInteractionRequestParser.recoverDeliveryChannel] and to the
 * domain contexts that read `routingExtras` positionally: a future token reorder on either side
 * breaks this test instead of silently misrouting a host-confirmation message in production.
 * The final `given` is the executable negative control: it reorders the writer's real tokens and
 * asserts the resulting misroute, proving the positive assertions are order-sensitive.
 */
class ViewSubmissionChannelRoutingRegressionTest :
    BehaviorSpec({
        val templateBuilder =
            ModalTemplateBuilder(
                modalBlockBuilder = ModalBlockBuilder(),
                restRequester = mockk(),
                slackApiToken = TEST_BOT_TOKEN,
            )
        val parser = SlackInteractionRequestParser()

        // Decodes the writer's real output through the Slack SDK's View model (the same technique
        // ModalTemplateBuilderTest uses to validate Block Kit shape) instead of re-deriving the
        // tokenized string by hand -- that hand-building is exactly the gap this test closes.
        fun extractPrivateMetadata(modalViewJson: String): String =
            GsonFactory.createSnakeCase().fromJson(modalViewJson, View::class.java).privateMetadata

        // Mirrors the production sequence in SlackInteractionHandlerImpl: parse the payload, build
        // the transport-neutral InboundCommand, then drive the real domain Command entry point and
        // drain whatever CommandIntent/OutboundMessage effects it emitted.
        fun runThroughDomain(viewSubmissionPayload: String): Pair<CommandOutput, List<CommandEffect>> {
            val interactionPayload = parser.parseStringPayload(payload = viewSubmissionPayload)
            val command =
                InteractionCommand(
                    appName = "routing-regression-test",
                    idempotencyKey = UUID.randomUUID(),
                    commandData = interactionPayload.toInboundCommand(),
                )
            val output = command.handleEvent()
            return output to command.drainIntents()
        }

        given("the reschedule-meeting modal's private_metadata feeds the view_submission reader") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST"
            val originChannel = "C_ORIGIN_RESCHEDULE"
            val modalJson =
                templateBuilder.rescheduleMeetingModalViewJson(
                    meetingUid = meetingUid,
                    currentStartAt = LocalDateTime.of(2026, 7, 1, 14, 30),
                    requesterId = requesterId,
                    channel = originChannel,
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("type, idempotencyKey, and the origin channel are recovered from routingExtras[1]") {
                    interactionPayload.type shouldBe CommandDetailType.MEETING_RESCHEDULE_SUBMIT
                    interactionPayload.idempotencyKey shouldBe meetingUid.toString()
                    interactionPayload.channel.id shouldBe originChannel
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, _) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("CommandOutput threads the same origin channel end-to-end") {
                    output.ok shouldBe true
                    output.channel shouldBe originChannel
                }
            }
        }

        given("the add-participant modal's private_metadata feeds the view_submission reader") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_AP"
            val originChannel = "C_ORIGIN_ADD_PARTICIPANT"
            val modalJson =
                templateBuilder.addParticipantModalViewJson(
                    meetingUid = meetingUid,
                    requesterId = requesterId,
                    channel = originChannel,
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = AddParticipantModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("type, idempotencyKey, and the origin channel are recovered from routingExtras[1]") {
                    interactionPayload.type shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                    interactionPayload.idempotencyKey shouldBe meetingUid.toString()
                    interactionPayload.channel.id shouldBe originChannel
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, _) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("CommandOutput threads the same origin channel end-to-end") {
                    output.ok shouldBe true
                    output.channel shouldBe originChannel
                }
            }
        }

        given("the standup-setup modal's private_metadata feeds the view_submission reader") {
            val setupKey = UUID.randomUUID()
            val creatorId = "U_C"
            val commandChannel = "C_CMD"
            val modalJson =
                templateBuilder.standupSetupModalViewJson(
                    idempotencyKey = setupKey,
                    creatorId = creatorId,
                    commandChannel = commandChannel,
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = StandupSetupModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("routingExtras exposes creator + command channel; basicInfo.channel stays blank") {
                    interactionPayload.type shouldBe CommandDetailType.STANDUP_SETUP_SUBMIT
                    interactionPayload.idempotencyKey shouldBe setupKey.toString()
                    interactionPayload.routingExtras shouldBe listOf(creatorId, commandChannel)
                    // Unlike reschedule/add-participant, this flow's channel rides in routingExtras
                    // and is read directly by the domain context, not by basicInfo.channel.
                    interactionPayload.channel.id shouldBe ""
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("CreateStandupRoutine carries the creator and command channel recovered from routingExtras") {
                    output.ok shouldBe true
                    val create = effects.filterIsInstance<CommandIntent.CreateStandupRoutine>().single()
                    create.creatorId shouldBe creatorId
                    create.commandChannel shouldBe commandChannel
                }
            }
        }

        given("the standup-answer modal's private_metadata feeds the view_submission reader") {
            val sessionUid = UUID.randomUUID()
            val userId = "U_STANDUP"
            val noticeChannel = "D_NOTICE_ANSWER"
            val noticeMessageTs = "1700000000.000700"
            val modalJson =
                templateBuilder.standupModalViewJson(
                    routineName = "Daily Standup",
                    sessionDate = LocalDate.of(2026, 5, 4),
                    sessionUid = sessionUid,
                    userId = userId,
                    noticeChannel = noticeChannel,
                    noticeMessageTs = noticeMessageTs,
                    questions = listOf("What did you do yesterday?"),
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = StandupModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("routingExtras exposes user, notice channel, and message ts in writer order") {
                    interactionPayload.type shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                    interactionPayload.idempotencyKey shouldBe sessionUid.toString()
                    interactionPayload.routingExtras shouldBe listOf(userId, noticeChannel, noticeMessageTs)
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("UpdateMessage collapses the notice DM at the recovered channel and ts") {
                    output.ok shouldBe true
                    val update = effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe noticeChannel
                    update.ref.messageId shouldBe noticeMessageTs
                    update.detailType shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                }
            }
        }

        given("the decline-reason modal's private_metadata feeds the view_submission reader") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT_ROUTING"
            val noticeChannel = "C_NOTICE_DECLINE"
            val noticeMessageTs = "1700000000.000800"
            val modalJson =
                templateBuilder.declineReasonModalViewJson(
                    meetingTitle = "Project sync",
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = participantUserId,
                    noticeChannel = noticeChannel,
                    noticeMessageTs = noticeMessageTs,
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = DeclineReasonModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("routingExtras exposes participant, notice channel, and message ts in writer order") {
                    interactionPayload.type shouldBe CommandDetailType.MEETING_DECLINE_REASON
                    interactionPayload.idempotencyKey shouldBe meetingKey.toString()
                    interactionPayload.routingExtras shouldBe
                        listOf(participantUserId, noticeChannel, noticeMessageTs)
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("UpdateMessage collapses the notice DM at the recovered channel and ts") {
                    output.ok shouldBe true
                    val update = effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe noticeChannel
                    update.ref.messageId shouldBe noticeMessageTs
                    update.detailType shouldBe CommandDetailType.MEETING_DECLINE_REASON
                }
            }
        }

        // Negative control: proves the positive assertions above genuinely depend on the writer's
        // token order. If channel recovery were order-insensitive, this block would fail and the
        // whole suite would be vacuous.
        given("a reschedule private_metadata whose routing tokens were reordered") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_SWAPPED"
            val originChannel = "C_ORIGIN_SWAPPED"
            val modalJson =
                templateBuilder.rescheduleMeetingModalViewJson(
                    meetingUid = meetingUid,
                    currentStartAt = LocalDateTime.of(2026, 7, 1, 14, 30),
                    requesterId = requesterId,
                    channel = originChannel,
                )
            val swappedMetadata =
                extractPrivateMetadata(modalViewJson = modalJson)
                    .split(",")
                    .map { it.trim() }
                    .let { tokens -> tokens.take(2) + tokens.drop(2).reversed() }
                    .joinToString(separator = ",")
            // Real date/time state so the submission takes the actual reschedule path (a stateless
            // payload would fall through to the context's no-op success and prove nothing beyond
            // the basicInfo copy).
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                    privateMetadata = swappedMetadata,
                    stateValues =
                        stateValuesJson(
                            "reschedule_date_block" to datepickerStateJson(selectedDate = "2026-07-10"),
                            "reschedule_time_block" to timepickerStateJson(selectedTime = "15:45"),
                        ),
                )

            `when`("the parser reads the reordered token string") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("the recovered channel is the misrouted requester id, not the origin channel") {
                    interactionPayload.channel.id shouldNotBe originChannel
                    interactionPayload.channel.id shouldBe requesterId
                }
            }

            `when`("the reordered interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("the real reschedule path executes and every routed slot is swapped") {
                    output.ok shouldBe true
                    val reschedule = effects.filterIsInstance<CommandIntent.RescheduleMeeting>().single()
                    reschedule.meetingUid shouldBe meetingUid
                    reschedule.newStartAt shouldBe LocalDateTime.of(2026, 7, 10, 15, 45)
                    // The channel token landed in the requester slot and vice versa.
                    reschedule.requesterId shouldBe originChannel
                    output.channel shouldNotBe originChannel
                    output.channel shouldBe requesterId
                }
            }
        }
    })
