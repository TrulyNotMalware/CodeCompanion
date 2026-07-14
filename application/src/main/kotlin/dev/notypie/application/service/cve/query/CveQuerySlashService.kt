package dev.notypie.application.service.cve.query

import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

/** Entry point for the CVE read-only slash commands (`/latest`), mirroring CveSubscriptionSlashService. */
interface CveQuerySlashService {
    fun handleLatest(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )
}
