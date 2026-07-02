package dev.notypie.domain.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_TEAM_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.InboundAction
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundField
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.inbound.TriggerHandle
import java.util.UUID

fun createMentionInboundCommand(
    mentionedUserIds: List<String> = emptyList(),
    commandTokens: List<String> = listOf("help"),
    hasCommandStructure: Boolean = true,
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    actorId: String = TEST_USER_ID,
    actorName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
    channelName: String = "general",
    teamId: String? = TEST_TEAM_ID,
): InboundCommand =
    InboundCommand(
        appId = appId,
        appToken = appToken,
        actorId = actorId,
        actorName = actorName,
        channel = channel,
        channelName = channelName,
        teamId = teamId,
        kind = InboundKind.MENTION,
        payload =
            MentionInvocation(
                mentionedUserIds = mentionedUserIds,
                commandTokens = commandTokens,
                hasCommandStructure = hasCommandStructure,
            ),
    )

fun createInteractionInboundCommand(
    commandDetailType: CommandDetailType = CommandDetailType.APPROVAL_REQUEST,
    action: InboundAction = approveAction(isSelected = true),
    form: List<InboundField> = emptyList(),
    idempotencyKey: UUID = UUID.randomUUID(),
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    actorId: String = TEST_USER_ID,
    actorName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
): InboundCommand =
    createInteractionResponseInboundCommand(
        interaction =
            createInboundInteraction(
                detailType = commandDetailType,
                action = action,
                form = form,
                idempotencyKey = idempotencyKey,
            ),
        appId = appId,
        appToken = appToken,
        actorId = actorId,
        actorName = actorName,
        channel = channel,
    )

fun createInteractionResponseInboundCommand(
    interaction: InboundInteraction,
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    actorId: String = TEST_USER_ID,
    actorName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
    channelName: String = TEST_CHANNEL_NAME,
): InboundCommand =
    InboundCommand(
        appId = appId,
        appToken = appToken,
        actorId = actorId,
        actorName = actorName,
        channel = channel,
        channelName = channelName,
        kind = InboundKind.INTERACTION,
        payload = interaction,
    )

fun createSlashInboundCommand(
    subCommands: List<String> = emptyList(),
    triggerId: String = "",
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    actorId: String = TEST_USER_ID,
    actorName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
    channelName: String = TEST_CHANNEL_NAME,
): InboundCommand =
    InboundCommand(
        appId = appId,
        appToken = appToken,
        actorId = actorId,
        actorName = actorName,
        channel = channel,
        channelName = channelName,
        kind = InboundKind.SLASH,
        subCommands = subCommands,
        payload = SlashInvocation(trigger = TriggerHandle(raw = triggerId)),
    )
