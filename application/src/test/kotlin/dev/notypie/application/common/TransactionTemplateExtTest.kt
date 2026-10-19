package dev.notypie.application.common

import dev.notypie.application.service.meeting.createH2DataSource
import dev.notypie.application.service.meeting.createH2TransactionManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class TransactionTemplateExtTest :
    BehaviorSpec({
        val dataSource = createH2DataSource()
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE tx_probe (id INT PRIMARY KEY)")
        val template = TransactionTemplate(createH2TransactionManager(dataSource = dataSource))

        fun probeRows(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM tx_probe", Int::class.java)!!

        given("runInTx on a real transaction manager") {
            `when`("the action succeeds") {
                jdbc.update("DELETE FROM tx_probe")
                val result = template.runInTx { jdbc.update("INSERT INTO tx_probe (id) VALUES (1)") }

                then("the write commits and the result is a success") {
                    result.getOrNull() shouldBe 1
                    probeRows() shouldBe 1
                }
            }

            `when`("the action throws") {
                jdbc.update("DELETE FROM tx_probe")
                val result =
                    template.runInTx {
                        jdbc.update("INSERT INTO tx_probe (id) VALUES (2)")
                        error("action failed")
                    }

                then("the write rolls back and the failure is returned") {
                    result.exceptionOrNull()?.message shouldBe "action failed"
                    probeRows() shouldBe 0
                }
            }

            `when`("the action returns but the commit fails in a BEFORE_COMMIT callback") {
                jdbc.update("DELETE FROM tx_probe")
                val result =
                    template.runInTx {
                        jdbc.update("INSERT INTO tx_probe (id) VALUES (3)")
                        TransactionSynchronizationManager.registerSynchronization(
                            object : TransactionSynchronization {
                                override fun beforeCommit(readOnly: Boolean): Unit =
                                    throw IllegalStateException("outbox write failed at commit")
                            },
                        )
                    }

                then("the commit failure is returned as a failure instead of escaping the caller's onFailure") {
                    result.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>().message shouldBe
                        "outbox write failed at commit"
                    probeRows() shouldBe 0
                }
            }

            `when`("the action throws an Error") {
                jdbc.update("DELETE FROM tx_probe")
                val thrown = StackOverflowError("deep")
                val escaped =
                    shouldThrow<StackOverflowError> {
                        template.runInTx {
                            jdbc.update("INSERT INTO tx_probe (id) VALUES (4)")
                            throw thrown
                        }
                    }

                then("the write rolls back and the same Error propagates instead of becoming a failure") {
                    escaped shouldBeSameInstanceAs thrown
                    probeRows() shouldBe 0
                }
            }

            `when`("the action throws an InterruptedException") {
                jdbc.update("DELETE FROM tx_probe")
                val thrown = InterruptedException("shutting down")
                var keptInterrupt = false
                val result =
                    try {
                        template.runInTx {
                            jdbc.update("INSERT INTO tx_probe (id) VALUES (5)")
                            throw thrown
                        }
                    } finally {
                        keptInterrupt = Thread.interrupted()
                    }

                then("the write rolls back, the interrupt is the failure and the interrupt flag stays set") {
                    result.exceptionOrNull() shouldBeSameInstanceAs thrown
                    keptInterrupt shouldBe true
                    probeRows() shouldBe 0
                }
            }
        }

        given("runInTx when the rollback itself fails") {
            `when`("the action throws an Error and the transaction manager cannot roll back") {
                val rollbackFailure = TransactionSystemException("rollback failed")
                val manager =
                    mockk<PlatformTransactionManager> {
                        every { getTransaction(any()) } returns mockk<TransactionStatus>(relaxed = true)
                        every { rollback(any()) } throws rollbackFailure
                    }
                val thrown = StackOverflowError("deep")
                val escaped =
                    shouldThrow<StackOverflowError> { TransactionTemplate(manager).runInTx<Unit> { throw thrown } }

                then("the same Error propagates with the rollback failure attached as suppressed") {
                    escaped shouldBeSameInstanceAs thrown
                    escaped.suppressed.single() shouldBeSameInstanceAs rollbackFailure
                }
            }

            `when`("the action throws an Error and the rollback fails with a translated runtime exception") {
                val rollbackFailure = IllegalStateException("rollback failed")
                val manager =
                    mockk<PlatformTransactionManager> {
                        every { getTransaction(any()) } returns mockk<TransactionStatus>(relaxed = true)
                        every { rollback(any()) } throws rollbackFailure
                    }
                val thrown = StackOverflowError("deep")
                val escaped =
                    shouldThrow<StackOverflowError> { TransactionTemplate(manager).runInTx<Unit> { throw thrown } }

                then("the Error is still the one that propagates, whatever the rollback threw") {
                    escaped shouldBeSameInstanceAs thrown
                    escaped.suppressed.single() shouldBeSameInstanceAs rollbackFailure
                }
            }
        }
    })
