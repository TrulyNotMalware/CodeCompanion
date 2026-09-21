package dev.notypie.domain.command

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.slash.CveSubscribeSlashCommand
import dev.notypie.domain.command.entity.slash.CveUnsubscribeSlashCommand
import dev.notypie.domain.command.entity.slash.SetupStandupCommand
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.TopicOption
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID

class SlashPayloadResolutionTest :
    BehaviorSpec({
        val topics = listOf(TopicOption(key = "spring", label = "Spring"))

        fun modalOpens(effects: List<CommandEffect>) = effects.filterIsInstance<OutboundMessage.OpenModal>()

        given("commands built from a genuine slash payload") {
            `when`("each command handles its event") {
                val setup =
                    SetupStandupCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = createSlashInboundCommand(subCommands = listOf("setup"), triggerId = "trigger-1"),
                    )
                val subscribe =
                    CveSubscribeSlashCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = createSlashInboundCommand(triggerId = "trigger-2"),
                        topics = topics,
                    )
                val unsubscribe =
                    CveUnsubscribeSlashCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = createSlashInboundCommand(triggerId = "trigger-3"),
                        topics = topics,
                    )

                then("each succeeds and opens its modal") {
                    setup.handleEvent().ok shouldBe true
                    modalOpens(setup.drainIntents()).size shouldBe 1
                    subscribe.handleEvent().ok shouldBe true
                    modalOpens(subscribe.drainIntents()).size shouldBe 1
                    unsubscribe.handleEvent().ok shouldBe true
                    modalOpens(unsubscribe.drainIntents()).size shouldBe 1
                }
            }
        }

        given("commands mis-built with a non-slash payload") {
            `when`("each command handles its event") {
                val commands =
                    listOf(
                        SetupStandupCommand(
                            idempotencyKey = UUID.randomUUID(),
                            commandData = createMentionInboundCommand(),
                        ),
                        CveSubscribeSlashCommand(
                            idempotencyKey = UUID.randomUUID(),
                            commandData = createMentionInboundCommand(),
                            topics = topics,
                        ),
                        CveUnsubscribeSlashCommand(
                            idempotencyKey = UUID.randomUUID(),
                            commandData = createMentionInboundCommand(),
                            topics = topics,
                        ),
                    )

                then("each failure stays inside the command boundary as an ERROR_RESPONSE") {
                    commands.forEach { command ->
                        val output = command.handleEvent()
                        output.ok shouldBe false
                        output.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                        command.drainIntents().shouldBeEmpty()
                    }
                }
            }
        }
    })
