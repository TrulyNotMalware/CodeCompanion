package dev.notypie.application.exception

import dev.notypie.application.security.SlackHeaders
import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.exception.meeting.DatabaseException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

private val log = KotlinLogging.logger {}

private val CONTROL_CHARACTERS = Regex("\\p{Cntrl}")

internal fun String.stripControlCharacters(): String = replace(regex = CONTROL_CHARACTERS, replacement = "?")

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
        val commandType = e.rawCommandType.stripControlCharacters()
        log.warn { "Received unsupported Slack command type '$commandType'" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .header(SlackHeaders.NO_RETRY, "1")
            .body(mapOf("error" to "unsupported_command_type"))
    }

    @ExceptionHandler(value = [AppIdNotFoundException::class, InvalidEventPayloadException::class])
    fun handleUnreadablePayload(e: CodeCompanionRuntimeException): ResponseEntity<Map<String, String>> {
        log.warn(e) { "Rejected a Slack payload that cannot be read: ${e.errorCode.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .header(SlackHeaders.NO_RETRY, "1")
            .body(mapOf("error" to "invalid_payload"))
    }

    @ExceptionHandler(value = [Exception::class])
    fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error(e) { "Unhandled exception while handling a Slack request: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "internal_error"))
    }
}
