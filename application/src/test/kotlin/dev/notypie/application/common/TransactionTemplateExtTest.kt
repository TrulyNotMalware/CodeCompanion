package dev.notypie.application.common

import dev.notypie.application.service.meeting.createH2DataSource
import dev.notypie.application.service.meeting.createH2TransactionManager
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.jdbc.core.JdbcTemplate
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
        }
    })
