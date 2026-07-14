package dev.notypie.application.service.cve.query

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.entity.slash.CveLatestSlashCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap

/**
 * Builds the `/latest` slash command and runs it through the [CommandExecutor], mirroring
 * [dev.notypie.application.service.cve.subscription.CveSubscriptionSlashServiceImpl]. The optional
 * topic-key argument rides through as plain data; the listener owns the persistence read and the DM.
 * Fail-closed on the feature gate at this layer (the listener re-checks in case of a runtime toggle).
 */
@Service
class CveQuerySlashServiceImpl(
    private val appConfig: AppConfig,
    private val commandExecutor: CommandExecutor,
) : CveQuerySlashService {
    private val log = KotlinLogging.logger {}

    @Transactional
    override fun handleLatest(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        if (!appConfig.cve.enabled) {
            log.warn { "CVE feature disabled; ignoring /latest from ${commandData.actorId}" }
            return
        }
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        commandExecutor.execute(
            command =
                CveLatestSlashCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                    topicKey = extractTopicKey(subCommands = commandData.subCommands),
                ),
        )
    }

    companion object {
        // Keys are lowercase by convention; normalizing makes `/latest Kotlin` find `kotlin`.
        internal fun extractTopicKey(subCommands: List<String>): String? =
            subCommands.firstOrNull { it.isNotBlank() }?.lowercase()
    }
}
