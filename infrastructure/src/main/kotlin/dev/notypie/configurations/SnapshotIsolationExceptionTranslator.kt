package dev.notypie.configurations

import org.springframework.dao.DataAccessException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.jdbc.support.SQLExceptionTranslator
import java.sql.SQLException

private const val MAX_CAUSE_DEPTH = 16

class SnapshotIsolationConflictException(
    message: String,
    cause: Throwable,
) : OptimisticLockingFailureException(message, cause)

class SnapshotIsolationExceptionTranslator : SQLExceptionTranslator {
    private val defaultTranslator = SQLExceptionSubclassTranslator()

    override fun translate(task: String, sql: String?, ex: SQLException): DataAccessException? =
        when {
            ex.isSnapshotIsolationFailure() ->
                SnapshotIsolationConflictException(message = "$task; SQL [$sql]; ${ex.message}", cause = ex)
            task.startsWith(HIBERNATE_OPERATION_TASK) -> null
            else -> defaultTranslator.translate(task, sql, ex)
        }

    companion object {
        const val ER_CHECKREAD = 1020
        private const val HIBERNATE_OPERATION_TASK = "Hibernate operation: "
    }
}

private fun SQLException.isSnapshotIsolationFailure(): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<SQLException>()
        .any { it.errorCode == SnapshotIsolationExceptionTranslator.ER_CHECKREAD }
