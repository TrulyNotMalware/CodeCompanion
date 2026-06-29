package dev.notypie.application.common

import org.springframework.transaction.support.TransactionTemplate

/**
 * Runs [action] in the receiver's transaction, converting a thrown exception into a failed [Result]
 * and marking the transaction rollback-only. The `?:` fallback covers `execute`'s nullable return —
 * we always produce a non-null [Result].
 */
inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> =
    execute<Result<T>> { status ->
        runCatching { action() }.onFailure { status.setRollbackOnly() }
    } ?: Result.failure(IllegalStateException("transactionTemplate.execute returned null"))
