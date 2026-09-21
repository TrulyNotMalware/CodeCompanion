package dev.notypie.impl.command.slack

import dev.notypie.domain.command.entity.CommandDetailType

data class InteractionPayload(
    val type: CommandDetailType,
    val team: Team,
    val user: User,
    val triggerId: String,
    val isEnterprise: Boolean,
    val enterprise: Enterprise? = null,
    val idempotencyKey: String,
    val apiAppId: String,
    val botId: String,
    val token: String,
    val container: Container,
    val channel: Channel,
    val responseUrl: String,
    val states: List<States>,
    val currentAction: States,
    val routingExtras: List<String> = emptyList(),
    val privateMetadata: String? = null,
)

fun InteractionPayload.isCompleted(): Boolean =
    currentAction.type.isPrimary &&
        currentAction.isSelected &&
        states.all {
            it.isSelected ||
                it.type == ActionElementTypes.CHECKBOX ||
                it.type == ActionElementTypes.PLAIN_TEXT_INPUT
        }

fun InteractionPayload.isPrimary() = currentAction.type.isPrimary

fun InteractionPayload.isCanceled() = currentAction.type == ActionElementTypes.REJECT_BUTTON
