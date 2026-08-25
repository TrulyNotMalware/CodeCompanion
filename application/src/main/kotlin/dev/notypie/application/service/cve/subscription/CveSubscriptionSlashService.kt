package dev.notypie.application.service.cve.subscription

import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

/**
 * Slash entry points for the CVE subscription flows. `/subscribe` and `/unsubscribe` open a topic
 * multi-select modal; `/subscriptions` DMs the current list without a modal. Every method is a no-op
 * when the CVE feature is disabled.
 */
interface CveSubscriptionSlashService {
    fun handleSubscribe(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )

    fun handleUnsubscribe(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )

    fun handleSubscriptions(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )
}
