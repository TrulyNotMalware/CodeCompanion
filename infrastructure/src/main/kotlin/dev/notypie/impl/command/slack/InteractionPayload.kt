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
    val container: Container,
    val channel: Channel,
    val responseUrl: String,
    val states: List<States>,
    val currentAction: States,
    val routingExtras: List<String> = emptyList(),
    val privateMetadata: String? = null,
) {
    override fun toString(): String =
        "InteractionPayload(type=$type, team=$team, user=$user, triggerId=$triggerId, isEnterprise=$isEnterprise, " +
            "enterprise=$enterprise, idempotencyKey=$idempotencyKey, apiAppId=$apiAppId, botId=$botId, " +
            "container=$container, channel=$channel, responseUrl=redacted, states=$states, " +
            "currentAction=$currentAction, routingExtras=${if (routingExtras.isEmpty()) "[]" else "redacted"}, " +
            "privateMetadata=${privateMetadata?.let { "redacted" }})"
}

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
