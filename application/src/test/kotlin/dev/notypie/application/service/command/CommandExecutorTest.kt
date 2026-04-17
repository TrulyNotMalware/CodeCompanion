package dev.notypie.application.service.command

import dev.notypie.domain.command.TestCommand
import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.impl.command.SlackIntentResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class CommandExecutorTest :
    BehaviorSpec({
        isolationMode = IsolationMode.InstancePerLeaf

        val intentResolver = mockk<SlackIntentResolver>()
        val eventPublisher = mockk<EventPublisher>()
        val executor =
            CommandExecutor(
                intentResolver = intentResolver,
                eventPublisher = eventPublisher,
            )

        given("a command that produces a single intent") {
            val intent = CommandIntent.TextResponse(headLine = "hi", message = "world")
            val idempotencyKey = UUID.randomUUID()
            val command =
                TestCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = createAppMentionSlackCommandData(),
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
                        commandType = any(),
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
                            commandType = CommandType.SIMPLE,
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
                        commandType = any(),
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
                        commandType = any(),
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
                    commandData = createAppMentionSlackCommandData(),
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
                            commandType = any(),
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
                    commandData = createAppMentionSlackCommandData(),
                    intentToProduce = CommandIntent.Nothing,
                )

            `when`("execute is called") {
                every {
                    intentResolver.resolveAll(
                        intents = any(),
                        basicInfo = any(),
                        commandType = any(),
                    )
                } returns emptyList()

                executor.execute(command = command)

                then("publisher is not invoked when resolver yields empty list") {
                    verify(exactly = 0) { eventPublisher.publishEvent(events = any()) }
                }
            }
        }
    })
