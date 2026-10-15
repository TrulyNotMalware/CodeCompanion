package dev.notypie.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

data class CapturedLogs<out T>(
    val result: T,
    val events: List<ILoggingEvent>,
) {
    fun eventsAt(level: Level): List<ILoggingEvent> = events.eventsAt(level = level)

    fun messagesAt(level: Level): List<String> = events.messagesAt(level = level)
}

fun List<ILoggingEvent>.eventsAt(level: Level): List<ILoggingEvent> = filter { it.level == level }

fun List<ILoggingEvent>.messagesAt(level: Level): List<String> = eventsAt(level = level).map { it.formattedMessage }

fun <T> captureLogs(loggerName: String, action: () -> T): CapturedLogs<T> {
    val logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    val result =
        try {
            action()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    return CapturedLogs(result = result, events = appender.list.toList())
}
