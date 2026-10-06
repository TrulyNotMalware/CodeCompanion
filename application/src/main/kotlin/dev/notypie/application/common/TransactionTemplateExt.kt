package dev.notypie.application.common

import org.springframework.transaction.support.TransactionTemplate

// Also marks the transaction rollback-only on failure — callers rely on this to abort the tx.
// Commit-time failures (flush, BEFORE_COMMIT listeners) surface from execute() and become a failure too.
inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> =
    runCatching {
        execute<Result<T>> { status -> runCatching { action() }.onFailure { status.setRollbackOnly() } }
    }.getOrElse { commitFailure -> Result.failure(commitFailure) }
