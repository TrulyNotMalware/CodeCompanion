package dev.notypie.domain.command

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.DirectReply
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage

fun createAgentQuestionMessage(
    prompt: String = "Why is the build slow?",
    requesterName: String = TEST_USER_NAME,
    channelName: String = TEST_CHANNEL_NAME,
    channel: String = TEST_CHANNEL_ID,
    fallback: DirectReply? = null,
): OutboundMessage.ChannelMessage =
    OutboundMessage.ChannelMessage(
        target = ConversationTarget(id = channel),
        content =
            MessageContent.AgentQuestion(
                prompt = prompt,
                requesterName = requesterName,
                channelName = channelName,
            ),
        detailType = CommandDetailType.AGENT_ASK_QUESTION,
        fallback = fallback,
    )
