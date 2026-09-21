package dev.notypie.application.common

import org.springframework.transaction.support.TransactionTemplate

// Also marks the transaction rollback-only on failure — callers rely on this to abort the tx.
inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> =
    execute<Result<T>> { status ->
        runCatching { action() }.onFailure { status.setRollbackOnly() }
    } ?: Result.failure(IllegalStateException("transactionTemplate.execute returned null"))
