package dev.notypie.impl.command

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.createOpenViewEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class SlackViewOpenDispatcherTest :
    BehaviorSpec({
        val database = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build()
        afterSpec { database.shutdown() }
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(database))

        fun recordingDispatcher(openedInsideTransaction: MutableList<Boolean>): SlackViewOpenDispatcher {
            val messageDispatcher = mockk<MessageDispatcher>()
            every { messageDispatcher.dispatchImmediate(event = any()) } answers {
                openedInsideTransaction += TransactionSynchronizationManager.isActualTransactionActive()
                mockk<CommandOutput>(relaxed = true)
            }
            return SlackViewOpenDispatcher(messageDispatcher = messageDispatcher)
        }

        given("a modal staged inside a transaction") {
            `when`("the caller owns the boundary through ViewOpenDeferral") {
                val openedInsideTransaction = mutableListOf<Boolean>()
                val dispatcher = recordingDispatcher(openedInsideTransaction = openedInsideTransaction)
                val openedBeforeCommit = mutableListOf<Boolean>()

                ViewOpenDeferral.afterBoundary {
                    transactionTemplate.executeWithoutResult {
                        dispatcher.listenOpenViewEvent(event = createOpenViewEvent())
                        openedBeforeCommit += openedInsideTransaction.isNotEmpty()
                    }
                }

                then("views.open runs once, after the transaction released its connection") {
                    openedBeforeCommit shouldBe listOf(false)
                    openedInsideTransaction shouldBe listOf(false)
                }
            }

            `when`("the transaction fails") {
                val openedInsideTransaction = mutableListOf<Boolean>()
                val dispatcher = recordingDispatcher(openedInsideTransaction = openedInsideTransaction)

                shouldThrow<IllegalStateException> {
                    ViewOpenDeferral.afterBoundary {
                        transactionTemplate.executeWithoutResult {
                            dispatcher.listenOpenViewEvent(event = createOpenViewEvent())
                            error("command failed")
                        }
                    }
                }

                then("the modal for the rolled-back command is not opened") {
                    openedInsideTransaction.shouldBeEmpty()
                }
            }

            `when`("no caller collects view opens") {
                val openedInsideTransaction = mutableListOf<Boolean>()
                val dispatcher = recordingDispatcher(openedInsideTransaction = openedInsideTransaction)

                transactionTemplate.executeWithoutResult {
                    dispatcher.listenOpenViewEvent(event = createOpenViewEvent())
                }

                then("views.open runs immediately, as before") {
                    openedInsideTransaction shouldBe listOf(true)
                }
            }
        }
    })
