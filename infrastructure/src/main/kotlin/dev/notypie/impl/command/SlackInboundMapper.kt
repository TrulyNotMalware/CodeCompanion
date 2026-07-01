package dev.notypie.impl.command

import dev.notypie.domain.command.inbound.InboundAction
import dev.notypie.domain.command.inbound.InboundActionRole
import dev.notypie.domain.command.inbound.InboundActor
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundField
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundForm
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.inbound.ReplyHandle
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.InteractionPayload
import dev.notypie.impl.command.slack.States

/**
 * Adapts the Slack-shaped [InteractionPayload] into the transport-neutral [InboundInteraction] the
 * domain contexts consume. The order of [InboundForm.fields] mirrors the parser's `states` order,
 * which several contexts read positionally (e.g. the meeting form's start/end TIME pickers).
 */
fun InteractionPayload.toInbound(): InboundInteraction =
    InboundInteraction(
        detailType = type,
        actor = InboundActor(id = user.id),
        channelId = channel.id,
        trigger = TriggerHandle(raw = triggerId),
        reply = ReplyHandle(raw = responseUrl),
        message = container.messageTs?.let { MessageHandle(raw = it) },
        idempotencyKey = idempotencyKey,
        routingExtras = routingExtras,
        form = InboundForm(fields = states.map { it.toInboundField() }),
        action = currentAction.toInboundAction(),
    )

fun States.toInboundField(): InboundField =
    InboundField(
        key = blockId,
        kind = type.toInboundFieldKind(),
        isSelected = isSelected,
        rawValue = selectedValue,
    )

fun States.toInboundAction(): InboundAction =
    InboundAction(
        role =
            when (type) {
                ActionElementTypes.APPLY_BUTTON -> InboundActionRole.APPROVE
                ActionElementTypes.REJECT_BUTTON -> InboundActionRole.REJECT
                ActionElementTypes.BUTTON -> InboundActionRole.ACTIVATE
                else -> InboundActionRole.PASSIVE
            },
        isSelected = isSelected,
    )

private fun ActionElementTypes.toInboundFieldKind(): InboundFieldKind =
    when (this) {
        ActionElementTypes.PLAIN_TEXT_INPUT -> InboundFieldKind.TEXT
        ActionElementTypes.STATIC_SELECT, ActionElementTypes.RADIO_BUTTONS -> InboundFieldKind.CHOICE
        ActionElementTypes.MULTI_STATIC_SELECT -> InboundFieldKind.MULTI_CHOICE
        ActionElementTypes.MULTI_USERS_SELECT -> InboundFieldKind.USERS
        ActionElementTypes.CONVERSATIONS_SELECT -> InboundFieldKind.CONVERSATION
        ActionElementTypes.DATE_PICKER -> InboundFieldKind.DATE
        ActionElementTypes.TIME_PICKER -> InboundFieldKind.TIME
        ActionElementTypes.CHECKBOX -> InboundFieldKind.TOGGLE
        else -> InboundFieldKind.UNKNOWN
    }

/**
 * Boundary translation from the parsed Slack payload into the queue-carried [InboundCommand].
 * The neutral [InboundInteraction] rides directly in [InboundCommand.payload]; identity fields keep
 * reading the Slack payload directly (this runs in the application/infra layer, not the domain).
 */
fun InteractionPayload.toInboundCommand() =
    InboundCommand(
        appId = apiAppId,
        appToken = token,
        actorId = user.id,
        actorName = user.name,
        channel = channel.id,
        channelName = channel.name,
        payload = this.toInbound(),
        kind = InboundKind.INTERACTION,
        teamId = team.id,
    )
