package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.UUID

class OutboundMessageTest :
    BehaviorSpec({
        given("the transport-neutral value classes") {
            `when`("each is constructed") {
                then("the @JvmInline value classes wrap the raw value") {
                    ConversationTarget(id = "C123").id shouldBe "C123"
                    UserRef(id = "U123").id shouldBe "U123"
                    ModalOpenHandle(raw = "trigger.1").raw shouldBe "trigger.1"
                    ResponseReplaceHandle(raw = "https://response.url").raw shouldBe "https://response.url"
                }

                then("MessageRef carries the conversation and message id") {
                    val ref = MessageRef(conversation = ConversationTarget(id = "C1"), messageId = "1700.0001")
                    ref.conversation shouldBe ConversationTarget(id = "C1")
                    ref.messageId shouldBe "1700.0001"
                }
            }
        }

        given("each MessageContent variant") {
            `when`("constructed") {
                then("fields round-trip") {
                    val text = MessageContent.Text(headline = "Hi", markdown = "*bold*")
                    text.headline shouldBe "Hi"
                    text.markdown shouldBe "*bold*"

                    val error =
                        MessageContent.ErrorNotice(
                            className = "IllegalStateException",
                            message = "boom",
                            details = "stack",
                        )
                    error.className shouldBe "IllegalStateException"
                    error.message shouldBe "boom"
                    error.details shouldBe "stack"

                    val info =
                        TimeScheduleInfo(
                            scheduleName = "Sync",
                            startTime = LocalDateTime.of(2026, 1, 1, 9, 0),
                            endTime = LocalDateTime.of(2026, 1, 1, 10, 0),
                        )
                    val schedule = MessageContent.Schedule(headline = "Meeting", info = info)
                    schedule.headline shouldBe "Meeting"
                    schedule.info shouldBe info

                    val selection =
                        SelectionContents(
                            title = "Pick",
                            explanation = "Choose one",
                            placeholderText = "...",
                            contents = emptyList(),
                        )
                    val reason = TextInputContents(title = "Reason", placeholderText = "Why?")
                    val approval = approvalContents()
                    val form =
                        MessageContent.Form(
                            headline = "Form",
                            fields = listOf(selection),
                            reason = reason,
                            approval = approval,
                        )
                    form.headline shouldBe "Form"
                    form.fields shouldBe listOf(selection)
                    form.reason shouldBe reason
                    form.approval shouldBe approval

                    val meetingRequest = MessageContent.MeetingRequest(approval = approval)
                    meetingRequest.approval shouldBe approval
                }
            }
        }

        given("each ModalForm variant") {
            val meetingUid = UUID.randomUUID()
            `when`("constructed") {
                then("fields round-trip") {
                    val rescheduleChannel = ConversationTarget(id = "C_RESCHEDULE")
                    val reschedule =
                        ModalForm.Reschedule(
                            meetingUid = meetingUid,
                            requesterId = "U1",
                            channel = rescheduleChannel,
                        )
                    reschedule.meetingUid shouldBe meetingUid
                    reschedule.requesterId shouldBe "U1"
                    reschedule.channel shouldBe rescheduleChannel

                    val addChannel = ConversationTarget(id = "C_ADD")
                    val addParticipant =
                        ModalForm.AddParticipant(
                            meetingUid = meetingUid,
                            requesterId = "U2",
                            channel = addChannel,
                        )
                    addParticipant.meetingUid shouldBe meetingUid
                    addParticipant.requesterId shouldBe "U2"
                    addParticipant.channel shouldBe addChannel

                    val sessionUid = UUID.randomUUID()
                    val routineUid = UUID.randomUUID()
                    val fillNotice =
                        MessageRef(conversation = ConversationTarget(id = "D_FILL"), messageId = "1700.0002")
                    val standupFill =
                        ModalForm.StandupFill(
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            requesterId = "U3",
                            originNotice = fillNotice,
                        )
                    standupFill.sessionUid shouldBe sessionUid
                    standupFill.routineUid shouldBe routineUid
                    standupFill.requesterId shouldBe "U3"
                    standupFill.originNotice shouldBe fillNotice

                    val setupChannel = ConversationTarget(id = "C_SETUP")
                    val standupSetup = ModalForm.StandupSetup(creatorId = "U4", commandChannel = setupChannel)
                    standupSetup.creatorId shouldBe "U4"
                    standupSetup.commandChannel shouldBe setupChannel

                    val notice = MessageRef(conversation = ConversationTarget(id = "C1"), messageId = "1700.0001")
                    val declineReason =
                        ModalForm.DeclineReason(
                            meetingIdempotencyKey = meetingUid,
                            participantUserId = "U5",
                            meetingTitle = "Sync",
                            originNotice = notice,
                        )
                    declineReason.meetingIdempotencyKey shouldBe meetingUid
                    declineReason.participantUserId shouldBe "U5"
                    declineReason.meetingTitle shouldBe "Sync"
                    declineReason.originNotice shouldBe notice
                }
            }
        }

        given("each OutboundMessage variant") {
            val target = ConversationTarget(id = "C1")
            val recipient = UserRef(id = "U1")
            val content = MessageContent.Text(headline = null, markdown = "hello")
            `when`("constructed") {
                then("fields round-trip") {
                    val channel = OutboundMessage.ChannelMessage(target = target, content = content)
                    channel.target shouldBe target
                    channel.content shouldBe content

                    val ephemeral =
                        OutboundMessage.Ephemeral(target = target, recipient = recipient, content = content)
                    ephemeral.target shouldBe target
                    ephemeral.recipient shouldBe recipient
                    ephemeral.content shouldBe content

                    // A null recipient means "the command publisher", mirroring today's targetUserId = null.
                    val ephemeralToPublisher = OutboundMessage.Ephemeral(target = target, content = content)
                    ephemeralToPublisher.recipient shouldBe null

                    val direct = OutboundMessage.DirectMessage(recipient = recipient, content = content)
                    direct.recipient shouldBe recipient
                    direct.content shouldBe content

                    val ref = MessageRef(conversation = target, messageId = "1700.0001")
                    val update =
                        OutboundMessage.UpdateMessage(
                            ref = ref,
                            content = content,
                            detailType = CommandDetailType.MEETING_DECLINE_REASON,
                        )
                    update.ref shouldBe ref
                    update.content shouldBe content
                    update.detailType shouldBe CommandDetailType.MEETING_DECLINE_REASON

                    val handle = ResponseReplaceHandle(raw = "https://response.url")
                    val replace = OutboundMessage.ReplaceMessage(handle = handle, content = content)
                    replace.handle shouldBe handle
                    replace.content shouldBe content

                    val modalHandle = ModalOpenHandle(raw = "trigger.1")
                    val form =
                        ModalForm.StandupSetup(creatorId = "U9", commandChannel = ConversationTarget(id = "C_SETUP"))
                    val openModal = OutboundMessage.OpenModal(handle = modalHandle, form = form)
                    openModal.handle shouldBe modalHandle
                    openModal.form shouldBe form

                    val approval = approvalContents()
                    val approvalMessage =
                        OutboundMessage.Approval(
                            target = target,
                            recipient = recipient,
                            approval = approval,
                            routingExtras = listOf("extra"),
                        )
                    approvalMessage.target shouldBe target
                    approvalMessage.recipient shouldBe recipient
                    approvalMessage.approval shouldBe approval
                    approvalMessage.routingExtras shouldBe listOf("extra")

                    val notice =
                        OutboundMessage.Notice(
                            target = target,
                            mentions = listOf(recipient),
                            message = "heads up",
                        )
                    notice.target shouldBe target
                    notice.mentions shouldBe listOf(recipient)
                    notice.message shouldBe "heads up"
                }

                then("Approval routingExtras defaults to empty") {
                    val approvalMessage =
                        OutboundMessage.Approval(
                            target = target,
                            recipient = null,
                            approval = approvalContents(),
                        )
                    approvalMessage.recipient shouldBe null
                    approvalMessage.routingExtras shouldBe emptyList()
                }
            }
        }
    })

private fun approvalContents(): ApprovalContents =
    ApprovalContents(
        reason = "please approve",
        publisherId = "U1",
        idempotencyKey = UUID.randomUUID(),
        commandDetailType = CommandDetailType.NOTHING,
    )
