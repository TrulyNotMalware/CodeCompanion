package dev.notypie.application.service.command

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.domain.command.TestCommand
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.slash.CalendarCommand
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.SlackIntentResolver
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import java.util.UUID

class CommandExecutorTest :
    BehaviorSpec({
        isolationMode = IsolationMode.InstancePerLeaf

        val intentResolver = mockk<SlackIntentResolver>()
        val outboundStager = mockk<OutboundMessageStager>()
        val eventPublisher = mockk<EventPublisher>()
        val executor =
            CommandExecutor(
                intentResolver = intentResolver,
                outboundStager = outboundStager,
                eventPublisher = eventPublisher,
            )

        given("a command that produces a single intent") {
            val intent = CommandIntent.StatusReport
            val idempotencyKey = UUID.randomUUID()
            val command =
                TestCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = createMentionInboundCommand(),
                    intentToProduce = intent,
                )

            `when`("resolver and publisher both succeed") {
                val resolvedEvent =
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        idempotencyKey = idempotencyKey,
                    )
                every {
                    intentResolver.resolveAll(
                        intents = any(),
                        basicInfo = any(),
                    )
                } returns listOf(resolvedEvent)
                every { eventPublisher.publishEvent(events = any()) } just Runs

                val output = executor.execute(command = command)

                then("returns successful command output, drains intents, resolver + publisher invoked once") {
                    output.ok.shouldBeTrue()
                    command.drainIntents().isEmpty() shouldBe true
                    verify(exactly = 1) {
                        intentResolver.resolveAll(
                            intents = listOf(intent),
                            basicInfo = any(),
                        )
                    }
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("resolver throws an exception") {
                every {
                    intentResolver.resolveAll(
                        intents = any(),
                        basicInfo = any(),
                    )
                } throws RuntimeException("resolver failure")

                then("execute re-throws and publisher is never invoked") {
                    shouldThrow<RuntimeException> {
                        executor.execute(command = command)
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("publisher throws after successful resolution") {
                val resolvedEvent =
                    createSendSlackMessageEvent(
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        idempotencyKey = idempotencyKey,
                    )
                every {
                    intentResolver.resolveAll(
                        intents = any(),
                        basicInfo = any(),
                    )
                } returns listOf(resolvedEvent)
                every { eventPublisher.publishEvent(events = any()) } throws RuntimeException("publish failure")

                then("execute re-throws and intents remain drained (no re-queue)") {
                    shouldThrow<RuntimeException> {
                        executor.execute(command = command)
                    }
                    command.drainIntents().isEmpty() shouldBe true
                }
            }
        }

        given("a command that produces no intents") {
            val command =
                TestCommand(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createMentionInboundCommand(),
                    intentToProduce = null,
                )

            `when`("execute is called") {
                val output = executor.execute(command = command)

                then("returns successful output and resolver is never invoked") {
                    output.ok.shouldBeTrue()
                    verify(exactly = 0) {
                        intentResolver.resolveAll(
                            intents = any(),
                            basicInfo = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(events = any()) }
                }
            }
        }

        given("a command whose resolver returns no events") {
            val command =
                TestCommand(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createMentionInboundCommand(),
                    intentToProduce = CommandIntent.Nothing,
                )

            `when`("execute is called") {
                every {
                    intentResolver.resolveAll(
                        intents = any(),
                        basicInfo = any(),
                    )
                } returns emptyList()

                executor.execute(command = command)

                then("publisher is not invoked when resolver yields empty list") {
                    verify(exactly = 0) { eventPublisher.publishEvent(events = any()) }
                }
            }
        }

        given("a command that produces an effect the executor cannot classify") {
            val command =
                TestCommand(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createMentionInboundCommand(),
                    rawEffectToProduce = object : CommandEffect {},
                )

            `when`("execute drains the effect queue") {
                then("the unknown effect fails loudly instead of being dropped") {
                    shouldThrow<IllegalStateException> { executor.execute(command = command) }
                        .message shouldContain "Unclassified CommandEffect"
                }
            }
        }

        given("commands whose outputs differ in how they failed") {
            fun warningsWhile(block: () -> Unit): List<ILoggingEvent> {
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                val logger = LoggerFactory.getLogger(CommandExecutor::class.java) as Logger
                logger.addAppender(appender)
                try {
                    block()
                } finally {
                    logger.detachAppender(appender)
                }
                return appender.list.filter { it.level == Level.WARN }
            }
            val staged = mutableListOf<OutboundMessage>()
            every { intentResolver.resolveAll(intents = any(), basicInfo = any()) } returns emptyList()
            every { outboundStager.stage(message = capture(staged), basicInfo = any()) } returns
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    idempotencyKey = UUID.randomUUID(),
                )
            every { eventPublisher.publishEvent(events = any()) } just Runs

            `when`("a slash command's context throws") {
                val command =
                    TestCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = createSlashInboundCommand(appToken = "xoxb-secret-token"),
                        failure = IllegalStateException("database down"),
                    )
                val warnings = warningsWhile { executor.execute(command = command) }

                then("one WARN names the command, its ids and the reason, never the app token") {
                    warnings.size shouldBe 1
                    val line = warnings.single().formattedMessage
                    line shouldContain "Command TestCommand failed"
                    line shouldContain "commandId=${command.commandId}"
                    line shouldContain "idempotencyKey=${command.idempotencyKey}"
                    line shouldContain "kind=SLASH"
                    line shouldContain "reason=java.lang.IllegalStateException: database down"
                    line shouldNotContain "xoxb-secret-token"
                }

                then("the generic error reply goes out through the stager like any other outbound") {
                    staged.filterIsInstance<OutboundMessage.Ephemeral>().size shouldBe 1
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("a command succeeds, or its context fails on purpose with its own reply") {
                val warnings =
                    warningsWhile {
                        executor.execute(
                            command =
                                TestCommand(
                                    idempotencyKey = UUID.randomUUID(),
                                    commandData = createMentionInboundCommand(),
                                ),
                        )
                        executor.execute(
                            command =
                                CalendarCommand(
                                    idempotencyKey = UUID.randomUUID(),
                                    commandData = createSlashInboundCommand(subCommands = listOf("bogus")),
                                ),
                        )
                    }

                then("nothing is logged at WARN") {
                    warnings.shouldBeEmpty()
                }
            }
        }
    })
