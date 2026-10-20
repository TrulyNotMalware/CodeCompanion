package dev.notypie.domain.command.outbound

import dev.notypie.domain.TEST_SLACK_RESPONSE_URL
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.AddParticipantParsed
import dev.notypie.domain.command.entity.context.form.RescheduleMeetingParsed
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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

        given("a response handle inside the data classes that carry it") {
            val handle = ResponseReplaceHandle(raw = TEST_SLACK_RESPONSE_URL)
            val meetingUid = UUID.randomUUID()
            val requesterId = "U1"
            val channel = ConversationTarget(id = "C1")
            val newStartAt = LocalDateTime.of(2026, 1, 1, 9, 0)
            val content = MessageContent.Text(headline = null, markdown = "hello")

            `when`("they are printed") {
                val nonNullCarrier = OutboundMessage.ReplaceMessage(handle = handle, content = content)
                val nullableCarriers =
                    listOf(
                        ModalForm.Reschedule(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            channel = channel,
                            listHandle = handle,
                        ),
                        ModalForm.AddParticipant(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            channel = channel,
                            listHandle = handle,
                        ),
                        CommandIntent.CancelMeeting(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            listHandle = handle,
                        ),
                        CommandIntent.RescheduleMeeting(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            newStartAt = newStartAt,
                            listHandle = handle,
                        ),
                        CommandIntent.AddParticipant(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            participantUserIds = listOf("U2"),
                            listHandle = handle,
                        ),
                        AddParticipantParsed(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            participantUserIds = listOf("U2"),
                            listHandle = handle,
                        ),
                        RescheduleMeetingParsed(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            newStartAt = newStartAt,
                            listHandle = handle,
                        ),
                    )

                then("neither a non-null nor a nullable handle property prints the URL") {
                    (nullableCarriers + nonNullCarrier).forEach { carrier ->
                        carrier.toString() shouldNotContain "hooks.slack.com"
                    }
                    nonNullCarrier.toString() shouldContain "handle=ResponseReplaceHandle(redacted)"
                    nullableCarriers.forEach { carrier ->
                        carrier.toString() shouldContain "listHandle=ResponseReplaceHandle(redacted)"
                    }
                }

                then("the handle prints redacted on its own, in a template and boxed in a collection") {
                    handle.toString() shouldBe "ResponseReplaceHandle(redacted)"
                    "$handle" shouldBe "ResponseReplaceHandle(redacted)"
                    listOf(handle).toString() shouldBe "[ResponseReplaceHandle(redacted)]"
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

                    val approval = approvalContents()
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
                            listHandle = null,
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
                            listHandle = null,
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

                then("a channel message and an ephemeral carry no direct-reply fallback unless one is given") {
                    OutboundMessage.ChannelMessage(target = target, content = content).fallback shouldBe null
                    OutboundMessage.Ephemeral(target = target, content = content).fallback shouldBe null
                    val reply = DirectReply(recipient = recipient, content = content)
                    val channel = OutboundMessage.ChannelMessage(target = target, content = content, fallback = reply)
                    val ephemeral = OutboundMessage.Ephemeral(target = target, content = content, fallback = reply)
                    channel.fallback shouldBe reply
                    ephemeral.fallback shouldBe reply
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

        given("the direct-reply fallbacks for a channel that refused the reply") {
            val channel = ConversationTarget(id = "C_REFUSED")
            val requester = UserRef(id = "U_REQUESTER")

            `when`("the neutral notice is built") {
                val notice = channelRefusedNotice(channel = channel, recipient = requester)

                then("it names the channel, blames no single cause, and goes to the requester without a headline") {
                    notice shouldBe
                        DirectReply(
                            recipient = requester,
                            content =
                                MessageContent.Text(
                                    headline = null,
                                    markdown =
                                        "I couldn't reply in <#C_REFUSED>: the post there was refused. " +
                                            "If I'm not a member of that channel, invite me and try again.",
                                ),
                        )
                }
            }

            `when`("a reply with content is copied as the fallback") {
                val copy =
                    directFallbackOf(
                        channel = channel,
                        recipient = requester,
                        content =
                            MessageContent.Text(
                                headline = "CodeCompanion — standup",
                                markdown = "line 1\nline 2",
                            ),
                    )

                then("one line naming the channel precedes the original markdown, and the headline is kept") {
                    copy shouldBe
                        DirectReply(
                            recipient = requester,
                            content =
                                MessageContent.Text(
                                    headline = "CodeCompanion — standup",
                                    markdown =
                                        "_(Sent here because my reply in <#C_REFUSED> was refused.)_\nline 1\nline 2",
                                ),
                        )
                }
            }

            `when`("the channel id carries mrkdwn control characters") {
                val hostile = ConversationTarget(id = "C1> <https://evil.example|Re-authorize & go")
                val text = MessageContent.Text(headline = null, markdown = "body")
                val notice = channelRefusedNotice(channel = hostile, recipient = requester)
                val copy = directFallbackOf(channel = hostile, recipient = requester, content = text)
                val askNotice = askRefusedNotice(channel = hostile, recipient = requester, prompt = "body")

                then("every helper escapes the id, so the channel mention stays one closed token") {
                    val escaped = "C1&gt; &lt;https://evil.example|Re-authorize &amp; go"
                    notice.content.markdown shouldStartWith
                        "I couldn't reply in <#$escaped>: the post there was refused."
                    copy.content.markdown shouldBe "_(Sent here because my reply in <#$escaped> was refused.)_\nbody"
                    askNotice.content.markdown shouldStartWith
                        "I couldn't post your question in <#$escaped>: the post there was refused,"
                }
            }

            `when`("the refused conversation is a direct message the bot is not in") {
                val direct = ConversationTarget(id = "D_REFUSED")
                val text = MessageContent.Text(headline = null, markdown = "body")
                val notice = channelRefusedNotice(channel = direct, recipient = requester)
                val copy = directFallbackOf(channel = direct, recipient = requester, content = text)

                then("both helpers speak of that direct message and never render the id as a mention") {
                    notice.content.markdown shouldBe
                        "I couldn't reply in that direct message: I can only post in conversations I'm part of. " +
                        "Run the command again in a channel I've joined."
                    copy.content.markdown shouldBe "_(Sent here because I can't post in that direct message.)_\nbody"
                }
            }

            `when`("the refused conversation has a G id (a group DM or a legacy private channel) the bot is not in") {
                val group = ConversationTarget(id = "G_REFUSED")
                val text = MessageContent.Text(headline = null, markdown = "body")
                val notice = channelRefusedNotice(channel = group, recipient = requester)
                val copy = directFallbackOf(channel = group, recipient = requester, content = text)

                then("both helpers speak of that conversation and never render the id as a mention") {
                    notice.content.markdown shouldBe
                        "I couldn't reply in that conversation: I can only post in conversations I'm part of. " +
                        "Add me to it, or run the command again in a channel I've joined."
                    copy.content.markdown shouldBe "_(Sent here because I can't post in that conversation.)_\nbody"
                }
            }
        }

        given("conversation ids classified by their first character") {
            `when`("kind() reads each id") {
                then("D is DIRECT, G is GROUP, and anything else, lower-case d and g included, is CHANNEL") {
                    ConversationTarget(id = "D012ABCDEFG").kind() shouldBe ConversationKind.DIRECT
                    ConversationTarget(id = "G012ABCDEFG").kind() shouldBe ConversationKind.GROUP
                    ConversationTarget(id = "C012ABCDEFG").kind() shouldBe ConversationKind.CHANNEL
                    ConversationTarget(id = "d012abcdefg").kind() shouldBe ConversationKind.CHANNEL
                    ConversationTarget(id = "g012abcdefg").kind() shouldBe ConversationKind.CHANNEL
                    ConversationTarget(id = "").kind() shouldBe ConversationKind.CHANNEL
                }
            }
        }

        given("the notice for an /ask question whose post was refused") {
            val requester = UserRef(id = "U_REQUESTER")
            val prompt = "why is CI red?"

            `when`("the question was asked in a channel") {
                val notice =
                    askRefusedNotice(
                        channel = ConversationTarget(id = "C_REFUSED"),
                        recipient = requester,
                        prompt = prompt,
                    )

                then("it names the channel, says the question was not answered, and quotes the question") {
                    notice shouldBe
                        DirectReply(
                            recipient = requester,
                            content =
                                MessageContent.Text(
                                    headline = null,
                                    markdown =
                                        "I couldn't post your question in <#C_REFUSED>: the post there was refused, " +
                                            "so it was *not* answered. If I'm not a member of that channel, " +
                                            "invite me and ask again, or ask in a channel I've joined." +
                                            "\n\n> why is CI red?",
                                ),
                        )
                }
            }

            `when`("the question was asked in a direct message") {
                val notice =
                    askRefusedNotice(
                        channel = ConversationTarget(id = "D_REFUSED"),
                        recipient = requester,
                        prompt = prompt,
                    )

                then("it speaks of that direct message without a mention and quotes the question") {
                    notice.content.markdown shouldBe
                        "I couldn't post your question in that direct message, so it was *not* answered. " +
                        "I can only post in conversations I'm part of: ask again in a channel I've joined." +
                        "\n\n> why is CI red?"
                }
            }

            `when`("the question was asked in a conversation with a G id") {
                val notice =
                    askRefusedNotice(
                        channel = ConversationTarget(id = "G_REFUSED"),
                        recipient = requester,
                        prompt = prompt,
                    )

                then("it speaks of that conversation without a mention and quotes the question") {
                    notice.content.markdown shouldBe
                        "I couldn't post your question in that conversation, so it was *not* answered. " +
                        "Add me to it and ask again, or ask in a channel I've joined." +
                        "\n\n> why is CI red?"
                }
            }

            `when`("the question spans lines broken by LF, a line separator and CRLF") {
                val notice =
                    askRefusedNotice(
                        channel = ConversationTarget(id = "C_REFUSED"),
                        recipient = requester,
                        prompt = "first\nsecond\u2028third\r\nfourth",
                    )

                then("every line is quoted on its own and no separator is left inside the quote") {
                    notice.content.markdown shouldEndWith "\n\n> first\n> second\n> third\n> fourth"
                    notice.content.markdown shouldNotContain "\u2028"
                    notice.content.markdown shouldNotContain "\r"
                }
            }

            `when`("the question carries mrkdwn control characters") {
                val notice =
                    askRefusedNotice(
                        channel = ConversationTarget(id = "C_REFUSED"),
                        recipient = requester,
                        prompt = "<!channel> & <https://evil.example|Re-authorize>",
                    )

                then("the quoted question is escaped, so it can neither ping the channel nor forge a link") {
                    notice.content.markdown shouldEndWith
                        "\n\n> &lt;!channel&gt; &amp; &lt;https://evil.example|Re-authorize&gt;"
                }
            }

            `when`("the escaped question is at or beyond the quote budget") {
                fun quotedPart(prompt: String): String =
                    askRefusedNotice(
                        channel = ConversationTarget(id = "C_REFUSED"),
                        recipient = requester,
                        prompt = prompt,
                    ).content
                        .markdown
                        .substringAfter(delimiter = "\n\n")

                then("a question at the budget is quoted whole") {
                    quotedPart(prompt = "x".repeat(n = 1_500)) shouldBe "> " + "x".repeat(n = 1_500)
                }

                then("a longer question is cut to the budget and marked with an ellipsis") {
                    quotedPart(prompt = "x".repeat(n = 2_000)) shouldBe "> " + "x".repeat(n = 1_500) + "…"
                }

                then("the cut drops an escape entity it would split instead of leaving part of it") {
                    quotedPart(prompt = "x".repeat(n = 1_498) + "&" + "y".repeat(n = 10)) shouldBe
                        "> " + "x".repeat(n = 1_498) + "…"
                }

                then("an escape entity that ends exactly at the budget is kept whole before the ellipsis") {
                    quotedPart(prompt = "x".repeat(n = 1_495) + "&" + "y".repeat(n = 10)) shouldBe
                        "> " + "x".repeat(n = 1_495) + "&amp;…"
                }

                then("the cut never leaves half of a surrogate pair") {
                    quotedPart(prompt = "x".repeat(n = 1_499) + "\uD83D\uDE00" + "y") shouldBe
                        "> " + "x".repeat(n = 1_499) + "…"
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
