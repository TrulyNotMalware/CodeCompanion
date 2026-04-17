package dev.notypie.application.exception

import dev.notypie.exception.meeting.DatabaseException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class ControllerAdvice {
    @ExceptionHandler(value = [DatabaseException::class])
    fun handleDatabaseException(e: DatabaseException) {
    }

    @ExceptionHandler(value = [UnsupportedSlackCommandTypeException::class])
    fun handleUnsupportedSlackCommandType(
        e: UnsupportedSlackCommandTypeException,
    ): ResponseEntity<Map<String, String>> {
        logger.warn { "Received unsupported Slack command type '${e.rawCommandType}': ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "Unsupported Slack command type: ${e.rawCommandType}"))
    }
}
