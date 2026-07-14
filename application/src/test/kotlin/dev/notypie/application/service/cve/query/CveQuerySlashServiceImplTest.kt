package dev.notypie.application.service.cve.query

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.entity.slash.CveLatestSlashCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.SlashInvocation
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

class CveQuerySlashServiceImplTest :
    BehaviorSpec({
        val noHeaders: MultiValueMap<String, String> = LinkedMultiValueMap()
        val payload = mockk<SlashCommandRequestBody>(relaxed = true)

        fun commandDataWith(subCommands: List<String> = emptyList()): InboundCommand =
            InboundCommand(
                appId = "A123",
                appToken = "t",
                actorId = "U_LATEST",
                actorName = "latest",
                channel = "C_CMD",
                channelName = "cmd",
                kind = InboundKind.SLASH,
                payload = SlashInvocation(trigger = TriggerHandle(raw = "trig")),
                subCommands = subCommands,
            )

        fun serviceWith(enabled: Boolean, commandExecutor: CommandExecutor): CveQuerySlashServiceImpl =
            CveQuerySlashServiceImpl(
                appConfig = AppConfig(cve = AppConfig.Cve(enabled = enabled)),
                commandExecutor = commandExecutor,
            )

        given("the feature is disabled") {
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val service = serviceWith(enabled = false, commandExecutor = commandExecutor)

            `when`("/latest arrives") {
                service.handleLatest(headers = noHeaders, payload = payload, commandData = commandDataWith())

                then("nothing is executed") {
                    verify(exactly = 0) { commandExecutor.execute<SubCommandDefinition>(command = any()) }
                }
            }
        }

        given("the feature is enabled") {
            val commandExecutor = mockk<CommandExecutor>(relaxed = true)
            val service = serviceWith(enabled = true, commandExecutor = commandExecutor)

            `when`("/latest arrives") {
                service.handleLatest(
                    headers = noHeaders,
                    payload = payload,
                    commandData = commandDataWith(subCommands = listOf("kotlin")),
                )

                then("exactly one latest command runs through the executor") {
                    verify(exactly = 1) {
                        commandExecutor.execute<NoSubCommands>(command = match { it is CveLatestSlashCommand })
                    }
                }
            }
        }

        given("the topic-key extraction rules") {
            `when`("there is no argument") {
                then("the key is null (read across the caller's subscriptions)") {
                    CveQuerySlashServiceImpl.extractTopicKey(subCommands = emptyList()).shouldBeNull()
                }
            }

            `when`("blank arguments precede the real one") {
                then("blanks are skipped") {
                    CveQuerySlashServiceImpl.extractTopicKey(subCommands = listOf("  ", "spring")) shouldBe "spring"
                }
            }

            `when`("the argument is upper-cased") {
                then("the key is normalized to the lowercase convention") {
                    CveQuerySlashServiceImpl.extractTopicKey(subCommands = listOf("Kotlin")) shouldBe "kotlin"
                }
            }
        }
    })
