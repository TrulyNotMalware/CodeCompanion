package dev.notypie.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.slf4j.LoggerFactory

class LogCaptureTest :
    BehaviorSpec({
        val loggerName = LogCaptureTest::class.java.name
        val logger = LoggerFactory.getLogger(loggerName) as Logger

        fun attachedAppenders(): List<Appender<ILoggingEvent>> = logger.iteratorForAppenders().asSequence().toList()

        given("an action that logs at WARN and ERROR and returns a value") {
            `when`("it runs inside a capture") {
                val (value, logs) =
                    captureLogs(loggerName = loggerName) {
                        logger.warn("first warning")
                        logger.error("one error")
                        logger.warn("second warning")
                        42
                    }

                then("the action's value and every line logged during it come back, filtered by level") {
                    value shouldBe 42
                    logs.map { it.level } shouldBe listOf(Level.WARN, Level.ERROR, Level.WARN)
                    logs.messagesAt(level = Level.WARN) shouldBe listOf("first warning", "second warning")
                    logs.messagesAt(level = Level.ERROR) shouldBe listOf("one error")
                    logs.eventsAt(level = Level.ERROR).map { it.loggerName } shouldBe listOf(loggerName)
                }
            }
        }

        given("a line logged after the capture returned") {
            `when`("the capture is read afterwards") {
                val logs = captureLogs(loggerName = loggerName) { logger.warn("during") }
                logger.warn("after")

                then("only the line logged during the action is in it, and the logger keeps no appender") {
                    logs.messagesAt(level = Level.WARN) shouldBe listOf("during")
                    logs.eventsAt(level = Level.WARN).map { it.formattedMessage } shouldBe listOf("during")
                    attachedAppenders().shouldBeEmpty()
                }
            }
        }

        given("an action that throws") {
            val failure = IllegalStateException("boom")

            `when`("it runs inside a capture") {
                val thrown =
                    shouldThrow<IllegalStateException> {
                        captureLogs(loggerName = loggerName) { throw failure }
                    }

                then("the same exception propagates and the logger keeps no appender to capture later lines") {
                    thrown shouldBeSameInstanceAs failure
                    attachedAppenders().shouldBeEmpty()
                }
            }
        }

        given("a capture on a package name") {
            `when`("a logger below the package and a logger outside it both log") {
                val logs =
                    captureLogs(loggerName = LogCaptureTest::class.java.packageName) {
                        LoggerFactory.getLogger("$loggerName.Child").warn("child")
                        LoggerFactory.getLogger("dev.notypie.other.LogCaptureProbe").warn("other")
                    }

                then("only the line from below the package is captured") {
                    logs.messagesAt(level = Level.WARN) shouldBe listOf("child")
                }
            }
        }
    })
