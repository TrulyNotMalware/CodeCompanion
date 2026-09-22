package dev.notypie.application.exception

import dev.notypie.exception.meeting.DatabaseException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

private val log = KotlinLogging.logger {}

// Extends ResponseEntityExceptionHandler so Spring MVC's own exceptions (404 static resource, 400 unreadable
// body, 405 method) keep their status; the Exception catch-all below would otherwise turn them into 500s.
@RestControllerAdvice
class ControllerAdvice : ResponseEntityExceptionHandler() {
    @ExceptionHandler(value = [DatabaseException::class])
    fun handleDatabaseException(e: DatabaseException): ResponseEntity<Map<String, String>> {
        log.error(e) { "Database failure on table '${e.tableName}' while handling a Slack request: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "internal_error"))
    }

    @ExceptionHandler(value = [UnsupportedSlackCommandTypeException::class])
    fun handleUnsupportedSlackCommandType(
        e: UnsupportedSlackCommandTypeException,
    ): ResponseEntity<Map<String, String>> {
        log.warn { "Received unsupported Slack command type '${e.rawCommandType}': ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "Unsupported Slack command type: ${e.rawCommandType}"))
    }

    @ExceptionHandler(value = [Exception::class])
    fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error(e) { "Unhandled exception while handling a Slack request: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "internal_error"))
    }
}
