package dev.notypie.application.exception

import dev.notypie.exception.meeting.DatabaseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ControllerAdvice {
    @ExceptionHandler(value = [DatabaseException::class])
    fun handleDatabaseException(e: DatabaseException) {
    }
}
