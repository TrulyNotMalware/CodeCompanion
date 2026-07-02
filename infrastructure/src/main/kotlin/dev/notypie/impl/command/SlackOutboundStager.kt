package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

/**
 * Slack adapter that renders the text, approval/form and modal families of [OutboundMessage]s into
 * staged [CommandEvent]s. Variants not yet migrated fail loudly.
 */
class SlackOutboundStager(
    private val slackEventBuilder: SlackApiEventConstructor,
    private val standupRepository: StandupRepository,
) : OutboundMessageStager {
    override fun stage(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (message) {
            is OutboundMessage.ChannelMessage ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline.orEmpty(),
                            commandBasicInfo = basicInfo,
                            simpleString = content.markdown,
                        )

                    is MessageContent.ErrorNotice ->
                        slackEventBuilder.detailErrorTextRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.ERROR_RESPONSE,
                            errorClassName = content.className,
                            errorMessage = content.message,
                            details = content.details,
                            commandBasicInfo = basicInfo,
                        )

                    is MessageContent.Schedule ->
                        slackEventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            timeScheduleInfo = content.info,
                        )

                    is MessageContent.Form ->
                        slackEventBuilder.simpleApprovalFormRequest(
                            commandDetailType = message.detailType ?: CommandDetailType.APPROVAL_REQUEST,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            selectionFields = content.fields,
                            reasonInput = content.reason,
                            approvalContents = content.approval,
                        )

                    is MessageContent.MeetingRequest ->
                        slackEventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = message.detailType ?: CommandDetailType.MEETING_CREATE_REQUEST,
                            approvalContents = content.approval,
                        )

                    is MessageContent.StandupSummary ->
                        slackEventBuilder.standupSummaryRequest(
                            commandBasicInfo = basicInfo,
                            routineName = content.routineName,
                            sessionDate = content.sessionDate,
                            members = content.members,
                            answers = content.answers,
                            questions = content.questions,
                        )

                    else -> error("not yet migrated: $content")
                }

            is OutboundMessage.Ephemeral ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = content.markdown,
                            commandBasicInfo = basicInfo,
                            commandDetailType = message.detailType ?: CommandDetailType.SIMPLE_TEXT,
                            targetUserId = message.recipient?.id,
                        )

                    is MessageContent.MeetingList ->
                        slackEventBuilder.getMeetingListFormRequest(
                            myMeetings = content.meetings,
                            commandBasicInfo = basicInfo,
                            commandDetailType = message.detailType ?: CommandDetailType.GET_MEETING_LIST,
                            currentUserId = content.currentUserId,
                        )

                    else -> error("Ephemeral content not yet migrated: $content")
                }

            is OutboundMessage.Approval ->
                slackEventBuilder.simpleApplyRejectRequest(
                    commandDetailType = message.approval.commandDetailType,
                    commandBasicInfo = basicInfo,
                    approvalContents = message.approval,
                    targetUserId = message.recipient?.id,
                    // Carry the subtitle (meeting title) through the routing text so handlers avoid a
                    // DB lookup; blanks are dropped to keep the token stable for flows without a subtitle.
                    routingExtras =
                        listOf(message.approval.subTitle).filter { it.isNotBlank() } + message.routingExtras,
                )

            is OutboundMessage.Notice -> {
                val userMentions = message.mentions.joinToString(" ") { "<@${it.id}>" }
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    headLineText = "Notice!",
                    commandBasicInfo = basicInfo,
                    simpleString = "[Notice] $userMentions ${message.message}",
                )
            }

            is OutboundMessage.UpdateMessage -> {
                val content = message.content
                check(content is MessageContent.Text) { "UpdateMessage content must be Text: $content" }
                slackEventBuilder.updateNoticeMessageRequest(
                    commandBasicInfo = basicInfo,
                    commandDetailType = message.detailType,
                    channel = message.ref.conversation.id,
                    messageTs = message.ref.messageId,
                    markdownText = content.markdown,
                )
            }

            is OutboundMessage.ReplaceMessage -> {
                val content = message.content
                check(content is MessageContent.Text) { "ReplaceMessage content must be Text: $content" }
                slackEventBuilder.replaceOriginalText(
                    markdownText = content.markdown,
                    responseUrl = message.handle.raw,
                    commandBasicInfo = basicInfo,
                    commandDetailType = CommandDetailType.REPLACE_TEXT,
                )
            }

            is OutboundMessage.OpenModal ->
                when (val form = message.form) {
                    is ModalForm.Reschedule ->
                        if (message.handle.raw.isBlank()) {
                            log.warn {
                                "Blank triggerId; cannot open reschedule modal for meetingUid=${form.meetingUid}"
                            }
                            null
                        } else {
                            slackEventBuilder.openRescheduleMeetingModalRequest(
                                commandBasicInfo = basicInfo,
                                commandDetailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST,
                                triggerId = message.handle.raw,
                                meetingUid = form.meetingUid,
                                requesterId = form.requesterId,
                                channel = form.channel.id,
                                // Message carries no stored start; the host adjusts both pickers anyway.
                                currentStartAt = LocalDateTime.now(),
                            )
                        }

                    is ModalForm.AddParticipant ->
                        if (message.handle.raw.isBlank()) {
                            log.warn {
                                "Blank triggerId; cannot open add-participant modal for meetingUid=${form.meetingUid}"
                            }
                            null
                        } else {
                            slackEventBuilder.openAddParticipantModalRequest(
                                commandBasicInfo = basicInfo,
                                commandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST,
                                triggerId = message.handle.raw,
                                meetingUid = form.meetingUid,
                                requesterId = form.requesterId,
                                channel = form.channel.id,
                            )
                        }

                    is ModalForm.StandupSetup ->
                        if (message.handle.raw.isBlank()) {
                            log.warn {
                                "Blank triggerId; cannot open standup setup modal for creatorId=${form.creatorId}"
                            }
                            null
                        } else {
                            slackEventBuilder.openStandupSetupModalRequest(
                                commandBasicInfo = basicInfo,
                                commandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST,
                                triggerId = message.handle.raw,
                                creatorId = form.creatorId,
                                commandChannel = form.commandChannel.id,
                            )
                        }

                    is ModalForm.StandupFill ->
                        if (message.handle.raw.isBlank()) {
                            log.warn { "Blank triggerId; cannot open standup modal for sessionUid=${form.sessionUid}" }
                            null
                        } else {
                            val routine = standupRepository.getRoutine(routineUid = form.routineUid)
                            val session = standupRepository.findSession(sessionUid = form.sessionUid)
                            if (session == null) {
                                log.warn { "Standup session not found: sessionUid=${form.sessionUid}" }
                                null
                            } else {
                                slackEventBuilder.openStandupModalRequest(
                                    commandBasicInfo = basicInfo,
                                    commandDetailType = CommandDetailType.STANDUP_PROMPT,
                                    triggerId = message.handle.raw,
                                    sessionUid = form.sessionUid,
                                    routineName = routine.name,
                                    sessionDate = session.sessionDate,
                                    questions = routine.questions,
                                    userId = form.requesterId,
                                    noticeChannel = form.originNotice.conversation.id,
                                    noticeMessageTs = form.originNotice.messageId,
                                )
                            }
                        }

                    is ModalForm.DeclineReason ->
                        slackEventBuilder.openDeclineReasonModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
                            triggerId = message.handle.raw,
                            meetingIdempotencyKey = form.meetingIdempotencyKey,
                            participantUserId = form.participantUserId,
                            meetingTitle = form.meetingTitle,
                            noticeChannel =
                                form.originNotice
                                    ?.conversation
                                    ?.id
                                    .orEmpty(),
                            noticeMessageTs = form.originNotice?.messageId.orEmpty(),
                        )
                }

            else -> error("OutboundMessage variant not yet migrated to stager: $message")
        }
}
